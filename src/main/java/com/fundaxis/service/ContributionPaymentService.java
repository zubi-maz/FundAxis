
package com.fundaxis.service;

import com.fundaxis.dto.*;
import com.fundaxis.entity.Contribution;
import com.fundaxis.entity.ContributionPayment;
import com.fundaxis.entity.Employee;
import com.fundaxis.exception.ContributionNotFoundException;
import com.fundaxis.repository.ContributionPaymentLockRepository;
import com.fundaxis.repository.ContributionPaymentRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Service
public class ContributionPaymentService {

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    private static final ZoneId BANK_ZONE =
            ZoneId.of("Asia/Dhaka");

    private final ContributionPaymentRepository paymentRepository;
    private final ContributionPaymentLockRepository contributionRepository;
    private final MemberCreditService memberCreditService;

    public ContributionPaymentService(
            ContributionPaymentRepository paymentRepository,
            ContributionPaymentLockRepository contributionRepository,
            MemberCreditService memberCreditService) {

        this.paymentRepository = paymentRepository;
        this.contributionRepository = contributionRepository;
        this.memberCreditService = memberCreditService;
    }

    // =====================================================
    // 1. RECEIVE REGULAR CONTRIBUTION PAYMENT
    // =====================================================

    @Transactional
    public ContributionPaymentSummaryResponse receive(
            Long contributionId,
            RecordContributionPaymentRequest request) {

        if (request == null) {
            throw new IllegalArgumentException(
                    "Payment request is required");
        }

        // Lock contribution before modifying its settlement.
        Contribution due = lockContribution(contributionId);
        validateMembershipPeriod(due);

        if (due.getContributionStatus()
                != Contribution.ContributionStatus.PENDING) {

            throw new IllegalStateException(
                    "Only PENDING contributions accept payments");
        }

        validatePaymentRequest(request);

        // Outstanding = Expected - Cash - Applied Credit.
        BigDecimal outstanding = due.getContributionAmount()
                .subtract(settledTotal(contributionId));

        if (outstanding.signum() <= 0) {
            throw new IllegalStateException(
                    "Contribution has no outstanding balance");
        }

        if (request.amount().compareTo(outstanding) > 0) {
            throw new IllegalArgumentException(
                    "Payment exceeds outstanding amount: "
                            + outstanding);
        }

        ContributionPayment payment = new ContributionPayment();

        payment.setContribution(due);
        payment.setPaymentAmount(request.amount());
        payment.setPaymentDate(request.paymentDate());
        payment.setPaymentMethod(request.paymentMethod());
        payment.setReferenceNo(clean(request.referenceNo()));
        payment.setNotes(clean(request.notes()));

        paymentRepository.saveAndFlush(payment);

        // Recalculate settlement after payment posting.
        recalculateSettlement(due);

        contributionRepository.save(due);

        return summary(due);
    }

    // =====================================================
    // 2. LEGACY FULL SETTLEMENT
    // =====================================================

    // Preserves existing /pay endpoint compatibility.
    // Only the outstanding remainder is recorded as cash.

    @Transactional
    public Contribution payRemainingLegacy(
            Long contributionId,
            LocalDate paymentDate) {

        Contribution due = lockContribution(contributionId);
        validateMembershipPeriod(due);

        if (due.getContributionStatus()
                != Contribution.ContributionStatus.PENDING) {

            throw new IllegalStateException(
                    "Only PENDING contributions can be marked PAID");
        }

        validateDate(paymentDate, "Payment date");

        BigDecimal remainder = due.getContributionAmount()
                .subtract(settledTotal(contributionId));

        if (remainder.signum() <= 0) {
            throw new IllegalStateException(
                    "Contribution has no outstanding balance");
        }

        ContributionPayment payment = new ContributionPayment();

        payment.setContribution(due);
        payment.setPaymentAmount(remainder);
        payment.setPaymentDate(paymentDate);
        payment.setPaymentMethod(
                ContributionPayment.PaymentMethod.UNSPECIFIED);
        payment.setNotes(
                "Recorded by legacy Mark Paid endpoint");

        paymentRepository.saveAndFlush(payment);

        recalculateSettlement(due);

        return contributionRepository.save(due);
    }

    // =====================================================
    // 3. REVERSE REGULAR CONTRIBUTION PAYMENT
    // =====================================================

