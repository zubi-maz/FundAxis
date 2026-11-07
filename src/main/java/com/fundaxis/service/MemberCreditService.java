
package com.fundaxis.service;

import com.fundaxis.entity.*;
import com.fundaxis.entity.MemberCredit.CreditStatus;
import com.fundaxis.repository.EmployeeRepository;
import com.fundaxis.repository.MemberCreditRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import jakarta.persistence.LockModeType;
import com.fundaxis.entity.Contribution;
import com.fundaxis.entity.MemberCreditAllocation;
import com.fundaxis.repository.ContributionPaymentLockRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import com.fundaxis.entity.MemberCreditAllocation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
public class MemberCreditService {


    @PersistenceContext
    private EntityManager entityManager;

    private final ContributionPaymentLockRepository contributionLockRepository;
    private final MemberCreditRepository creditRepository;
    private final EmployeeRepository employeeRepository;



    public MemberCreditService(
            ContributionPaymentLockRepository contributionLockRepository,
            MemberCreditRepository creditRepository,
            EmployeeRepository employeeRepository) {

        this.contributionLockRepository = contributionLockRepository;
        this.creditRepository = creditRepository;
        this.employeeRepository = employeeRepository;
    }


    // =====================================================
    // 1. RECEIVE ADDITIONAL COLLECTION
    // =====================================================

    @Transactional
    public MemberCredit receive(
            String employeeId,
            BigDecimal amount,
            LocalDate collectionDate,
            ContributionPayment.PaymentMethod paymentMethod,
            String referenceNo,
            String notes) {

        if (employeeId == null || employeeId.isBlank()) {
            throw new IllegalArgumentException(
                    "Employee ID is required");
        }

        validateAmount(amount);
        validateDate(collectionDate, "Collection date");

        if (paymentMethod == null) {
            throw new IllegalArgumentException(
                    "Payment method is required");
        }

        validateLength(referenceNo, 120, "Reference");
        validateLength(notes, 500, "Notes");

        String officialEmployeeId = employeeId.trim();

        Employee employee = employeeRepository
                .findByEmployeeId(officialEmployeeId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Employee not found: "
                                        + officialEmployeeId
                        )
                );

        validateMembership(employee, collectionDate);

        MemberCredit credit = new MemberCredit();

        credit.setEmployee(employee);
        credit.setOriginalAmount(amount);
        credit.setAvailableAmount(amount);
        credit.setStatus(CreditStatus.UNALLOCATED);
        credit.setCollectionDate(collectionDate);
        credit.setPaymentMethod(paymentMethod);
        credit.setReferenceNo(clean(referenceNo));
        credit.setNotes(clean(notes));

