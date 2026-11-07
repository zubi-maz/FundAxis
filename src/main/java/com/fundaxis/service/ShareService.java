
package com.fundaxis.service;

import com.fundaxis.dto.MonthlyShareContributionResponse;
import com.fundaxis.entity.Employee;
import com.fundaxis.entity.ShareHolding;
import com.fundaxis.entity.ShareRateHistory;
import com.fundaxis.entity.ShareTransaction;
import com.fundaxis.repository.EmployeeRepository;
import com.fundaxis.repository.ShareHoldingRepository;
import com.fundaxis.repository.ShareRateHistoryRepository;
import com.fundaxis.repository.ShareTransactionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

///========== Share Service ==========///
// Handles share allocation, quantity adjustments, rate changes,
// transaction history, rate history, and monthly contributions.

@Service
public class ShareService {

    private static final List<ShareTransaction.ShareTransactionType> QUANTITY_TRANSACTION_TYPES = List.of(
            ShareTransaction.ShareTransactionType.INITIAL_ALLOCATION,
            ShareTransaction.ShareTransactionType.INCREASE,
            ShareTransaction.ShareTransactionType.DECREASE,
            ShareTransaction.ShareTransactionType.WITHDRAWAL_POSTED);

    @org.springframework.beans.factory.annotation.Value("${fundaxis.withdrawals.enabled:false}")
    private boolean withdrawalsEnabled;
    private final com.fundaxis.repository.ShareWithdrawalRepository withdrawalRepository;
    private final EmployeeRepository employeeRepository;
    private final ShareHoldingRepository holdingRepository;
    private final ShareTransactionRepository transactionRepository;
    private final ShareRateHistoryRepository rateHistoryRepository;

    // Constructor injection keeps repository dependencies explicit.
    public ShareService(
            EmployeeRepository employeeRepository,
            ShareHoldingRepository holdingRepository,
            ShareTransactionRepository transactionRepository,
            ShareRateHistoryRepository rateHistoryRepository,
            com.fundaxis.repository.ShareWithdrawalRepository withdrawalRepository) {

        this.withdrawalRepository = withdrawalRepository;
        this.employeeRepository = employeeRepository;
        this.holdingRepository = holdingRepository;
        this.transactionRepository = transactionRepository;
        this.rateHistoryRepository = rateHistoryRepository;
    }

    ///========== Read Operations ==========///

    // Returns all share holdings belonging to the specified employee.
    @Transactional(readOnly = true)
    public List<ShareHolding> getEmployeeShares(String employeeId) {
        LocalDate asOf = LocalDate.now(java.time.ZoneId.of("Asia/Dhaka"));
        return holdingRepository.findByEmployeeEmployeeId(employeeId).stream()
                .peek(holding -> {
                    holding.setShareQuantity(quantityAsOf(holding, asOf));
                    holding.setMonthlyRatePerShare(rateAsOf(holding, asOf));
                    holding.setShareValuePerShare(shareValueAsOf(holding, asOf));
                })
                .toList();
    }

    /** Current effective quantity; future-dated allocations remain scheduled until their date. */
    @Transactional(readOnly = true)
    public int getCurrentShareQuantity(Long holdingId) {
        ShareHolding holding = getShareHolding(holdingId);
        return quantityAsOf(holding, LocalDate.now(java.time.ZoneId.of("Asia/Dhaka")));
    }

    private int quantityAsOf(ShareHolding holding, LocalDate asOf) {
        if (holding.getAllocationDate() != null && holding.getAllocationDate().isAfter(asOf)) return 0;
        List<ShareTransaction> transactions = transactionRepository
                .findByShareHoldingIdOrderByEffectiveDateAscIdAsc(holding.getId()).stream()
                .filter(t -> QUANTITY_TRANSACTION_TYPES.contains(t.getTransactionType())).toList();
        boolean hasInitialAllocation = transactions.stream()
                .anyMatch(t -> t.getTransactionType() == ShareTransaction.ShareTransactionType.INITIAL_ALLOCATION);
        long quantity = hasInitialAllocation ? 0 : holding.getShareQuantity()
                - transactions.stream().mapToLong(ShareTransaction::getQuantityChange).sum();
        boolean found = false;
        for (ShareTransaction transaction : transactions) {
            if (!transaction.getEffectiveDate().isAfter(asOf)) {
                quantity = Math.addExact(quantity, transaction.getQuantityChange());
                found = true;
            }
        }
        if (!found && transactions.isEmpty() && holding.getAllocationDate() != null
                && !holding.getAllocationDate().isAfter(asOf)) return holding.getShareQuantity();
        return Math.toIntExact(quantity);
    }

