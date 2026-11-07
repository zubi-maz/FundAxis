/*
 * ============================================================================
 * FUNDAXIS — CONTRIBUTION REPORTING SERVICE
 * ============================================================================
 * PURPOSE: Read-only financial reports for employee contributions.
 * This class does NOT create dues, receive payments, or change share holdings.
 *
 * QUICK NAVIGATION (Ctrl+F using these titles):
 *   [REPORTING-COMMON]     Shared date/payment/settlement helpers
 *   [REPORTING-STATEMENT]  One employee's contribution statement
 *   [REPORTING-MONTHLY]    Monthly summary used by workspace Overview
 *   [REPORTING-DASHBOARD]  Overview/yearly aggregate reports
 *   [SINGLE-MONTH-DTO]     MonthlyRecordRow + MonthlyFinancialSummary types
 *   [SINGLE-MONTH-ROWS]    getMonthlyRecords(...): potential service-layer list
 *   [SINGLE-MONTH-TOTALS]  getMonthlyFinancialSummary(...): service-layer totals
 *   [MONTHLY-REGISTER]    getMonthlyContributionRegister(...): multiple months
 *
 * IMPORTANT — ACTUAL CURRENT PAGE WIRING:
 *   Monthly Records tab (single):
 *     AdminContributionWorkspaceController.workspace()
 *       -> processSingleMonthRecords(...)
 *       -> model attributes monthlyRecords / monthlySummary
 *       -> contribution-workspace.html (view='single')
 *
 *   This service's getMonthlyRecords()/getMonthlyFinancialSummary() methods are
 *   NOT presently called by that controller's single-month workflow.
 *   It would be misleading to change only these methods and expect the
 *   visible Single Month page to change. Integrate deliberately in a later
 *   refactor, after reconciling the different DTO records and calculations.
 *
 *   Monthly Records tab (register):
 *     AdminContributionWorkspaceController.processMonthlyRegister(...)
 *       -> ContributionReportingService.getMonthlyContributionRegister(...)
 *
 *   Overview:
 *     workspace() -> monthly(...) and dashboard(...)
 *   Yearly records:
 *     workspace() -> dashboard(...)
 *   Member statement:
 *     workspace() -> member(...)
 *
 * Keep the class name ContributionReportingService: it is referenced by
 * multiple modules and fits its broad responsibilities.
 * ============================================================================
 */

package com.fundaxis.service;