        return creditRepository.saveAndFlush(credit);
    }

    // =====================================================
    // 2. EMPLOYEE CREDIT HISTORY
    // =====================================================

    @Transactional(readOnly = true)
    public List<MemberCredit> getHistory(String employeeId) {

        validateEmployeeId(employeeId);

        return creditRepository
                .findByEmployeeEmployeeIdOrderByIdDesc(
                        employeeId.trim()
                );
    }

    // =====================================================
    // 3. AVAILABLE UNALLOCATED CREDIT
    // =====================================================


    @Transactional(readOnly = true)
    public BigDecimal getAvailableBalance(String employeeId) {

        validateEmployeeId(employeeId);

        return creditRepository.sumAvailable(employeeId.trim(),
                List.of(CreditStatus.UNALLOCATED, CreditStatus.PARTIALLY_APPLIED));
    }

    @Transactional(readOnly = true)
    public List<MemberCredit> getRecentCollections(String employeeId) {
        validateEmployeeId(employeeId);
        return creditRepository.findTop3ByEmployeeEmployeeIdOrderByIdDesc(employeeId.trim());
    }


    // =====================================================
    // 4. REVERSE AN UNALLOCATED COLLECTION
    // =====================================================

    @Transactional
    public MemberCredit reverse(
            String employeeId,
            Long creditId,
            LocalDate reversalDate,
            String reason) {

        validateEmployeeId(employeeId);

        if (creditId == null) {
            throw new IllegalArgumentException(
                    "Credit ID is required");
        }

        validateDate(reversalDate, "Reversal date");

        if (reason == null || reason.isBlank()
                || reason.length() > 500) {

            throw new IllegalArgumentException(
                    "Reversal reason is required (max 500 characters)");
        }

        MemberCredit credit = creditRepository
                .findByIdForUpdate(creditId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Additional collection not found: "
                                        + creditId
                        )
                );

        if (!credit.getEmployee()
                .getEmployeeId()
                .equals(employeeId.trim())) {

            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Credit does not belong to employee "
                            + employeeId
            );
        }

        if (credit.getStatus() != CreditStatus.UNALLOCATED) {
            throw new IllegalStateException(
                    "Only unallocated credits can be reversed");
        }

        if (credit.getOriginalAmount()
                .compareTo(credit.getAvailableAmount()) != 0) {

            throw new IllegalStateException(
                    "Cannot reverse a credit that has been applied");
        }

        if (reversalDate.isBefore(credit.getCollectionDate())) {
            throw new IllegalArgumentException(
                    "Reversal date cannot precede collection date");
        }

        credit.setStatus(CreditStatus.REVERSED);
        credit.setAvailableAmount(BigDecimal.ZERO);
        credit.setReversalDate(reversalDate);
        credit.setReversalReason(reason.trim());

        return creditRepository.save(credit);
    }

    // =====================================================
    // VALIDATION HELPERS
    // =====================================================

    private void validateEmployeeId(String employeeId) {

        if (employeeId == null || employeeId.isBlank()) {
            throw new IllegalArgumentException(
                    "Employee ID is required");
        }
    }

    private void validateAmount(BigDecimal amount) {

        if (amount == null
                || amount.signum() <= 0
                || amount.scale() > 2
                || amount.precision() - amount.scale() > 18) {

            throw new IllegalArgumentException(
                    "Amount must be positive and fit DECIMAL(20,2)");
        }
    }

    private void validateDate(LocalDate date, String label) {

        if (date == null) {
            throw new IllegalArgumentException(
                    label + " is required");
        }

        if (date.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException(
                    label + " cannot be in the future");
        }
    }

    private void validateLength(
            String value,
            int max,
            String label) {

        if (value != null && value.length() > max) {
            throw new IllegalArgumentException(
                    label + " cannot exceed "
                            + max + " characters");
        }
    }

    private void validateMembership(
            Employee employee,
            LocalDate date) {

        if (employee.getFundJoinDate() != null
                && date.isBefore(employee.getFundJoinDate())) {

            throw new IllegalArgumentException(
                    "Collection date cannot precede fund join date");
        }

        if (employee.getFundExitDate() != null
                && date.isAfter(employee.getFundExitDate())) {

            throw new IllegalArgumentException(
                    "Collection date cannot be after fund exit date");
        }
    }

    private String clean(String value) {

        return value == null || value.isBlank()
                ? null
                : value.trim();
    }



    // Total active credit allocations against a contribution.