    private BigDecimal rateAsOf(ShareHolding holding, LocalDate asOf) {
        return rateHistoryRepository
                .findTopByShareHoldingIdAndEffectiveDateLessThanEqualOrderByEffectiveDateDescIdDesc(
                        holding.getId(), asOf)
                .map(ShareRateHistory::getMonthlyRatePerShare)
                .orElse(holding.getMonthlyRatePerShare());
    }

    /** Current or historical valuation from effective-dated allocation/valuation snapshots. */
    private BigDecimal shareValueAsOf(ShareHolding holding, LocalDate asOf) {
        List<ShareTransaction> valuationEvents = transactionRepository
                .findByShareHoldingIdOrderByEffectiveDateAscIdAsc(holding.getId()).stream()
                .filter(transaction -> transaction.getShareValueAfter() != null)
                .toList();

        ShareTransaction latestEffective = valuationEvents.stream()
                .filter(transaction -> !transaction.getEffectiveDate().isAfter(asOf))
                .max(java.util.Comparator.comparing(ShareTransaction::getEffectiveDate)
                        .thenComparing(ShareTransaction::getId))
                .orElse(null);
        if (latestEffective != null) return latestEffective.getShareValueAfter();

        // A future event carries the value immediately preceding it. This keeps
        // scheduled valuations from replacing today's value before their date.
        ShareTransaction nextScheduled = valuationEvents.stream()
                .filter(transaction -> transaction.getEffectiveDate().isAfter(asOf))
                .min(java.util.Comparator.comparing(ShareTransaction::getEffectiveDate)
                        .thenComparing(ShareTransaction::getId))
                .orElse(null);
        if (nextScheduled != null) return nextScheduled.getShareValueBefore();

        // Legacy holdings may not have any valuation snapshots yet.
        return holding.getShareValuePerShare();
    }

    /** Effective-dated holding valuation reference for read-only financial previews. */
    @Transactional(readOnly = true)
    public BigDecimal shareValuePerShareAsOf(Long holdingId, LocalDate asOf) {
        if (asOf == null) throw new IllegalArgumentException("Select a valuation date.");
        return shareValueAsOf(getShareHolding(holdingId), asOf);
    }