    @Transactional
    public ContributionPaymentSummaryResponse reverse(
            Long contributionId,
            Long paymentId,
            ContributionPaymentReversalRequest request) {

        if (request == null) {
            throw new IllegalArgumentException(
                    "Reversal request is required");
        }

        Contribution due = lockContribution(contributionId);

        validateDate(request.reversalDate(), "Reversal date");

        if (request.reason() == null
                || request.reason().isBlank()) {

            throw new IllegalArgumentException(
                    "Reversal reason is required");
        }

        if (request.reason().length() > 500) {
            throw new IllegalArgumentException(
                    "Reversal reason must be at most 500 characters");
        }

        ContributionPayment payment = paymentRepository
                .findById(paymentId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Payment not found: " + paymentId));

        if (!payment.getContribution()
                .getId()
                .equals(contributionId)) {

            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Payment not found under contribution");
        }

        if (payment.isReversed()) {
            throw new IllegalStateException(
                    "Payment already reversed");
        }

        if (request.reversalDate()
                .isBefore(payment.getPaymentDate())) {

            throw new IllegalArgumentException(
                    "Reversal date cannot precede payment date");
        }

        if (due.getContributionStatus()
                == Contribution.ContributionStatus.CANCELED) {

            throw new IllegalStateException(
                    "Cannot reverse payment of canceled contribution");
        }

        // Preserve original payment for audit.
        payment.setReversed(true);
        payment.setReversalDate(request.reversalDate());
        payment.setReversalReason(request.reason().trim());

        paymentRepository.saveAndFlush(payment);

        // Other active payments and credit allocations remain.
        recalculateSettlement(due);

        contributionRepository.save(due);

        return summary(due);
    }

    // =====================================================
    // 4. PAYMENT SUMMARY
    // =====================================================

    @Transactional(readOnly = true)
    public ContributionPaymentSummaryResponse getSummary(
            Long contributionId) {

        Contribution due = contributionRepository
                .findById(contributionId)
                .orElseThrow(() ->
                        new ContributionNotFoundException(
                                "Contribution not found: "
                                        + contributionId));

        return summary(due);
    }

    // =====================================================
    // 5. ACTIVE CASH PAYMENT CHECK
    // =====================================================

    @Transactional(readOnly = true)
    public boolean hasActivePayments(Long contributionId) {

        return paymentRepository
                .existsByContributionIdAndReversedFalse(
                        contributionId);
    }

    // =====================================================
    // 6. BUILD SUMMARY: CASH AND CREDIT SEPARATE
    // =====================================================

    private ContributionPaymentSummaryResponse summary(
            Contribution due) {

        Long contributionId = due.getId();

        List<ContributionPaymentResponse> ledger =
                paymentRepository
                        .findByContributionIdOrderByIdAsc(
                                contributionId)
                        .stream()
                        .map(ContributionPaymentResponse::from)
                        .toList();

        // Only actual direct contribution cash payments.
        BigDecimal cashCollected = activeTotal(contributionId);

        // Previously collected money applied as internal credit.
        // This is not a new cash receipt.
        BigDecimal appliedCredit =
                memberCreditService.getAppliedTotal(
                        contributionId);

        BigDecimal outstanding = due.getContributionAmount()
                .subtract(cashCollected)
                .subtract(appliedCredit);

        if (outstanding.signum() < 0) {
            throw new IllegalStateException(
                    "Contribution settlement exceeds expected amount");
        }

        // Preserve the existing DTO constructor.
        // collectedAmount remains direct contribution cash.
        return new ContributionPaymentSummaryResponse(
                contributionId,
                due.getEmployee().getEmployeeId(),
                due.getContributionMonth(),
                due.getContributionAmount(),
                cashCollected,
                outstanding,
                due.getContributionStatus(),
                ledger
        );
    }

    // =====================================================
    // 7. RECALCULATE CONTRIBUTION SETTLEMENT
    // =====================================================

    // Called only inside transactional mutation methods
    // after the contribution row has been locked.

    private void recalculateSettlement(Contribution due) {

        Long contributionId = due.getId();

        BigDecimal settled = settledTotal(contributionId);

        if (settled.compareTo(due.getContributionAmount()) > 0) {
            throw new IllegalStateException(
                    "Settlement exceeds expected contribution amount");
        }

        if (settled.signum() < 0) {
            throw new IllegalStateException(
                    "Settlement cannot be negative");
        }

        boolean fullyPaid =
                settled.compareTo(
                        due.getContributionAmount()) == 0;

        if (fullyPaid) {

            due.setContributionStatus(
                    Contribution.ContributionStatus.PAID);

            due.setPaymentDate(
                    latestSettlementDate(contributionId));

        } else {

            due.setContributionStatus(
                    Contribution.ContributionStatus.PENDING);

            due.setPaymentDate(null);
        }
    }

    // =====================================================
    // 8. CONTRIBUTION ROW LOCK
    // =====================================================

    private Contribution lockContribution(Long contributionId) {

        if (contributionId == null) {
            throw new IllegalArgumentException(
                    "Contribution ID is required");
        }

        Contribution contribution = contributionRepository
                .lockById(contributionId)
                .orElseThrow(() ->
                        new ContributionNotFoundException(
                                "Contribution not found: "
                                          + contributionId));
        // The MVC context may have loaded this entity before another transaction canceled it.
        // A lock query can return that managed instance without replacing its cached fields.
        entityManager.refresh(contribution, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        return contribution;
    }

    // =====================================================
    // 9. ACTIVE DIRECT CASH PAYMENTS
    // =====================================================

    private BigDecimal activeTotal(Long contributionId) {

        BigDecimal total =
                paymentRepository.sumActivePayments(contributionId);

        return total == null
                ? BigDecimal.ZERO
                : total;
    }

    // =====================================================
    // 10. TOTAL CONTRIBUTION SETTLEMENT
    // =====================================================

    // Cash collected directly for the due
    // + previously collected credits applied to this due.

    private BigDecimal settledTotal(Long contributionId) {

        return activeTotal(contributionId)
                .add(
                        memberCreditService.getAppliedTotal(
                                contributionId)
                );
    }

    // =====================================================
    // 11. LATEST ACTIVE SETTLEMENT DATE
    // =====================================================

    private LocalDate latestSettlementDate(
            Long contributionId) {

        LocalDate latestCashDate = paymentRepository
                .findByContributionIdOrderByIdAsc(contributionId)
                .stream()
                .filter(p -> !p.isReversed())
                .map(ContributionPayment::getPaymentDate)
                .filter(date -> date != null)
                .max(LocalDate::compareTo)
                .orElse(null);

        LocalDate latestCreditDate =
                memberCreditService.getLatestAppliedDate(
                        contributionId);

        if (latestCashDate == null) {
            return latestCreditDate;
        }

        if (latestCreditDate == null) {
            return latestCashDate;
        }

        return latestCashDate.isAfter(latestCreditDate)
                ? latestCashDate
                : latestCreditDate;
    }

    // =====================================================
    // 12. VALIDATE PAYMENT REQUEST
    // =====================================================

    private void validatePaymentRequest(
            RecordContributionPaymentRequest request) {

        validateAmount(request.amount());

        if (request.paymentMethod() == null) {
            throw new IllegalArgumentException(
                    "Payment method is required");
        }

        if (request.referenceNo() != null
                && request.referenceNo().length() > 120) {

            throw new IllegalArgumentException(
                    "Payment reference too long");
        }

        if (request.notes() != null
                && request.notes().length() > 500) {

            throw new IllegalArgumentException(
                    "Notes too long");
        }

        validateDate(request.paymentDate(), "Payment date");
    }

    /**
     * Preserve payment access to valid historical obligations for former
     * members, but do not accept cash for a contribution period wholly outside
     * the employee's fund membership. The period-boundary rule matches
     * ShareService's contribution eligibility calculation.
     */
    private void validateMembershipPeriod(Contribution due) {
        Employee employee = due.getEmployee();
        LocalDate monthStart = due.getContributionMonth().withDayOfMonth(1);
        LocalDate monthEnd = monthStart.with(java.time.temporal.TemporalAdjusters.lastDayOfMonth());

        if ((employee.getFundJoinDate() != null && monthEnd.isBefore(employee.getFundJoinDate()))
                || (employee.getFundExitDate() != null && monthStart.isAfter(employee.getFundExitDate()))) {
            throw new IllegalStateException(
                    "This contribution period is outside the employee's fund membership");
        }
    }

    // =====================================================
    // 13. VALIDATE AMOUNT
    // =====================================================

    private void validateAmount(BigDecimal amount) {

        if (amount == null
                || amount.signum() <= 0
                || amount.scale() > 2
                || amount.precision() - amount.scale() > 18) {

            throw new IllegalArgumentException(
                    "Payment amount must be positive "
                            + "and fit DECIMAL(20,2)");
        }
    }

    // =====================================================
    // 14. VALIDATE DATE
    // =====================================================

    private void validateDate(LocalDate date, String label) {

        if (date == null) {
            throw new IllegalArgumentException(
                    label + " is required");
        }

        if (date.isAfter(LocalDate.now(BANK_ZONE))) {
            throw new IllegalArgumentException(
                    label + " cannot be in future");
        }
    }

    // =====================================================
    // 15. CLEAN OPTIONAL TEXT
    // =====================================================

    private String clean(String input) {

        return input == null || input.isBlank()
                ? null
                : input.trim();
    }
}