// This is settlement, NOT new cash collected.
    /** Bulk read used by reporting; avoids one allocation query per obligation. */
    @Transactional(readOnly = true)
    public java.util.Map<Long, BigDecimal> getAppliedTotals(java.util.Collection<Long> ids) {
        java.util.Map<Long, BigDecimal> totals = new java.util.HashMap<>();
        java.util.List<Long> keys = new java.util.ArrayList<>(ids);
        for (int offset = 0; offset < keys.size(); offset += 500) {
            for (Object[] row : entityManager.createQuery(
                    "select a.contribution.id, sum(a.amount) from MemberCreditAllocation a "
                    + "where a.reversed=false and a.contribution.id in :ids group by a.contribution.id", Object[].class)
                    .setParameter("ids", keys.subList(offset, Math.min(offset + 500, keys.size())))
                    .getResultList()) {
                totals.put((Long) row[0], (BigDecimal) row[1]);
            }
        }
        return totals;
    }

    @Transactional(readOnly = true)
    public BigDecimal getAppliedTotal(Long contributionId) {
        BigDecimal total = entityManager.createQuery(
                        """
                        SELECT SUM(a.amount)
                        FROM MemberCreditAllocation a
                        WHERE a.contribution.id = :contributionId
                          AND a.reversed = false
                        """,
                        BigDecimal.class
                )
                .setParameter("contributionId", contributionId)
                .getSingleResult();

        return total == null ? BigDecimal.ZERO : total;
    }


    private void recalculateContributionSettlement(Contribution due) {

        Long contributionId = due.getId();

        // Actual contribution cash payments (excluding reversals)
        BigDecimal cash = entityManager.createQuery(
                        """
                        SELECT SUM(p.paymentAmount)
                        FROM ContributionPayment p
                        WHERE p.contribution.id = :contributionId
                          AND p.reversed = false
                        """,
                        BigDecimal.class
                )
                .setParameter("contributionId", contributionId)
                .getSingleResult();

        if (cash == null) {
            cash = BigDecimal.ZERO;
        }

        // Previously received member credits applied to this due
        BigDecimal appliedCredit = getAppliedTotal(contributionId);

        BigDecimal settled = cash.add(appliedCredit);

        // Never allow more than the expected contribution amount
        if (settled.compareTo(due.getContributionAmount()) > 0) {
            throw new IllegalStateException(
                    "Settlement exceeds expected contribution amount");
        }

        boolean fullyPaid =
                settled.compareTo(due.getContributionAmount()) == 0;

        due.setContributionStatus(
                fullyPaid
                        ? Contribution.ContributionStatus.PAID
                        : Contribution.ContributionStatus.PENDING
        );

        if (fullyPaid) {
            // The latest active cash payment or credit application date
            LocalDate lastCashDate = entityManager.createQuery(
                            """
                            SELECT MAX(p.paymentDate)
                            FROM ContributionPayment p
                            WHERE p.contribution.id = :contributionId
                              AND p.reversed = false
                            """,
                            LocalDate.class
                    )
                    .setParameter("contributionId", contributionId)
                    .getSingleResult();

            LocalDate lastCreditDate = entityManager.createQuery(
                            """
                            SELECT MAX(a.appliedDate)
                            FROM MemberCreditAllocation a
                            WHERE a.contribution.id = :contributionId
                              AND a.reversed = false
                            """,
                            LocalDate.class
                    )
                    .setParameter("contributionId", contributionId)
                    .getSingleResult();

            if (lastCashDate == null) {
                due.setPaymentDate(lastCreditDate);
            } else if (lastCreditDate == null) {
                due.setPaymentDate(lastCashDate);
            } else {
                due.setPaymentDate(
                        lastCashDate.isAfter(lastCreditDate)
                                ? lastCashDate
                                : lastCreditDate
                );
            }
        } else {
            due.setPaymentDate(null);
        }
    }






    @Transactional
    public MemberCreditAllocation reverseAppliedCredit(
            String employeeId,
            Long allocationId,
            LocalDate reversalDate,
            String reason) {

        // ============================================
        // 1. INPUT VALIDATION
        // ============================================

        validateEmployeeId(employeeId);
        validateDate(reversalDate, "Reversal date");

        if (allocationId == null) {
            throw new IllegalArgumentException(
                    "Allocation ID is required");
        }

        if (reason == null || reason.isBlank()
                || reason.length() > 500) {
            throw new IllegalArgumentException(
                    "Valid reversal reason is required (max 500 characters)");
        }

        String officialEmployeeId = employeeId.trim();

        // ============================================
        // 2. FIND ORIGINAL ALLOCATION
        // ============================================

        MemberCreditAllocation allocation = entityManager.find(
                MemberCreditAllocation.class,
                allocationId
        );

        if (allocation == null) {
            throw new IllegalArgumentException(
                    "Allocation not found: " + allocationId);
        }

        Long contributionId = allocation.getContribution().getId();
        Long creditId = allocation.getCredit().getId();

        // ============================================
        // 3. LOCK CONTRIBUTION AND MEMBER CREDIT
        // ============================================

        // Same locking order as applyCredit():
        // Contribution first, Member Credit second.

        Contribution due = contributionLockRepository
                .lockById(contributionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Contribution not found: " + contributionId));

        MemberCredit credit = creditRepository
                .findByIdForUpdate(creditId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Member credit not found: " + creditId));

        // Lock original allocation against concurrent reversals.
        allocation = entityManager.find(
                MemberCreditAllocation.class,
                allocationId,
                jakarta.persistence.LockModeType.PESSIMISTIC_WRITE
        );

        if (allocation == null) {
            throw new IllegalStateException(
                    "Allocation no longer exists");
        }

        // ============================================
        // 4. OWNERSHIP AND REVERSAL VALIDATION
        // ============================================

        if (!due.getEmployee().getEmployeeId()
                .equals(officialEmployeeId)
                || !credit.getEmployee().getEmployeeId()
                .equals(officialEmployeeId)) {

            throw new IllegalArgumentException(
                    "Credit and contribution must belong to the same employee");
        }

        if (!allocation.getCredit().getId().equals(creditId)
                || !allocation.getContribution().getId()
                .equals(contributionId)) {
            throw new IllegalStateException(
                    "Allocation reference mismatch");
        }

        if (allocation.isReversed()) {
            throw new IllegalStateException(
                    "Allocation has already been reversed");
        }

        if (reversalDate.isBefore(allocation.getAppliedDate())) {
            throw new IllegalArgumentException(
                    "Reversal date cannot precede application date");
        }

        if (credit.getStatus() == CreditStatus.REVERSED) {
            throw new IllegalStateException(
                    "Cannot reverse allocation of a reversed member credit");
        }

        if (due.getContributionStatus()
                == Contribution.ContributionStatus.CANCELED) {
            throw new IllegalStateException(
                    "Cannot reverse allocation of a canceled contribution");
        }

        // ============================================
        // 5. VALIDATE CREDIT RESTORATION
        // ============================================

        BigDecimal restoredAmount = credit.getAvailableAmount()
                .add(allocation.getAmount());

        if (restoredAmount.compareTo(
                credit.getOriginalAmount()) > 0) {

            throw new IllegalStateException(
                    "Restored credit cannot exceed original collection amount");
        }

        // ============================================
        // 6. REVERSE ALLOCATION (KEEP AUDIT HISTORY)
        // ============================================

        allocation.setReversed(true);
        allocation.setReversalDate(reversalDate);
        allocation.setReversalReason(reason.trim());

        entityManager.flush();

        // ============================================
        // 7. RESTORE AVAILABLE MEMBER CREDIT
        // ============================================

        credit.setAvailableAmount(restoredAmount);

        if (restoredAmount.compareTo(
                credit.getOriginalAmount()) == 0) {

            credit.setStatus(CreditStatus.UNALLOCATED);

        } else if (restoredAmount.signum() == 0) {

            credit.setStatus(CreditStatus.FULLY_APPLIED);

        } else {

            credit.setStatus(CreditStatus.PARTIALLY_APPLIED);
        }

        creditRepository.save(credit);

        // ============================================
        // 8. RECALCULATE CONTRIBUTION SETTLEMENT
        // ============================================

        // Recalculate using:
        // Active cash payments + non-reversed credit allocations.
        // Reversal does NOT create or remove a cash receipt.

        recalculateContributionSettlement(due);

        contributionLockRepository.save(due);

        // ============================================
        // 9. RETURN ORIGINAL ALLOCATION WITH REVERSAL
        // ============================================

        return allocation;
    }




    @Transactional(readOnly = true)
    public List<MemberCreditAllocation> getAllocationHistory(
            String employeeId) {

        validateEmployeeId(employeeId);

        return entityManager.createQuery(
                        """
                        SELECT a
                        FROM MemberCreditAllocation a
                        JOIN FETCH a.credit c
                        JOIN FETCH a.contribution d
                        WHERE c.employee.employeeId = :employeeId
                        ORDER BY a.id DESC
                        """,
                        MemberCreditAllocation.class
                )
                .setParameter("employeeId", employeeId.trim())
                .getResultList();
    }




    @Transactional(readOnly = true)
    public LocalDate getLatestAppliedDate(Long contributionId) {

        if (contributionId == null) {
            throw new IllegalArgumentException(
                    "Contribution ID is required");
        }

        return entityManager.createQuery(
                        """
                        SELECT MAX(a.appliedDate)
                        FROM MemberCreditAllocation a
                        WHERE a.contribution.id = :contributionId
                          AND a.reversed = false
                        """,
                        LocalDate.class
                )
                .setParameter("contributionId", contributionId)
                .getSingleResult();
    }




    @Transactional
    public MemberCreditAllocation applyCredit(
            String employeeId,
            Long creditId,
            Long contributionId,
            BigDecimal amount,
            LocalDate appliedDate
    ) {
        validateEmployeeId(employeeId);
        validateAmount(amount);
        validateDate(appliedDate, "Application date");

        if (creditId == null || contributionId == null) {
            throw new IllegalArgumentException(
                    "Credit ID and Contribution ID are required");
        }

        String memberId = employeeId.trim();

        // Lock contribution first, then credit.
        Contribution due = contributionLockRepository
                .lockById(contributionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Contribution not found: " + contributionId));

        MemberCredit credit = creditRepository
                .findByIdForUpdate(creditId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Member credit not found: " + creditId));

        // Both records must belong to the same employee.
        if (!due.getEmployee().getEmployeeId().equals(memberId)
                || !credit.getEmployee().getEmployeeId().equals(memberId)) {
            throw new IllegalArgumentException(
                    "Credit and contribution must belong to the same employee");
        }

        if (due.getContributionStatus()
                != Contribution.ContributionStatus.PENDING) {
            throw new IllegalStateException(
                    "Only PENDING contributions can receive credit");
        }

        if (credit.getStatus() != CreditStatus.UNALLOCATED
                && credit.getStatus() != CreditStatus.PARTIALLY_APPLIED) {
            throw new IllegalStateException(
                    "Member credit is not available for application");
        }

        if (appliedDate.isBefore(credit.getCollectionDate())) {
            throw new IllegalArgumentException(
                    "Application date cannot precede collection date");
        }

        if (credit.getAvailableAmount().compareTo(amount) < 0) {
            throw new IllegalArgumentException(
                    "Insufficient available member credit");
        }

        // Active cash payments already received for this due.
        BigDecimal cash = entityManager.createQuery(
                        """
                        SELECT SUM(p.paymentAmount)
                        FROM ContributionPayment p
                        WHERE p.contribution.id = :contributionId
                          AND p.reversed = false
                        """,
                        BigDecimal.class
                )
                .setParameter("contributionId", contributionId)
                .getSingleResult();

        if (cash == null) {
            cash = BigDecimal.ZERO;
        }

        // Internal allocations settle dues but are not new cash.
        BigDecimal appliedTotal = getAppliedTotal(contributionId);

        BigDecimal outstanding = due.getContributionAmount()
                .subtract(cash)
                .subtract(appliedTotal);

        if (outstanding.signum() <= 0
                || amount.compareTo(outstanding) > 0) {
            throw new IllegalArgumentException(
                    "Amount exceeds contribution outstanding: " + outstanding);
        }

        MemberCreditAllocation allocation =
                new MemberCreditAllocation();

        allocation.setCredit(credit);
        allocation.setContribution(due);
        allocation.setAmount(amount);
        allocation.setAppliedDate(appliedDate);

        entityManager.persist(allocation);
        entityManager.flush();

        // Deduct the amount from available member credit.
        BigDecimal remaining = credit.getAvailableAmount()
                .subtract(amount);

        credit.setAvailableAmount(remaining);
        credit.setStatus(
                remaining.signum() == 0
                        ? CreditStatus.FULLY_APPLIED
                        : CreditStatus.PARTIALLY_APPLIED
        );

        creditRepository.save(credit);

        // Recalculate due using cash + active credit allocations.
        recalculateContributionSettlement(due);

        contributionLockRepository.save(due);

        return allocation;
    }












}