    // Read employee information for the Shares workspace.
    @Transactional(readOnly = true)
    public Employee getShareEmployee(String employeeId) {

        if (employeeId == null || employeeId.isBlank()) {
            throw new IllegalArgumentException(
                    "Employee ID is required."
            );
        }

        return employeeRepository
                .findByEmployeeId(employeeId.trim())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Employee not found: " + employeeId
                ));
    }

    // Retrieves one share holding or returns HTTP 404 if it does not exist.
    @Transactional(readOnly = true)
    public ShareHolding getShareHolding(Long holdingId) {
        return holdingRepository.findById(holdingId)
                .orElseThrow(() -> notFound(
                        "Share holding with ID " + holdingId + " was not found."));
    }

    // Returns the complete quantity transaction history in chronological order.
    @Transactional(readOnly = true)
    public List<ShareTransaction> getTransactionHistory(Long holdingId) {
        getShareHolding(holdingId);

        return transactionRepository
                .findByShareHoldingIdOrderByEffectiveDateAscIdAsc(holdingId);
    }

    // Returns the complete historical record of monthly rate changes.
    @Transactional(readOnly = true)
    public List<ShareRateHistory> getRateHistory(Long holdingId) {
        getShareHolding(holdingId);

        return rateHistoryRepository
                .findByShareHoldingIdOrderByEffectiveDateAscIdAsc(holdingId);
    }

    /** Load all quantity history for a selected member in one query. */
    @Transactional(readOnly = true)
    public List<ShareTransaction> getTransactionHistoryForEmployee(String employeeId) {
        return transactionRepository.findHistoryByEmployeeId(employeeId.trim());
    }

    /** Load all monthly-rate history for a selected member in one query. */
    @Transactional(readOnly = true)
    public List<ShareRateHistory> getRateHistoryForEmployee(String employeeId) {
        return rateHistoryRepository.findHistoryByEmployeeId(employeeId.trim());
    }




    ///========== Initial Share Allocation ==========///


    // =====================================================
    // NEW: BACKWARD-COMPATIBLE ALLOCATION METHOD
    // =====================================================

    // Backward-compatible allocation method.
    // Used by existing callers that do not supply share valuation.
    @Transactional
    public ShareHolding allocateShares(
            String employeeId,
            ShareHolding.ShareCategory category,
            Integer quantity,
            BigDecimal monthlyRate,
            LocalDate effectiveDate) {

        return allocateShares(
                employeeId,
                category,
                quantity,
                monthlyRate,
                null,
                effectiveDate
        );
    }

    // Creates a holding and records both its initial quantity and initial rate.
    @Transactional
    public ShareHolding allocateShares(
            String employeeId,
            ShareHolding.ShareCategory category,
            Integer quantity,
            BigDecimal monthlyRate,
            BigDecimal shareValuePerShare,
            LocalDate effectiveDate) {

        // Validate all required allocation inputs before accessing the database.
        if (employeeId == null || employeeId.isBlank()) {
            throw new IllegalArgumentException("Employee ID is required");
        }

        if (category == null) {
            throw new IllegalArgumentException("Share category is required");
        }

        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException(
                    "Initial share quantity must be greater than zero");
        }

        validateRate(monthlyRate);
        validateShareValue(shareValuePerShare);
        validateDate(effectiveDate);

        // Resolve the employee using the official employee ID.
        Employee employee = employeeRepository.findByEmployeeId(employeeId)
                .orElseThrow(() -> notFound(
                        "Employee with ID " + employeeId + " was not found."));

        // An employee can have only one holding per share category.
        if (holdingRepository.existsByEmployeeIdAndShareCategory(
                employee.getId(), category)) {

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Share category " + category
                            + " is already allocated to employee " + employeeId + ".");
        }

        // Create and persist the initial share holding.
        ShareHolding holding = new ShareHolding();

        holding.setEmployee(employee);
        holding.setShareCategory(category);
        boolean scheduled = effectiveDate.isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Dhaka")));
        holding.setShareQuantity(scheduled ? 0 : quantity);

        // Existing monthly contribution rate
        holding.setMonthlyRatePerShare(monthlyRate);

        // NEW: Approved share valuation (optional)
        holding.setShareValuePerShare(scheduled ? null : shareValuePerShare);

        // Existing allocation effective date
        holding.setAllocationDate(effectiveDate);

        holding = holdingRepository.saveAndFlush(holding);

        // Record the initial allocation so the original balance remains auditable.
        recordTransaction(
                holding,
                ShareTransaction.ShareTransactionType.INITIAL_ALLOCATION,
                quantity,
                0,
                quantity,
                monthlyRate,
                effectiveDate,
                "Initial share allocation",
                null,
                shareValuePerShare);

        // Store the initial rate for historical contribution calculations.
        insertRateHistory(
                holding,
                monthlyRate,
                effectiveDate,
                "Initial share allocation");

        return holding;
    }





    ///========== Increase / Decrease Shares ==========///

    // Changes an existing holding's quantity and records the balance adjustment.
    @Transactional
    public ShareHolding adjustShares(
            Long holdingId,
            Integer quantityChange,
            LocalDate effectiveDate,
            String reason) {

        // A zero change would create a meaningless transaction.
        if (quantityChange == null || quantityChange == 0) {
            throw new IllegalArgumentException(
                    "Share quantity change cannot be zero");
        }

        validateDate(effectiveDate);
        validateReason(reason);

        // Lock the holding to prevent concurrent adjustments from overwriting
        // one another. Requires the repository's locking query to be configured.
        ShareHolding holding = holdingRepository.findWithLockById(holdingId)
                .orElseThrow(() -> notFound(
                        "Share holding with ID " + holdingId + " was not found."));

        applyQuantityChange(holding, quantityChange, effectiveDate, reason, -1L, false, false);
        return holdingRepository.save(holding);
    }

    // Called only inside the withdrawal service's holding-first locked transaction.
    ShareTransaction postWithdrawalShares(com.fundaxis.entity.ShareWithdrawal withdrawal) {
        if (!withdrawalsEnabled || withdrawal.getStatus() != com.fundaxis.entity.ShareWithdrawal.WithdrawalStatus.APPROVED
                || withdrawal.getSharesPostedAt() != null || withdrawal.getShareTransaction() != null) {
            throw new IllegalStateException("Withdrawal is not eligible for share posting.");
        }
        LocalDate effectiveDate = withdrawal.getEffectiveDate();
        if (effectiveDate == null) throw new IllegalStateException("Withdrawal effective date is missing.");
        validateDate(effectiveDate);
        return applyQuantityChange(withdrawal.getShareHolding(), -withdrawal.getRequestedQuantity(), effectiveDate,
                "Share withdrawal request #" + withdrawal.getId(), withdrawal.getId(), true, true);
    }

    private ShareTransaction applyQuantityChange(ShareHolding holding, int quantityChange,
            LocalDate effectiveDate, String reason, Long excludedWithdrawal, boolean enforceMembership,
            boolean withdrawalPosting) {
        if (enforceMembership) {
            validateMembershipPeriod(holding.getEmployee(), effectiveDate);
        }

        if (holding.getAllocationDate() != null && effectiveDate.isBefore(holding.getAllocationDate())) {
            throw new IllegalArgumentException("Adjustment date cannot precede the holding allocation date.");
        }

        List<ShareTransaction> quantityHistory = transactionRepository
                .findByShareHoldingIdOrderByEffectiveDateAscIdAsc(holding.getId()).stream()
                .filter(t -> QUANTITY_TRANSACTION_TYPES.contains(t.getTransactionType())).toList();
        boolean hasInitialAllocation = quantityHistory.stream()
                .anyMatch(t -> t.getTransactionType() == ShareTransaction.ShareTransactionType.INITIAL_ALLOCATION);
        long currentBalance = quantityHistory.isEmpty() ? holding.getShareQuantity()
                : (hasInitialAllocation ? 0 : holding.getShareQuantity()
                    - quantityHistory.stream().mapToLong(ShareTransaction::getQuantityChange).sum());
        long previousBalance = currentBalance;
        for (ShareTransaction transaction : quantityHistory) {
            long next = currentBalance + transaction.getQuantityChange();
            if (!transaction.getEffectiveDate().isAfter(effectiveDate)) previousBalance = next;
            currentBalance = next;
        }
        long resultingQuantity = previousBalance + quantityChange;

        if (resultingQuantity < 0 || resultingQuantity > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Invalid resulting share quantity");
        }

        long adjustedBalance = resultingQuantity;
        for (ShareTransaction transaction : quantityHistory) {
            if (transaction.getEffectiveDate().isAfter(effectiveDate)) {
                adjustedBalance += transaction.getQuantityChange();
                if (adjustedBalance < 0 || adjustedBalance > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("The adjustment would create an invalid later share balance.");
                }
            }
        }
        long finalBalance = currentBalance + quantityChange;
        if (finalBalance < 0 || finalBalance > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("The adjustment would create an invalid current share balance.");
        }

        if (withdrawalsEnabled && quantityChange < 0 && finalBalance < withdrawalRepository.reserved(holding.getId(), excludedWithdrawal)) {
            throw new IllegalArgumentException("The adjustment would consume shares reserved by withdrawal requests.");
        }
        int newQuantity = (int) resultingQuantity;

        // Select the transaction type based on the direction of the adjustment.
        ShareTransaction.ShareTransactionType type = withdrawalPosting
                ? ShareTransaction.ShareTransactionType.WITHDRAWAL_POSTED
                : quantityChange > 0 ? ShareTransaction.ShareTransactionType.INCREASE
                : ShareTransaction.ShareTransactionType.DECREASE;

        holding.setShareQuantity((int) finalBalance);

        // Persist the audit record and updated balance in the same transaction.
        return recordTransaction(
                holding,
                type,
                quantityChange,
                (int) previousBalance,
                newQuantity,
                rateAsOf(holding, effectiveDate),
                effectiveDate,
                reason.trim(),
                null,
                null);

    }

    ///========== Monthly Rate Change ==========///

    // Changes the monthly rate while preserving its historical rate records.
    @Transactional
    public ShareHolding changeMonthlyRate(
            Long holdingId,
            BigDecimal newRate,
            LocalDate effectiveDate,
            String reason) {

        validateRate(newRate);
        validateDate(effectiveDate);
        validateReason(reason);

        // Lock the holding while validating and applying the rate change.
        ShareHolding holding = holdingRepository.findWithLockById(holdingId)
                .orElseThrow(() -> notFound(
                        "Share holding with ID " + holdingId + " was not found."));

        if (holding.getAllocationDate() != null && effectiveDate.isBefore(holding.getAllocationDate())) {
            throw new IllegalArgumentException("Rate effective date cannot precede the holding allocation date.");
        }

        // Same-day corrections are appended as audited rows. The highest ID
        // deterministically wins for that date; prior rows remain intact.
        BigDecimal effectiveRate = rateHistoryRepository
                .findTopByShareHoldingIdAndEffectiveDateLessThanEqualOrderByEffectiveDateDescIdDesc(holdingId, effectiveDate)
                .map(ShareRateHistory::getMonthlyRatePerShare)
                .orElse(null);
        if (effectiveRate != null && effectiveRate.compareTo(newRate) == 0) {
            throw new IllegalArgumentException(
                    "The selected effective date already has this rate. Enter a different rate or choose another date.");
        }

        insertRateHistory(
                holding,
                newRate,
                effectiveDate,
                reason.trim());

        // The holding column is the latest scheduled state; current reads still
        // resolve history as of today, so future entries are not applied early.
        rateHistoryRepository.flush();
        rateHistoryRepository.findTopByShareHoldingIdOrderByEffectiveDateDescIdDesc(holdingId)
                .ifPresent(latest -> holding.setMonthlyRatePerShare(latest.getMonthlyRatePerShare()));

        return holdingRepository.save(holding);
    }

    /** Update the current internal valuation and retain effective-dated before/after audit snapshots. */
    @Transactional
    public ShareHolding changeShareValue(Long holdingId, BigDecimal newValue,
            LocalDate effectiveDate, String reason) {
        validateShareValue(newValue);
        validateDate(effectiveDate);
        validateReason(reason);

        ShareHolding holding = holdingRepository.findWithLockById(holdingId)
                .orElseThrow(() -> notFound("Share holding with ID " + holdingId + " was not found."));
        if (newValue == null) {
            throw new IllegalArgumentException("New share value per share is required.");
        }
        if (holding.getAllocationDate() != null && effectiveDate.isBefore(holding.getAllocationDate())) {
            throw new IllegalArgumentException("Share value effective date cannot precede the holding allocation date.");
        }

        BigDecimal valueBeforeEffectiveDate = shareValueAsOf(holding, effectiveDate);
        if (valueBeforeEffectiveDate != null && valueBeforeEffectiveDate.compareTo(newValue) == 0) {
            throw new IllegalArgumentException("The selected effective date already has this share value.");
        }

        int effectiveQuantity = quantityAsOf(holding, effectiveDate);
        recordTransaction(holding,
                ShareTransaction.ShareTransactionType.VALUATION_CHANGE,
                0, effectiveQuantity, effectiveQuantity, rateAsOf(holding, effectiveDate),
                effectiveDate, reason.trim(), valueBeforeEffectiveDate, newValue);

        // Store only the value effective today. Future changes remain in the
        // audit stream and are resolved by effective date when read later.
        holding.setShareValuePerShare(shareValueAsOf(
                holding, LocalDate.now(java.time.ZoneId.of("Asia/Dhaka"))));
        return holdingRepository.save(holding);
    }

    ///========== Current Monthly Contribution ==========///

    // Calculates the expected contribution using each holding's current quantity
    // and current monthly rate.
    @Transactional(readOnly = true)
    public BigDecimal calculateCurrentMonthlyContribution(String employeeId) {
        return getEmployeeShares(employeeId)
                .stream()
                .map(ShareHolding::getExpectedMonthlyContribution)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    ///========== Historical Monthly Contribution ==========///

    // A share change is effective for the full calendar month containing its
    // effective date. Existing stored contribution obligations are not rewritten.
    @Transactional(readOnly = true)
    public MonthlyShareContributionResponse calculateMonthlyContribution(
            String employeeId,
            LocalDate contributionMonth) {

        if (employeeId == null || employeeId.isBlank()) {
            throw new IllegalArgumentException("Employee ID is required");
        }

        if (contributionMonth == null) {
            throw new IllegalArgumentException("Contribution month is required");
        }

        Employee employee = employeeRepository.findByEmployeeId(employeeId)
                .orElseThrow(() -> notFound(
                        "Employee with ID " + employeeId + " was not found."));

        // Expected contribution uses the final state effective in that month,
        // including scheduled changes later in the current calendar month.
        LocalDate monthStart = contributionMonth.withDayOfMonth(1);
        LocalDate monthEnd = contributionMonth.with(java.time.temporal.TemporalAdjusters.lastDayOfMonth());
        LocalDate effectiveCutoff = monthEnd;

        // An employee outside the membership period owes no contribution
        // for the requested month.
        if ((employee.getFundJoinDate() != null && monthEnd.isBefore(employee.getFundJoinDate()))
                || (employee.getFundExitDate() != null && monthStart.isAfter(employee.getFundExitDate()))) {

            return new MonthlyShareContributionResponse(
                    employeeId, monthStart, BigDecimal.ZERO);
        }

        BigDecimal total = BigDecimal.ZERO;

        // Calculate the historical contribution for each eligible holding.
        for (ShareHolding holding :
                holdingRepository.findByEmployeeEmployeeId(employeeId)) {

            if (holding.getAllocationDate().isAfter(effectiveCutoff)) {
                continue;
            }

            // Find the last quantity snapshot effective within the contribution month.
            int quantity = quantityAsOf(holding, effectiveCutoff);

            // Find the last rate effective within the contribution month.
            BigDecimal rate = rateHistoryRepository
                    .findTopByShareHoldingIdAndEffectiveDateLessThanEqualOrderByEffectiveDateDescIdDesc(
                    holding.getId(), effectiveCutoff)
                    .map(ShareRateHistory::getMonthlyRatePerShare)
                    .orElseThrow(() -> new IllegalStateException(
                            "Missing historical share rate for holding "
                                    + holding.getId()));

            // Contribution = historical quantity multiplied by historical rate.
            total = total.add(rate.multiply(BigDecimal.valueOf(quantity)));
        }

        return new MonthlyShareContributionResponse(
                employeeId, monthStart, total);
    }



    // ============================================================
// MONTHLY RECORDS → SINGLE MONTH RECORDS
// HISTORICAL SHARE BREAKDOWN
// ============================================================

    public record HistoricalShareBreakdown(
            int oldShares,
            int newShares,
            int totalShares,
            BigDecimal oldMonthlyRate,
            BigDecimal newMonthlyRate,
            BigDecimal expectedMonthlyContribution
    ) {}

    @Transactional(readOnly = true)
    public HistoricalShareBreakdown getHistoricalShareBreakdown(
            String employeeId,
            LocalDate contributionMonth
    ) {

        if (employeeId == null || employeeId.isBlank()) {
            throw new IllegalArgumentException("Employee ID is required.");
        }

        if (contributionMonth == null) {
            throw new IllegalArgumentException("Contribution month is required.");
        }

        LocalDate monthStart = contributionMonth.withDayOfMonth(1);
        LocalDate monthEnd = contributionMonth.with(java.time.temporal.TemporalAdjusters.lastDayOfMonth());
        LocalDate effectiveCutoff = monthEnd;

        int oldShares = 0;
        int newShares = 0;

        BigDecimal oldRate = null;
        BigDecimal newRate = null;
        BigDecimal expectedContribution = BigDecimal.ZERO;

        List<ShareHolding> holdings =
                holdingRepository.findByEmployeeEmployeeId(employeeId.trim());

        for (ShareHolding holding : holdings) {

            if (holding.getAllocationDate() == null
                    || holding.getAllocationDate().isAfter(effectiveCutoff)) {
                continue;
            }

            int historicalQuantity = quantityAsOf(holding, effectiveCutoff);

            if (historicalQuantity <= 0) {
                continue;
            }

            BigDecimal historicalRate = rateHistoryRepository
                    .findTopByShareHoldingIdAndEffectiveDateLessThanEqualOrderByEffectiveDateDescIdDesc(
                            holding.getId(),
                            effectiveCutoff
                    )
                    .map(ShareRateHistory::getMonthlyRatePerShare)
                    .orElseThrow(() -> new IllegalStateException(
                            "Historical share rate not found for holding ID: "
                                    + holding.getId()
                    ));

            if (holding.getShareCategory() == ShareHolding.ShareCategory.OLD) {

                oldShares += historicalQuantity;
                oldRate = historicalRate;

            } else if (holding.getShareCategory() == ShareHolding.ShareCategory.NEW) {

                newShares += historicalQuantity;
                newRate = historicalRate;
            }

            expectedContribution = expectedContribution.add(
                    historicalRate.multiply(
                            BigDecimal.valueOf(historicalQuantity)
                    )
            );
        }

        return new HistoricalShareBreakdown(
                oldShares,
                newShares,
                oldShares + newShares,
                oldRate,
                newRate,
                expectedContribution
        );
    }




    ///========== Internal History Helpers ==========///

    // Stores a rate snapshot so historical contributions can be reconstructed.
    private void insertRateHistory(
            ShareHolding holding,
            BigDecimal rate,
            LocalDate effectiveDate,
            String reason) {

        ShareRateHistory history = new ShareRateHistory();
        history.setShareHolding(holding);
        history.setMonthlyRatePerShare(rate);
        history.setEffectiveDate(effectiveDate);
        history.setReason(reason);
        history.setRecordedBy(recordedBy());

        rateHistoryRepository.save(history);
    }

    // Stores the quantity change, before/after balances, effective date,
    // rate snapshot, and reason for audit purposes.
    private ShareTransaction recordTransaction(
            ShareHolding holding,
            ShareTransaction.ShareTransactionType type,
            int quantityChange,
            int before,
            int after,
            BigDecimal rateSnapshot,
            LocalDate effectiveDate,
            String reason,
            BigDecimal shareValueBefore,
            BigDecimal shareValueAfter) {

        ShareTransaction transaction = new ShareTransaction();
        transaction.setShareHolding(holding);
        transaction.setTransactionType(type);
        transaction.setQuantityChange(quantityChange);
        transaction.setQuantityBefore(before);
        transaction.setQuantityAfter(after);
        transaction.setEffectiveDate(effectiveDate);
        transaction.setRatePerShareSnapshot(rateSnapshot);
        transaction.setReason(reason);
        transaction.setRecordedBy(recordedBy());
        transaction.setShareValueBefore(shareValueBefore);
        transaction.setShareValueAfter(shareValueAfter);

        return transactionRepository.save(transaction);
    }

    private String recordedBy() {
        var authentication = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && authentication.getName() != null && !"anonymousUser".equals(authentication.getName())
                ? authentication.getName() : "SYSTEM";
    }

    ///========== Validation Helpers ==========///

    // Requires a positive rate that fits the database DECIMAL(20,2) column.
    private void validateRate(BigDecimal rate) {
        if (rate == null || rate.signum() <= 0
                || rate.scale() > 2
                || rate.precision() - rate.scale() > 18) {

            throw new IllegalArgumentException(
                    "Monthly rate must be positive and fit DECIMAL(20,2)");
        }
    }



    // Optional share valuation validation.
    private void validateShareValue(BigDecimal shareValuePerShare) {

        // No approved valuation provided.
        if (shareValuePerShare == null) {
            return;
        }

        if (shareValuePerShare.signum() <= 0
                || shareValuePerShare.scale() > 2
                || shareValuePerShare.precision()
                - shareValuePerShare.scale() > 18) {

            throw new IllegalArgumentException(
                    "Share Value per Share must be greater than zero " +
                            "and fit DECIMAL(20,2)."
            );
        }
    }

    // Effective dates may be past, current, or future. Operation ordering,
    // withdrawal membership checks, and contribution eligibility protect history.
    private void validateDate(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("Effective date is required");
        }

    }

    // Ensures the reason is present and does not exceed 500 characters.
    private void validateReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new IllegalArgumentException(
                    "Reason is required (maximum 500 characters)");
        }
    }

    // Ensures the operation date falls within the employee's fund membership.
    private void validateMembershipPeriod(
            Employee employee,
            LocalDate effectiveDate) {

        if (employee.getFundJoinDate() != null
                && effectiveDate.isBefore(employee.getFundJoinDate())) {

            throw new IllegalArgumentException(
                    "Share date cannot precede fund join date");
        }

        if (employee.getFundExitDate() != null
                && effectiveDate.isAfter(employee.getFundExitDate())) {

            throw new IllegalArgumentException(
                    "Share date cannot be after fund exit date");
        }
    }

    // Creates a consistent HTTP 404 exception for missing resources.
    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