import com.fundaxis.dto.*;
import com.fundaxis.entity.*;
import com.fundaxis.exception.EmployeeNotFoundException;
import com.fundaxis.repository.*;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ContributionReportingService {

    @PersistenceContext
    private EntityManager entityManager;

    private static final List<ShareTransaction.ShareTransactionType> QUANTITY_TRANSACTION_TYPES = List.of(
            ShareTransaction.ShareTransactionType.INITIAL_ALLOCATION,
            ShareTransaction.ShareTransactionType.INCREASE,
            ShareTransaction.ShareTransactionType.DECREASE,
            ShareTransaction.ShareTransactionType.WITHDRAWAL_POSTED);

    // =====================================================
    // 1. DEPENDENCIES
    // =====================================================

    private final ContributionReportingRepository contributionRepo;
    private final ContributionPaymentReportingRepository paymentRepo;
    private final EmployeeRepository employeeRepo;
    private final ShareHoldingRepository sharesRepo;
    private final MemberCreditService memberCreditService;
    private final ShareTransactionRepository shareTransactionRepo;

    public ContributionReportingService(
            ContributionReportingRepository contributionRepo,
            ContributionPaymentReportingRepository paymentRepo,
            EmployeeRepository employeeRepo,
            ShareHoldingRepository sharesRepo,
            MemberCreditService memberCreditService,
            ShareTransactionRepository shareTransactionRepo) {

        this.contributionRepo = contributionRepo;
        this.paymentRepo = paymentRepo;
        this.employeeRepo = employeeRepo;
        this.sharesRepo = sharesRepo;
        this.memberCreditService = memberCreditService;
        this.shareTransactionRepo = shareTransactionRepo;
    }

    // =====================================================
    // [REPORTING-COMMON] 2. REPORTING WINDOW — SHARED DATE VALIDATION
    // =====================================================

    private record Window(LocalDate start, LocalDate end) {}

    private Window window(
            int fromYear,
            int fromMonth,
            int toYear,
            int toMonth) {

        if (fromYear < 1900 || fromYear > 9999
                || toYear < 1900 || toYear > 9999
                || fromMonth < 1 || fromMonth > 12
                || toMonth < 1 || toMonth > 12) {
            throw new IllegalArgumentException("Invalid reporting year/month");
        }

        LocalDate from = LocalDate.of(fromYear, fromMonth, 1);
        LocalDate to = LocalDate.of(toYear, toMonth, 1);

        if (from.isAfter(to)) {
            throw new IllegalArgumentException(
                    "fromMonth must not exceed toMonth");
        }

        if (ChronoUnit.MONTHS.between(from, to) > 119) {
            throw new IllegalArgumentException(
                    "Reporting range cannot exceed 120 months");
        }

        return new Window(from, to);
    }

    // =====================================================
    // [REPORTING-COMMON] 3. PAYMENT AGGREGATE — ACTIVE PAYMENTS ONLY
    // =====================================================

    private record PaymentAggregate(
            BigDecimal collected,
            int payments,
            int reversals
    ) {}


    /**
     * Aggregates direct cash payments only.
     * Reversed payments are excluded from collected cash.
     */
    private Map<Long, PaymentAggregate> paymentsByContribution(
            List<Contribution> dues) {

        if (dues.isEmpty()) {
            return Map.of();
        }

        List<Long> ids = dues.stream()
                .map(Contribution::getId)
                .toList();

        List<ContributionPayment> allPayments = new ArrayList<>();
        for (int offset = 0; offset < ids.size(); offset += 500) {
            allPayments.addAll(paymentRepo.findPaymentsForContributions(
                    ids.subList(offset, Math.min(offset + 500, ids.size()))));
        }
        Map<Long, List<ContributionPayment>> grouped = allPayments.stream()
                .collect(Collectors.groupingBy(p -> p.getContribution().getId()));

        Map<Long, PaymentAggregate> result = new HashMap<>();

        for (Long id : ids) {
            BigDecimal paid = zero();
            int active = 0;
            int reversed = 0;

            for (ContributionPayment payment :
                    grouped.getOrDefault(id, List.of())) {

                if (payment.isReversed()) {
                    reversed++;
                } else {
                    paid = paid.add(payment.getPaymentAmount());
                    active++;
                }
            }

            result.put(id, new PaymentAggregate(paid, active, reversed));
        }

        return result;
    }

    // =====================================================
    // [REPORTING-COMMON] 4. COMMON HELPERS
    // =====================================================

    private int quantityAt(ShareHolding holding, LocalDate asOf) {
        if (holding.getAllocationDate() != null && holding.getAllocationDate().isAfter(asOf)) return 0;
        List<ShareTransaction> transactions = shareTransactionRepo.findByShareHoldingIdOrderByEffectiveDateAscIdAsc(holding.getId());
        List<ShareTransaction> quantityTransactions = transactions.stream()
                .filter(t -> QUANTITY_TRANSACTION_TYPES.contains(t.getTransactionType())).toList();
        boolean hasInitialAllocation = quantityTransactions.stream()
                .anyMatch(t -> t.getTransactionType() == ShareTransaction.ShareTransactionType.INITIAL_ALLOCATION);
        long quantity = hasInitialAllocation ? 0 : holding.getShareQuantity()
                - quantityTransactions.stream().mapToLong(ShareTransaction::getQuantityChange).sum();
        boolean found = false;
        for (ShareTransaction transaction : quantityTransactions) {
            if (!transaction.getEffectiveDate().isAfter(asOf)) {
                quantity = Math.addExact(quantity, transaction.getQuantityChange());
                found = true;
            }
        }
        if (!found && quantityTransactions.isEmpty()) return holding.getShareQuantity();
        return Math.toIntExact(quantity);
    }

    private LocalDate shareCutoffForMonth(LocalDate month) {
        return month.with(java.time.temporal.TemporalAdjusters.lastDayOfMonth());
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }

    private static boolean is(
            Contribution contribution,
            Contribution.ContributionStatus status) {
        return contribution.getContributionStatus() == status;
    }

    private static BigDecimal amountOrZero(BigDecimal amount) {
        return amount == null ? zero() : amount;
    }

    // =====================================================
    // [REPORTING-COMMON] 5. BUILD INDIVIDUAL CONTRIBUTION LINE
    // =====================================================

    /**
     * collected = active direct cash payments
     * creditApplied = active member-credit allocations
     * totalSettled = collected + creditApplied
     * outstanding = expected - totalSettled
     */
    private ContributionStatementLine line(
            Contribution contribution,
            Map<Long, PaymentAggregate> paid) {

        return line(contribution, paid, memberCreditService.getAppliedTotal(contribution.getId()));
    }

    private ContributionStatementLine line(Contribution contribution,
            Map<Long, PaymentAggregate> paid, BigDecimal credit) {
        PaymentAggregate aggregate = paid.getOrDefault(
                contribution.getId(),
                new PaymentAggregate(zero(), 0, 0));

        boolean canceled = is(
                contribution,
                Contribution.ContributionStatus.CANCELED);

        BigDecimal cashCollected = amountOrZero(aggregate.collected());
        BigDecimal appliedCredit = amountOrZero(credit);

        if (cashCollected.signum() < 0 || appliedCredit.signum() < 0) {
            throw new IllegalStateException(
                    "Negative settlement detected. Contribution ID: "
                            + contribution.getId());
        }

        if (canceled) {
            if (cashCollected.signum() != 0
                    || appliedCredit.signum() != 0) {
                throw new IllegalStateException(
                        "Canceled contribution has active settlement: "
                                + contribution.getId());
            }

            return new ContributionStatementLine(
                    contribution.getId(),
                    contribution.getContributionMonth(),
                    zero(),
                    zero(),
                    zero(),
                    zero(),
                    zero(),
                    contribution.getContributionStatus(),
                    aggregate.payments(),
                    aggregate.reversals());
        }

        BigDecimal expected = amountOrZero(
                contribution.getContributionAmount());

        BigDecimal totalSettled = cashCollected.add(appliedCredit);
        BigDecimal outstanding = expected.subtract(totalSettled);

        if (outstanding.signum() < 0) {
            throw new IllegalStateException(
                    "Contribution over-settlement detected. ID: "
                            + contribution.getId());
        }

        return new ContributionStatementLine(
                contribution.getId(),
                contribution.getContributionMonth(),
                expected,
                cashCollected,
                appliedCredit,
                totalSettled,
                outstanding,
                contribution.getContributionStatus(),
                aggregate.payments(),
                aggregate.reversals());
    }

    /** Bounded operational page, using the same cash/credit/cancellation rules as statements. */
    @Transactional(readOnly = true)
    public Map<Long, ContributionStatementLine> operationLines(List<Contribution> contributions) {
        var paid = paymentsByContribution(contributions);
        var credits = memberCreditService.getAppliedTotals(contributions.stream().map(Contribution::getId).toList());
        return contributions.stream().collect(Collectors.toMap(Contribution::getId,
                c -> line(c, paid, credits.get(c.getId()))));
    }

    // =====================================================
    // [REPORTING-STATEMENT] 6. MEMBER CONTRIBUTION STATEMENT
    // =====================================================

    @Transactional(readOnly = true)
    public MemberContributionStatementResponse member(
            String employeeId,
            int fromYear,
            int fromMonth,
            int toYear,
            int toMonth) {

        if (employeeId == null || employeeId.isBlank()) {
            throw new IllegalArgumentException("Employee ID is required");
        }

        Window period = window(fromYear, fromMonth, toYear, toMonth);

        Employee employee = employeeRepo.findByEmployeeId(employeeId.trim())
                .orElseThrow(() -> new EmployeeNotFoundException(
                        "Employee not found: " + employeeId));

        List<Contribution> dues = contributionRepo.findMemberPeriod(
                employee.getId(),
                period.start(),
                period.end());

        Map<Long, PaymentAggregate> payments = paymentsByContribution(dues);

        Map<Long, BigDecimal> credits = memberCreditService.getAppliedTotals(
                dues.stream().map(Contribution::getId).toList());
        List<ContributionStatementLine> lines = dues.stream()
                .map(contribution -> line(contribution, payments, credits.get(contribution.getId())))
                .toList();

        BigDecimal expected = zero();
        BigDecimal collected = zero();
        BigDecimal creditApplied = zero();
        BigDecimal totalSettled = zero();
        BigDecimal outstanding = zero();

        for (ContributionStatementLine statementLine : lines) {
            expected = expected.add(statementLine.expected());
            collected = collected.add(statementLine.collected());
            creditApplied = creditApplied.add(statementLine.creditApplied());
            totalSettled = totalSettled.add(statementLine.totalSettled());
            outstanding = outstanding.add(statementLine.outstanding());
        }

        int oldShares = 0;
        int newShares = 0;

        for (ShareHolding holding :
                sharesRepo.findByEmployeeEmployeeId(employee.getEmployeeId())) {

            if (holding.getShareCategory() == ShareHolding.ShareCategory.OLD) {
                oldShares += quantityAt(holding, period.end());
            } else if (holding.getShareCategory()
                    == ShareHolding.ShareCategory.NEW) {
                newShares += quantityAt(holding, period.end());
            }
        }

        return new MemberContributionStatementResponse(
                employee.getEmployeeId(),
                employee.getFullName(),
                period.start(),
                period.end(),
                expected,
                collected,
                creditApplied,
                totalSettled,
                outstanding,
                oldShares,
                newShares,
                lines);
    }

    // =====================================================
    // [REPORTING-MONTHLY] 7. MONTHLY CONTRIBUTION SUMMARY
    // =====================================================

    @Transactional(readOnly = true)
    public MonthlyContributionSummaryResponse monthly(int year, int month) {

        Window period = window(year, month, year, month);

        List<Contribution> dues = contributionRepo.findPeriod(
                period.start(), period.end());

        Map<Long, PaymentAggregate> payments = paymentsByContribution(dues);

        BigDecimal expected = zero();
        BigDecimal collected = zero();
        BigDecimal creditApplied = zero();
        BigDecimal totalSettled = zero();
        BigDecimal outstanding = zero();

        long pending = 0;
        long complete = 0;
        long canceled = 0;
        long paymentCount = 0;
        long reversalCount = 0;

        for (Contribution contribution : dues) {
            ContributionStatementLine statementLine =
                    line(contribution, payments);

            expected = expected.add(statementLine.expected());
            collected = collected.add(statementLine.collected());
            creditApplied = creditApplied.add(statementLine.creditApplied());
            totalSettled = totalSettled.add(statementLine.totalSettled());
            outstanding = outstanding.add(statementLine.outstanding());

            paymentCount += statementLine.paymentCount();
            reversalCount += statementLine.reversalCount();

            if (is(contribution, Contribution.ContributionStatus.PENDING)) {
                pending++;
            } else if (is(contribution, Contribution.ContributionStatus.PAID)) {
                complete++;
            } else if (is(contribution, Contribution.ContributionStatus.CANCELED)) {
                canceled++;
            }
        }

        return new MonthlyContributionSummaryResponse(
                period.start(),
                dues.size(),
                pending,
                complete,
                canceled,
                expected,
                collected,
                creditApplied,
                totalSettled,
                outstanding,
                paymentCount,
                reversalCount);
    }

    // =====================================================
    // [REPORTING-DASHBOARD] 8. CONTRIBUTION DASHBOARD
    // =====================================================

    @Transactional(readOnly = true)
    public ContributionDashboardResponse dashboard(
            int fromYear,
            int fromMonth,
            int toYear,
            int toMonth) {

        Window period = window(fromYear, fromMonth, toYear, toMonth);

        List<Contribution> dues = contributionRepo.findPeriod(
                period.start(), period.end());

        Map<Long, PaymentAggregate> payments = paymentsByContribution(dues);

        BigDecimal expected = zero();
        BigDecimal collected = zero();
        BigDecimal creditApplied = zero();
        BigDecimal totalSettled = zero();
        BigDecimal outstanding = zero();

        long pending = 0;
        long complete = 0;
        long canceled = 0;
        long paymentCount = 0;
        long reversalCount = 0;

        for (Contribution contribution : dues) {
            ContributionStatementLine statementLine =
                    line(contribution, payments);

            expected = expected.add(statementLine.expected());
            collected = collected.add(statementLine.collected());
            creditApplied = creditApplied.add(statementLine.creditApplied());
            totalSettled = totalSettled.add(statementLine.totalSettled());
            outstanding = outstanding.add(statementLine.outstanding());

            paymentCount += statementLine.paymentCount();
            reversalCount += statementLine.reversalCount();

            if (is(contribution, Contribution.ContributionStatus.PENDING)) {
                pending++;
            } else if (is(contribution, Contribution.ContributionStatus.PAID)) {
                complete++;
            } else if (is(contribution, Contribution.ContributionStatus.CANCELED)) {
                canceled++;
            }
        }

        int oldShares = 0;
        int newShares = 0;

        for (ShareHolding holding : sharesRepo.findAllWithEmployee()) {
            if (holding.getShareCategory() == ShareHolding.ShareCategory.OLD) {
                oldShares += quantityAt(holding, LocalDate.now(java.time.ZoneId.of("Asia/Dhaka")));
            } else if (holding.getShareCategory()
                    == ShareHolding.ShareCategory.NEW) {
                newShares += quantityAt(holding, LocalDate.now(java.time.ZoneId.of("Asia/Dhaka")));
            }
        }

        return new ContributionDashboardResponse(
                period.start(),
                period.end(),
                dues.size(),
                pending,
                complete,
                canceled,
                expected,
                collected,
                creditApplied,
                totalSettled,
                outstanding,
                oldShares,
                newShares,
                paymentCount,
                reversalCount);
    }

    // =====================================================
    // [SINGLE-MONTH-DTO] 9. SERVICE DTO RECORDS (NOT THE CONTROLLER DTO)
    // =====================================================

    public record MonthlyRecordRow(
            int slNo,
            Long contributionId,
            String employeeId,
            String employeeFullName,
            String designation,
            int oldShares,
            int newShares,
            int totalShares,
            String shareValue,
            BigDecimal monthlyDue,
            BigDecimal cashReceived,
            BigDecimal memberCreditApplied,
            BigDecimal totalPaid,
            BigDecimal outstanding,
            String status,
            String remarks
    ) {}

    public record MonthlyFinancialSummary(
            int totalMembers,
            BigDecimal totalDue,
            BigDecimal totalCash,
            BigDecimal totalCredit,
            BigDecimal totalSettled,
            BigDecimal totalRemaining,
            long paidCount,
            long pendingCount,
            long canceledCount
    ) {}

    // =====================================================
    // [SINGLE-MONTH-ROWS] 10. GET MONTHLY RECORDS — NOT YET USED BY WORKSPACE SINGLE VIEW
    // =====================================================

    /**
     * Candidate service-layer monthly table implementation.
     * NOT currently used to populate the Admin Monthly Records > Single Month
     * page: that page uses the controller's processSingleMonthRecords(...).
     * Do not assume edits here affect that page until the wiring is changed.
     * Existing share counts here use current holdings, not month-end history.
     */
    @Transactional(readOnly = true)
    public Page<MonthlyRecordRow> getMonthlyRecords(
            int year,
            int month,
            String search,
            String status,
            Pageable pageable) {

        Window period = window(year, month, year, month);

        List<Contribution> dues = contributionRepo.findPeriod(
                period.start(), period.end());

        Map<Long, PaymentAggregate> payments = paymentsByContribution(dues);

        Map<String, List<ShareHolding>> holdingsMap =
                sharesRepo.findAllWithEmployee()
                        .stream()
                        .filter(h -> h.getEmployee() != null)
                        .collect(Collectors.groupingBy(
                                h -> h.getEmployee().getEmployeeId()));

        List<MonthlyRecordRow> rows = new ArrayList<>();

        for (Contribution due : dues) {
            Employee emp = due.getEmployee();

            String empId = emp != null ? emp.getEmployeeId() : "";
            String empName = emp != null ? emp.getFullName() : "";
            String designation = emp != null
                    && emp.getEmployeeDesignation() != null
                    ? emp.getEmployeeDesignation().name()
                    : "";

            List<ShareHolding> holdings =
                    holdingsMap.getOrDefault(empId, List.of());

            int oldShares = 0;
            int newShares = 0;

            for (ShareHolding holding : holdings) {
                if (holding.getShareCategory() == ShareHolding.ShareCategory.OLD) {
                    oldShares += quantityAt(holding, shareCutoffForMonth(due.getContributionMonth()));
                } else if (holding.getShareCategory()
                        == ShareHolding.ShareCategory.NEW) {
                    newShares += quantityAt(holding, shareCutoffForMonth(due.getContributionMonth()));
                }
            }

            int totalShares = oldShares + newShares;

            boolean canceled = is(
                    due, Contribution.ContributionStatus.CANCELED);

            BigDecimal dueAmount = canceled
                    ? zero()
                    : amountOrZero(due.getContributionAmount());

            PaymentAggregate aggregate = payments.getOrDefault(
                    due.getId(),
                    new PaymentAggregate(zero(), 0, 0));

            BigDecimal cash = amountOrZero(aggregate.collected());
            BigDecimal credit = amountOrZero(
                    memberCreditService.getAppliedTotal(due.getId()));

            if (cash.signum() < 0 || credit.signum() < 0) {
                throw new IllegalStateException(
                        "Negative settlement detected. Contribution ID: "
                                + due.getId());
            }

            if (canceled && (cash.signum() != 0 || credit.signum() != 0)) {
                throw new IllegalStateException(
                        "Canceled contribution has active settlement: "
                                + due.getId());
            }

            BigDecimal totalPaid = canceled ? zero() : cash.add(credit);
            BigDecimal remaining = canceled
                    ? zero()
                    : dueAmount.subtract(totalPaid);

            if (remaining.signum() < 0) {
                throw new IllegalStateException(
                        "Contribution over-settlement detected. ID: "
                                + due.getId());
            }

            String statusStr = canceled
                    ? "CANCELED"
                    : remaining.signum() == 0 ? "PAID" : "PENDING";

            String remarks = canceled
                    ? "Contribution canceled"
                    : remaining.signum() == 0
                    ? (credit.signum() > 0
                    ? (cash.signum() > 0
                    ? "Paid by cash + credit"
                    : "Paid from credit")
                    : "Paid in full")
                    : (cash.signum() > 0 || credit.signum() > 0
                    ? "Partially paid"
                    : "Unpaid");

            rows.add(new MonthlyRecordRow(
                    0,
                    due.getId(),
                    empId,
                    empName,
                    designation,
                    oldShares,
                    newShares,
                    totalShares,
                    "N/A",
                    dueAmount,
                    cash,
                    credit,
                    totalPaid,
                    remaining,
                    statusStr,
                    remarks));
        }

        String query = search == null
                ? ""
                : search.trim().toLowerCase(Locale.ROOT);

        String statusFilter = status == null
                ? "ALL"
                : status.trim().toUpperCase(Locale.ROOT);

        List<MonthlyRecordRow> filtered = rows.stream()
                .filter(row -> query.isEmpty()
                        || row.employeeId().toLowerCase(Locale.ROOT).contains(query)
                        || row.employeeFullName().toLowerCase(Locale.ROOT).contains(query))
                .filter(row -> "ALL".equals(statusFilter)
                        || row.status().equalsIgnoreCase(statusFilter))
                .sorted(Comparator.comparing(
                        MonthlyRecordRow::employeeId,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();

        int totalElements = filtered.size();
        int pageSize = pageable.getPageSize() <= 0
                ? 10
                : pageable.getPageSize();

        int pageNumber = Math.max(0, pageable.getPageNumber());
        int totalPages = Math.max(
                1,
                (int) Math.ceil((double) totalElements / pageSize));

        pageNumber = Math.min(pageNumber, totalPages - 1);

        int fromIndex = pageNumber * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, totalElements);

        List<MonthlyRecordRow> pagedContent = new ArrayList<>();

        if (fromIndex < totalElements) {
            List<MonthlyRecordRow> sub =
                    filtered.subList(fromIndex, toIndex);

            for (int i = 0; i < sub.size(); i++) {
                MonthlyRecordRow row = sub.get(i);

                pagedContent.add(new MonthlyRecordRow(
                        fromIndex + i + 1,
                        row.contributionId(),
                        row.employeeId(),
                        row.employeeFullName(),
                        row.designation(),
                        row.oldShares(),
                        row.newShares(),
                        row.totalShares(),
                        row.shareValue(),
                        row.monthlyDue(),
                        row.cashReceived(),
                        row.memberCreditApplied(),
                        row.totalPaid(),
                        row.outstanding(),
                        row.status(),
                        row.remarks()));
            }
        }

        Pageable effectivePageable =
                org.springframework.data.domain.PageRequest.of(
                        pageNumber, pageSize, pageable.getSort());

        return new PageImpl<>(
                pagedContent,
                effectivePageable,
                totalElements);
    }

    // =====================================================
    // [SINGLE-MONTH-TOTALS] 11. MONTHLY FINANCIAL SUMMARY — NOT YET USED BY WORKSPACE SINGLE VIEW
    // =====================================================

    /**
     * Candidate service-layer single-month financial totals.
     * Currently the Admin Single Month page uses the controller's own
     * SingleMonthSummary created in processSingleMonthRecords(...).
     */
    @Transactional(readOnly = true)
    public MonthlyFinancialSummary getMonthlyFinancialSummary(
            int year,
            int month) {

        Window period = window(year, month, year, month);

        List<Contribution> dues = contributionRepo.findPeriod(
                period.start(), period.end());

        Map<Long, PaymentAggregate> payments = paymentsByContribution(dues);

        BigDecimal totalMonthDue = zero();
        BigDecimal totalMonthCash = zero();
        BigDecimal totalMonthCredit = zero();
        BigDecimal totalMonthSettled = zero();
        BigDecimal totalMonthRemaining = zero();

        long paidCount = 0;
        long pendingCount = 0;
        long canceledCount = 0;

        for (Contribution due : dues) {
            boolean canceled = is(
                    due, Contribution.ContributionStatus.CANCELED);

            PaymentAggregate aggregate = payments.getOrDefault(
                    due.getId(),
                    new PaymentAggregate(zero(), 0, 0));

            BigDecimal cash = amountOrZero(aggregate.collected());
            BigDecimal credit = amountOrZero(
                    memberCreditService.getAppliedTotal(due.getId()));

            if (cash.signum() < 0 || credit.signum() < 0) {
                throw new IllegalStateException(
                        "Negative settlement detected. Contribution ID: "
                                + due.getId());
            }

            if (canceled) {
                if (cash.signum() != 0 || credit.signum() != 0) {
                    throw new IllegalStateException(
                            "Canceled contribution has active settlement: "
                                    + due.getId());
                }
                canceledCount++;
                continue;
            }

            BigDecimal dueAmount = amountOrZero(
                    due.getContributionAmount());
            BigDecimal settled = cash.add(credit);
            BigDecimal remaining = dueAmount.subtract(settled);

            if (remaining.signum() < 0) {
                throw new IllegalStateException(
                        "Contribution over-settlement detected. ID: "
                                + due.getId());
            }

            totalMonthDue = totalMonthDue.add(dueAmount);
            totalMonthCash = totalMonthCash.add(cash);
            totalMonthCredit = totalMonthCredit.add(credit);
            totalMonthSettled = totalMonthSettled.add(settled);
            totalMonthRemaining = totalMonthRemaining.add(remaining);

            if (remaining.signum() == 0) {
                paidCount++;
            } else {
                pendingCount++;
            }
        }

        return new MonthlyFinancialSummary(
                dues.size(),
                totalMonthDue,
                totalMonthCash,
                totalMonthCredit,
                totalMonthSettled,
                totalMonthRemaining,
                paidCount,
                pendingCount,
                canceledCount);
    }




    // =====================================================
    // [MONTHLY-REGISTER] 12. MONTHLY CONTRIBUTION REGISTER — USED BY CONTROLLER
    // =====================================================

    public record MonthlyRegisterRow(
            int slNo,
            String employeeId,
            String employeeName,
            String designation,
            int oldShares,
            int newShares,
            Map<YearMonth, BigDecimal> monthlySettled,
            Map<YearMonth, String> monthlyStatuses,
            BigDecimal totalSettled,
            BigDecimal totalDue,
            BigDecimal remainingDue
    ) {}

    /**
     * ACTIVE route for Monthly Records > Monthly Contribution Register.
     * Called by AdminContributionWorkspaceController.processMonthlyRegister().
     */
    @Transactional(readOnly = true)
    public List<MonthlyRegisterRow> getMonthlyContributionRegister(
            int fromYear,
            int fromMonth,
            int toYear,
            int toMonth,
            String search) {

        Window period = window(fromYear, fromMonth, toYear, toMonth);

        // Generate all calendar months in the selected range.
        List<YearMonth> months = new ArrayList<>();
        YearMonth first = YearMonth.from(period.start());
        YearMonth last = YearMonth.from(period.end());

        for (YearMonth month = first;
             !month.isAfter(last);
             month = month.plusMonths(1)) {
            months.add(month);
        }

        // Fetch all contribution records in the selected range.
        List<Contribution> dues = contributionRepo.findPeriod(
                period.start(),
                period.end());

        Map<Long, PaymentAggregate> payments =
                paymentsByContribution(dues);

        // Current share holdings grouped by employee ID.
        Map<String, List<ShareHolding>> holdingsByEmployee =
                sharesRepo.findAllWithEmployee()
                        .stream()
                        .filter(h -> h.getEmployee() != null)
                        .collect(Collectors.groupingBy(
                                h -> h.getEmployee().getEmployeeId()));

        // Group contribution records by employee ID and month.
        Map<String, Map<YearMonth, Contribution>> duesByEmployee =
                new HashMap<>();

        for (Contribution due : dues) {
            Employee employee = due.getEmployee();

            if (employee == null || employee.getEmployeeId() == null) {
                continue;
            }

            YearMonth contributionMonth =
                    YearMonth.from(due.getContributionMonth());

            duesByEmployee
                    .computeIfAbsent(
                            employee.getEmployeeId(),
                            key -> new HashMap<>())
                    .put(contributionMonth, due);
        }

        String query = search == null
                ? ""
                : search.trim().toLowerCase(Locale.ROOT);

        List<Employee> employees = employeeRepo.findAll()
                .stream()
                .filter(e -> e.getEmployeeId() != null)

                // Only employees having contribution records
                // within the selected reporting period.
                .filter(e -> duesByEmployee.containsKey(e.getEmployeeId()))

                // Search by Employee ID or Full Name.
                .filter(e -> query.isEmpty()
                        || e.getEmployeeId()
                        .toLowerCase(Locale.ROOT)
                        .contains(query)
                        || (e.getFullName() != null
                        && e.getFullName()
                        .toLowerCase(Locale.ROOT)
                        .contains(query)))

                // Sort by business Employee ID.
                .sorted(Comparator.comparing(
                        Employee::getEmployeeId,
                        String.CASE_INSENSITIVE_ORDER))

                .toList();

        List<MonthlyRegisterRow> rows = new ArrayList<>();
        int slNo = 0;

        for (Employee employee : employees) {
            String employeeId = employee.getEmployeeId();

            List<ShareHolding> holdings =
                    holdingsByEmployee.getOrDefault(
                            employeeId, List.of());

            int oldShares = 0;
            int newShares = 0;

            // Historical share quantities as of the selected
// reporting period's ending date.
            LocalDate reportingDate = period.end();

            for (ShareHolding holding : holdings) {

                int historicalQuantity = shareTransactionRepo
                        .findTopByShareHoldingIdAndTransactionTypeInAndEffectiveDateLessThanEqualOrderByEffectiveDateDescIdDesc(
                                holding.getId(), QUANTITY_TRANSACTION_TYPES,
                                reportingDate
                        )
                        .map(ShareTransaction::getQuantityAfter)
                        .orElse(0);

                if (holding.getShareCategory() == ShareHolding.ShareCategory.OLD) {
                    oldShares += historicalQuantity;

                } else if (holding.getShareCategory() == ShareHolding.ShareCategory.NEW) {
                    newShares += historicalQuantity;
                }
            }

            Map<YearMonth, Contribution> employeeDues =
                    duesByEmployee.getOrDefault(
                            employeeId, Map.of());

            Map<YearMonth, BigDecimal> monthlySettled =
                    new LinkedHashMap<>();

            Map<YearMonth, String> monthlyStatuses =
                    new LinkedHashMap<>();

            BigDecimal totalSettled = zero();
            BigDecimal totalDue = zero();
            BigDecimal remainingDue = zero();

            for (YearMonth month : months) {
                Contribution due = employeeDues.get(month);

                if (due == null) {
                    // No contribution record exists for this month.
                    monthlySettled.put(month, zero());
                    monthlyStatuses.put(month, "NO_RECORD");
                    continue;
                }

                ContributionStatementLine statementLine =
                        line(due, payments);

                monthlySettled.put(
                        month, statementLine.totalSettled());

                if (is(due, Contribution.ContributionStatus.CANCELED)) {
                    monthlyStatuses.put(month, "CANCELED");
                    continue;
                }

                BigDecimal expected = statementLine.expected();
                BigDecimal settled = statementLine.totalSettled();
                BigDecimal outstanding = statementLine.outstanding();

                totalDue = totalDue.add(expected);
                totalSettled = totalSettled.add(settled);
                remainingDue = remainingDue.add(outstanding);

                if (outstanding.signum() == 0) {
                    monthlyStatuses.put(month, "SETTLED");
                } else if (settled.signum() > 0) {
                    monthlyStatuses.put(month, "PARTIAL");
                } else {
                    monthlyStatuses.put(month, "UNPAID");
                }
            }

            rows.add(new MonthlyRegisterRow(
                    ++slNo,
                    employeeId,
                    employee.getFullName(),
                    employee.getEmployeeDesignation() == null
                            ? ""
                            : employee.getEmployeeDesignation().name(),
                    oldShares,
                    newShares,
                    Collections.unmodifiableMap(monthlySettled),
                    Collections.unmodifiableMap(monthlyStatuses),
                    totalSettled,
                    totalDue,
                    remainingDue
            ));
        }

        return List.copyOf(rows);
    }


    // [REPORTING-ANNUAL] Calendar-year contribution-period reports, current settlements.
    public record AnnualRow(String employeeId, String fullName, String designation,
            String employeeStatus, BigDecimal expected, BigDecimal collected,
            BigDecimal creditApplied, BigDecimal outstanding, long obligations) {
        public BigDecimal totalSettled() { return collected.add(creditApplied); }
        public BigDecimal rate() { return percentage(collected, expected); }
        public String status() {
            if (obligations == 0) return "NO_RECORDS";
            if (outstanding.signum() == 0) return "PAID";
            return totalSettled().signum() > 0 ? "PARTIAL" : "PENDING";
        }
    }
    public record ReportMonth(LocalDate month, BigDecimal expected, BigDecimal collected,
            BigDecimal creditApplied, BigDecimal outstanding, String status) {}
    public record AnnualReport(Page<AnnualRow> page, List<AnnualRow> allRows,
            long activeContributors, BigDecimal expected, BigDecimal collected,
            BigDecimal creditApplied, BigDecimal outstanding, BigDecimal rate,
            List<ReportMonth> months) {
        public BigDecimal chartMax() { return months.stream().map(m -> m.expected().max(m.collected()))
                .reduce(BigDecimal.ONE, BigDecimal::max); }
    }
    public record StatementReport(Employee employee, MemberContributionStatementResponse summary,
            List<ReportMonth> months, List<StatementPayment> payments,
            List<StatementCreditAllocation> allocations, String generatedOn) {
        public long paymentCount() { return payments.stream().filter(p -> !p.reversed()).count(); }
        public long reversalCount() { return payments.stream().filter(StatementPayment::reversed).count(); }
        public long paidContributionMonths() { return months.stream().filter(m -> "PAID".equals(m.status())).count(); }
        public long pendingCount() { return months.stream().filter(m -> "PENDING".equals(m.status()) || "PARTIALLY PAID".equals(m.status())).count(); }
    }
    public record StatementPayment(LocalDate contributionMonth, LocalDate paymentDate, Long paymentId,
            String reference, String method, BigDecimal amount, boolean reversed, LocalDate reversalDate, String reversalReason) {
        public String displayMethod() {
            return Arrays.stream(method.toLowerCase(Locale.ROOT).split("_"))
                    .filter(part -> !part.isBlank()).map(part -> Character.toUpperCase(part.charAt(0))+part.substring(1))
                    .collect(Collectors.joining(" "));
        }
    }
    public record StatementCreditAllocation(String reference, LocalDate contributionMonth, BigDecimal amount,
            LocalDate appliedDate, boolean reversed, LocalDate reversalDate, String reversalReason) {}
    public record AnnualDetail(String employeeId, String fullName, ReportMonth month) {}

    public static BigDecimal percentage(BigDecimal received, BigDecimal expected) {
        return expected.signum() == 0 ? BigDecimal.ZERO.setScale(2)
                : received.multiply(BigDecimal.valueOf(100)).divide(expected, 2, java.math.RoundingMode.HALF_UP);
    }
    private String pattern(String input) {
        if (input == null || input.isBlank()) return "";
        if (input.length() > 100) throw new IllegalArgumentException("Search value is too long.");
        return "%" + input.trim().toLowerCase(Locale.ROOT).replace("!", "!!")
                .replace("%", "!%").replace("_", "!_").replace("[", "![") + "%";
    }
    private AnnualRow annualRow(ContributionReportingRepository.AnnualTotal r) {
        if (r.getAnomalies() > 0) throw new IllegalStateException("Settlement reconciliation required.");
        return new AnnualRow(r.getEmployeeId(), r.getFullName(), r.getDesignation(),
                r.getEmployeeStatus(), r.getExpected(), r.getCollected(), r.getCreditApplied(),
                r.getOutstanding(), r.getObligations());
    }
    private void validateAnnual(String status, int page, int size, String sortBy, String sortDir) {
        if (!Set.of("ALL","PAID","PENDING","PARTIAL","NO_RECORDS").contains(status))
            throw new IllegalArgumentException("Invalid annual status.");
        if (page < 0 || !Set.of(10,25,50,100).contains(size))
            throw new IllegalArgumentException("Invalid page or page size.");
        if (!Set.of("employeeId","fullName","expected","collected","outstanding").contains(sortBy)
                || !Set.of("asc","desc").contains(sortDir))
            throw new IllegalArgumentException("Invalid annual sorting.");
    }
    @Transactional(readOnly=true)
    public AnnualReport annual(int year, String employeeId, String employeeName, String status,
            int page, int size, String sortBy, String sortDir) {
        Window w = window(year,1,year,12);
        validateAnnual(status,page,size,sortBy,sortDir);
        String id = pattern(employeeId), name = pattern(employeeName);
        var sort = org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.fromString(sortDir), sortBy);
        if (!"employeeId".equals(sortBy)) sort = sort.and(org.springframework.data.domain.Sort.by("employeeId"));
        Page<AnnualRow> rows = contributionRepo.annualPage(w.start(),w.end(),id,name,status,
                org.springframework.data.domain.PageRequest.of(page,size,sort)).map(this::annualRow);
        if (page > 0 && page >= rows.getTotalPages()) throw new IllegalArgumentException("Page is outside the report.");
        List<AnnualRow> all = contributionRepo.annualTotals(w.start(),w.end(),id,name,status).stream()
                .map(this::annualRow).sorted(Comparator.comparing(AnnualRow::employeeId,String.CASE_INSENSITIVE_ORDER)).toList();
        BigDecimal expected=zero(), cash=zero(), credit=zero(), outstanding=zero();
        for (AnnualRow row : all) {
            expected=expected.add(row.expected()); cash=cash.add(row.collected());
            credit=credit.add(row.creditApplied()); outstanding=outstanding.add(row.outstanding());
        }
        Map<LocalDate, Object[]> monthly = new HashMap<>();
        for (Object[] values : contributionRepo.annualMonths(w.start(),w.end(),id,name,status)) {
            LocalDate date = values[0] instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) values[0];
            monthly.put(date, values);
        }
        List<ReportMonth> months = new ArrayList<>();
        for (int m=1;m<=12;m++) {
            LocalDate date=LocalDate.of(year,m,1); Object[] v=monthly.get(date);
            months.add(new ReportMonth(date, v==null?zero():(BigDecimal)v[1], v==null?zero():(BigDecimal)v[2],
                    v==null?zero():(BigDecimal)v[3],v==null?zero():(BigDecimal)v[4],v==null?"NO RECORD":"RECORDED"));
        }
        return new AnnualReport(rows,all,all.stream().filter(r -> "ACTIVE".equals(r.employeeStatus()) && r.obligations()>0).count(),
                expected,cash,credit,outstanding,percentage(cash,expected),List.copyOf(months));
    }
    public LocalDate[] statementPeriod(String type, int year, LocalDate start, LocalDate end) {
        return statementPeriod(type, year, 1, start, end);
    }
    public LocalDate[] statementPeriod(String type, int year, int month, LocalDate start, LocalDate end) {
        if ("ANNUAL".equals(type)) { window(year,1,year,12); return new LocalDate[]{LocalDate.of(year,1,1),LocalDate.of(year,12,31)}; }
        if ("MONTHLY".equals(type)) { window(year,month,year,month); return new LocalDate[]{LocalDate.of(year,month,1),YearMonth.of(year,month).atEndOfMonth()}; }
        if (!"CUSTOM".equals(type)) throw new IllegalArgumentException("Invalid statement type.");
        if (start==null || end==null || start.isAfter(end)) throw new IllegalArgumentException("Enter a valid start and end date.");
        // Obligations are month-keyed. Accept only complete calendar months; do not silently widen dates.
        if (start.getDayOfMonth()!=1 || !end.equals(YearMonth.from(end).atEndOfMonth()))
            throw new IllegalArgumentException("Choose the first day of the starting month and last day of the ending month.");
        window(start.getYear(),start.getMonthValue(),end.getYear(),end.getMonthValue());
        return new LocalDate[]{start,end};
    }
    @Transactional(readOnly=true)
    public StatementReport statement(String employeeId, String type, int year, LocalDate start, LocalDate end) {
        return statement(employeeId,type,year,1,start,end);
    }
    @Transactional(readOnly=true)
    public StatementReport statement(String employeeId, String type, int year, int month, LocalDate start, LocalDate end) {
        LocalDate[] dates=statementPeriod(type,year,month,start,end);
        if (employeeId==null || employeeId.isBlank() || employeeId.length()>50)
            throw new IllegalArgumentException("Enter a valid business Employee ID.");
        String normalizedId=employeeId.trim().matches("\\d+")?"EMP-"+employeeId.trim():employeeId.trim();
        Employee employee=employeeRepo.findByEmployeeId(normalizedId)
                .orElseThrow(() -> new EmployeeNotFoundException("Employee not found."));
        var summary=member(employee.getEmployeeId(), dates[0].getYear(),dates[0].getMonthValue(),dates[1].getYear(),dates[1].getMonthValue());
        Map<LocalDate,ContributionStatementLine> recorded=summary.contributions().stream()
                .collect(Collectors.toMap(ContributionStatementLine::month, l -> l));
        List<ReportMonth> months=new ArrayList<>();
        for (LocalDate d=dates[0]; !d.isAfter(dates[1]); d=d.plusMonths(1)) {
            var l=recorded.get(d);
            boolean beforeJoin=employee.getFundJoinDate()!=null && YearMonth.from(d).atEndOfMonth().isBefore(employee.getFundJoinDate());
            boolean afterExit=employee.getFundExitDate()!=null && employee.getFundExitDate().isBefore(d);
            String status=l==null?(beforeJoin?"NOT YET JOINED":afterExit?"AFTER EXIT":"NOT CREATED"):l.status()==Contribution.ContributionStatus.CANCELED?"CANCELED":
                    l.outstanding().signum()==0?"PAID":l.totalSettled().signum()>0?"PARTIALLY PAID":"PENDING";
            months.add(new ReportMonth(d,l==null?zero():l.expected(),l==null?zero():l.collected(),
                    l==null?zero():l.creditApplied(),l==null?zero():l.outstanding(),status));
        }
        List<StatementPayment> payments=new ArrayList<>();
        List<Long> ids=summary.contributions().stream().map(ContributionStatementLine::contributionId).toList();
        for (int offset=0;offset<ids.size();offset+=500) {
            for (var p:paymentRepo.findPaymentsForContributions(ids.subList(offset,Math.min(offset+500,ids.size())))) {
                payments.add(new StatementPayment(p.getContribution().getContributionMonth(),p.getPaymentDate(),
                        p.getId(),p.getReferenceNo(),p.getPaymentMethod().name(),p.getPaymentAmount(),p.isReversed(),p.getReversalDate(),p.getReversalReason()));
            }
        }
        payments.sort(Comparator.comparing(StatementPayment::paymentDate).thenComparing(StatementPayment::contributionMonth)
                .thenComparing(StatementPayment::paymentId));
        List<StatementCreditAllocation> allocations=List.of();
        if (!ids.isEmpty()) {
            allocations=entityManager.createQuery("select a from MemberCreditAllocation a join fetch a.credit join fetch a.contribution "
                    + "where a.contribution.id in :ids order by a.appliedDate, a.id",MemberCreditAllocation.class)
                    .setParameter("ids",ids).getResultList().stream()
                    .map(a -> new StatementCreditAllocation(a.getCredit().getReferenceNo(),a.getContribution().getContributionMonth(),
                            a.getAmount(),a.getAppliedDate(),a.isReversed(),a.getReversalDate(),a.getReversalReason())).toList();
        }
        return new StatementReport(employee,summary,List.copyOf(months),List.copyOf(payments),List.copyOf(allocations),
                java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Dhaka")).format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm z")));
    }
    @Transactional(readOnly=true)
    public List<AnnualDetail> annualDetails(int year, String employeeId, String employeeName, String status) {
        Window w=window(year,1,year,12); validateAnnual(status,0,10,"employeeId","asc");
        String id=pattern(employeeId),name=pattern(employeeName);
        var eligible=contributionRepo.annualTotals(w.start(),w.end(),id,name,status).stream().map(this::annualRow)
                .collect(Collectors.toMap(AnnualRow::employeeId,r -> r));
        // Bounded to one reporting year. No all-time payment or employee history is loaded.
        var dues=contributionRepo.findFilteredPeriod(w.start(),w.end(),id,name).stream()
                .filter(c -> eligible.containsKey(c.getEmployee().getEmployeeId())).toList();
        var paid=paymentsByContribution(dues);
        var credits=memberCreditService.getAppliedTotals(dues.stream().map(Contribution::getId).toList());
        Map<String,Map<LocalDate,ContributionStatementLine>> lines=new HashMap<>();
        for (var c:dues) lines.computeIfAbsent(c.getEmployee().getEmployeeId(),k -> new HashMap<>())
                .put(c.getContributionMonth(),line(c,paid,credits.get(c.getId())));
        List<AnnualDetail> result=new ArrayList<>();
        for (var r:eligible.values().stream().sorted(Comparator.comparing(AnnualRow::employeeId)).toList()) {
            for (int m=1;m<=12;m++) {
                LocalDate date=LocalDate.of(year,m,1); var l=lines.getOrDefault(r.employeeId(),Map.of()).get(date);
                result.add(new AnnualDetail(r.employeeId(),r.fullName(),new ReportMonth(date,
                    l==null?zero():l.expected(),l==null?zero():l.collected(),l==null?zero():l.creditApplied(),
                    l==null?zero():l.outstanding(),l==null?"NO RECORD":l.status()==Contribution.ContributionStatus.CANCELED?"CANCELED":
                    l.outstanding().signum()==0?"PAID":l.totalSettled().signum()>0?"PARTIALLY PAID":"PENDING")));
            }
        }
        return result;
    }
}
