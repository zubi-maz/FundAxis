package com.fundaxis.service;

import com.fundaxis.entity.Repayment;
import com.fundaxis.entity.RepaymentSchedule;
import com.fundaxis.exception.DuplicateRepaymentException;
import com.fundaxis.exception.RepaymentNotFoundException;
import com.fundaxis.exception.RepaymentScheduleNotFoundException;
import com.fundaxis.repository.RepaymentRepository;
import com.fundaxis.repository.RepaymentScheduleRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;


@Service
public class RepaymentService {


    // ==================== Repository Dependencies ==================== //

    private final RepaymentRepository repaymentRepository;
    private final RepaymentScheduleRepository repaymentScheduleRepository;


    // ==================== Constructor Injection ==================== //

    public RepaymentService(
            RepaymentRepository repaymentRepository,
            RepaymentScheduleRepository repaymentScheduleRepository
    ) {
        this.repaymentRepository = repaymentRepository;
        this.repaymentScheduleRepository = repaymentScheduleRepository;
    }


    // ============================================================
    // CREATE / RECORD REPAYMENT
    // ============================================================ //

    ///---------- Record A Completed Repayment Against A Schedule ----------
    @Transactional
    public Repayment recordRepayment(
            Long repaymentScheduleId,
            Repayment repayment,
            String recordedBy
    ) {

        // Find parent repayment schedule.
        RepaymentSchedule schedule =
                repaymentScheduleRepository
                        .findByIdForUpdate(repaymentScheduleId)
                        .orElseThrow(() ->
                                new RepaymentScheduleNotFoundException(
                                        "Repayment schedule not found with ID: "
                                                + repaymentScheduleId
                                )
                        );


        // Validate the schedule can accept payment.
        validateScheduleForRepayment(schedule);


        // Validate repayment input.
        validateRepayment(repayment);


        // Normalize reference number.
        String referenceNumber =
                repayment.getReferenceNumber().trim();


        // Prevent duplicate repayment reference numbers.
        if (repaymentRepository
                .existsByReferenceNumberIgnoreCase(referenceNumber)) {

            throw new DuplicateRepaymentException(
                    "Repayment reference number already exists: "
                            + referenceNumber
            );
        }


        // Amount must not exceed the outstanding installment balance.
        BigDecimal remainingAmount =
                schedule.getRemainingAmount();


        if (repayment.getRepaymentAmount()
                .compareTo(remainingAmount) > 0) {

            throw new IllegalArgumentException(
                    "Repayment amount cannot exceed remaining installment amount of "
                            + remainingAmount
            );
        }


        // Connect transaction to repayment schedule.
        repayment.setRepaymentSchedule(schedule);


        // Save normalized reference.
        repayment.setReferenceNumber(referenceNumber);


        // Repayment is immediately completed when recorded.
        repayment.setRepaymentStatus(
                Repayment.RepaymentStatus.COMPLETED
        );


        // Record the administrator username.
        repayment.setRecordedBy(
                recordedBy != null
                        ? recordedBy.trim()
                        : null
        );


        // Clear reversal fields for a new transaction.
        repayment.setReversalReason(null);
        repayment.setReversalDate(null);
        repayment.setReversedBy(null);


        // Save repayment transaction first.
        Repayment savedRepayment =
                repaymentRepository.save(repayment);


        // Apply financial impact to the repayment schedule.
        applyRepaymentToSchedule(
                schedule,
                repayment.getRepaymentAmount(),
                repayment.getRepaymentDate()
        );


        return savedRepayment;
    }


    // ============================================================
    // READ REPAYMENTS
    // ============================================================ //

    ///---------- Get All Repayment Transactions ----------
    public List<Repayment> getAllRepayments() {

        return repaymentRepository.findAllByOrderByRepaymentDateDescCreatedAtDesc();
    }


    ///---------- Get Repayment By Database ID ----------
    public Repayment getRepaymentById(Long id) {

        return repaymentRepository
                .findById(id)
                .orElseThrow(() ->
                        new RepaymentNotFoundException(
                                "Repayment not found with ID: " + id
                        )
                );
    }


    ///---------- Get Repayment By Unique Reference Number ----------
    public Repayment getRepaymentByReferenceNumber(
            String referenceNumber
    ) {

        if (referenceNumber == null
                || referenceNumber.trim().isEmpty()) {

            throw new IllegalArgumentException(
                    "Reference number is required"
            );
        }


        return repaymentRepository
                .findByReferenceNumberIgnoreCase(
                        referenceNumber.trim()
                )
                .orElseThrow(() ->
                        new RepaymentNotFoundException(
                                "Repayment not found with reference number: "
                                        + referenceNumber
                        )
                );
    }


    ///---------- Get All Repayments For One Repayment Schedule ----------
    public List<Repayment> getRepaymentsByScheduleId(
            Long repaymentScheduleId
    ) {

        return repaymentRepository
                .findByRepaymentScheduleIdOrderByRepaymentDateDescCreatedAtDesc(
                        repaymentScheduleId
                );
    }


    ///---------- Get All Repayments For One Loan ----------
    public List<Repayment> getRepaymentsByLoanId(
            Long loanId
    ) {

        return repaymentRepository
                .findByRepaymentScheduleLoanIdOrderByRepaymentDateDescCreatedAtDesc(
                        loanId
                );
    }


    ///---------- Get All Repayments For One Employee ----------
    public List<Repayment> getRepaymentsByEmployeeId(
            String employeeId
    ) {

        return repaymentRepository
                .findByRepaymentScheduleLoanEmployeeEmployeeIdOrderByRepaymentDateDescCreatedAtDesc(
                        employeeId
                );
    }


    ///---------- Get Repayments By Status ----------
    public List<Repayment> getRepaymentsByStatus(
            Repayment.RepaymentStatus status
    ) {

        return repaymentRepository
                .findByRepaymentStatusOrderByRepaymentDateDescCreatedAtDesc(
                        status
                );
    }


    ///---------- Get Repayments By Payment Method ----------
    public List<Repayment> getRepaymentsByPaymentMethod(
            Repayment.PaymentMethod paymentMethod
    ) {

        return repaymentRepository
                .findByPaymentMethodOrderByRepaymentDateDescCreatedAtDesc(
                        paymentMethod
                );
    }


    // ============================================================
    // TOTALS
    // ============================================================ //

    ///---------- Total COMPLETED Amount For One Schedule ----------
    public BigDecimal getTotalCompletedAmountForSchedule(
            Long repaymentScheduleId
    ) {

        BigDecimal total =
                repaymentRepository
                        .sumCompletedRepaymentAmountByScheduleId(
                                repaymentScheduleId
                        );


        return total != null
                ? total
                : BigDecimal.ZERO;
    }


    ///---------- Total COMPLETED Repayment Amount Across System ----------
    public BigDecimal getTotalCompletedRepaymentAmount() {

        BigDecimal total =
                repaymentRepository
                        .sumCompletedRepaymentAmount();


        return total != null
                ? total
                : BigDecimal.ZERO;
    }


    // ============================================================
    // REVERSE REPAYMENT
    // ============================================================ //

    ///---------- Reverse A Previously Completed Repayment ----------
    @Transactional
    public Repayment reverseRepayment(
            Long repaymentId,
            String reversalReason,
            String reversedBy
    ) {

        Repayment repayment =
                repaymentRepository.findByIdForUpdate(repaymentId)
                        .orElseThrow(() -> new RepaymentNotFoundException(
                                "Repayment not found with ID: " + repaymentId));


        // Only completed repayment can be reversed.
        if (repayment.getRepaymentStatus()
                != Repayment.RepaymentStatus.COMPLETED) {

            throw new IllegalStateException(
                    "Only completed repayments can be reversed: "
                            + repaymentId
            );
        }


        // Reversal reason is mandatory.
        if (reversalReason == null
                || reversalReason.trim().isEmpty()) {

            throw new IllegalArgumentException(
                    "Reversal reason is required"
            );
        }


        // Administrator username is mandatory.
        if (reversedBy == null
                || reversedBy.trim().isEmpty()) {

            throw new IllegalArgumentException(
                    "Reversed by is required"
            );
        }


        RepaymentSchedule schedule = repaymentScheduleRepository
                .findByIdForUpdate(repayment.getRepaymentSchedule().getId())
                .orElseThrow(() -> new RepaymentScheduleNotFoundException(
                        "Repayment schedule not found with ID: "
                                + repayment.getRepaymentSchedule().getId()));


        if (schedule == null) {

            throw new IllegalStateException(
                    "Repayment is not connected to a repayment schedule"
            );
        }


        // Restore the financial amount to the schedule.
        restoreRepaymentToSchedule(
                schedule,
                repayment.getRepaymentAmount()
        );


        // Change transaction status.
        repayment.setRepaymentStatus(
                Repayment.RepaymentStatus.REVERSED
        );


        repayment.setReversalReason(
                reversalReason.trim()
        );


        repayment.setReversalDate(
                LocalDate.now()
        );


        repayment.setReversedBy(
                reversedBy.trim()
        );


        return repaymentRepository.save(repayment);
    }


    // ============================================================
    // HELPER — APPLY COMPLETED REPAYMENT
    // ============================================================ //

    private void applyRepaymentToSchedule(
            RepaymentSchedule schedule,
            BigDecimal repaymentAmount,
            LocalDate repaymentDate
    ) {

        BigDecimal currentPaid =
                schedule.getPaidAmount() != null
                        ? schedule.getPaidAmount()
                        : BigDecimal.ZERO;


        BigDecimal currentRemaining =
                schedule.getRemainingAmount();


        if (currentRemaining == null) {

            throw new IllegalStateException(
                    "Repayment schedule remaining amount is missing"
            );
        }


        // Calculate new amounts.
        BigDecimal newPaid =
                currentPaid.add(repaymentAmount);


        BigDecimal newRemaining =
                currentRemaining.subtract(repaymentAmount);


        // Safety check.
        if (newRemaining.compareTo(BigDecimal.ZERO) < 0) {

            throw new IllegalStateException(
                    "Repayment would create a negative remaining balance"
            );
        }


        schedule.setPaidAmount(newPaid);
        schedule.setRemainingAmount(newRemaining);


        // Fully paid.
        if (newRemaining.compareTo(BigDecimal.ZERO) == 0) {

            schedule.setInstallmentStatus(
                    RepaymentSchedule.InstallmentStatus.PAID
            );


            schedule.setPaymentDate(
                    repaymentDate != null
                            ? repaymentDate
                            : LocalDate.now()
            );

        } else {

            // Any successful payment with outstanding balance means PARTIAL.
            schedule.setInstallmentStatus(
                    RepaymentSchedule.InstallmentStatus.PARTIAL
            );


            schedule.setPaymentDate(null);
        }


        repaymentScheduleRepository.save(schedule);
    }


    // ============================================================
    // HELPER — RESTORE REVERSED REPAYMENT
    // ============================================================ //

    private void restoreRepaymentToSchedule(
            RepaymentSchedule schedule,
            BigDecimal repaymentAmount
    ) {

        if (repaymentAmount == null
                || repaymentAmount.compareTo(BigDecimal.ZERO) <= 0) {

            throw new IllegalStateException(
                    "Repayment amount is invalid for reversal"
            );
        }


        BigDecimal currentPaid =
                schedule.getPaidAmount() != null
                        ? schedule.getPaidAmount()
                        : BigDecimal.ZERO;


        BigDecimal currentRemaining =
                schedule.getRemainingAmount() != null
                        ? schedule.getRemainingAmount()
                        : BigDecimal.ZERO;


        // A reversal must never make paid amount negative.
        if (currentPaid.compareTo(repaymentAmount) < 0) {

            throw new IllegalStateException(
                    "Repayment reversal would make paid amount negative"
            );
        }


        BigDecimal newPaid =
                currentPaid.subtract(repaymentAmount);


        BigDecimal newRemaining =
                currentRemaining.add(repaymentAmount);


        // Remaining amount must not exceed original installment total.
        if (schedule.getTotalInstallmentAmount() != null
                && newRemaining.compareTo(
                schedule.getTotalInstallmentAmount()
        ) > 0) {

            throw new IllegalStateException(
                    "Repayment reversal would exceed the original installment amount"
            );
        }


        schedule.setPaidAmount(newPaid);
        schedule.setRemainingAmount(newRemaining);


        // No payment remains.
        if (newPaid.compareTo(BigDecimal.ZERO) == 0) {

            schedule.setInstallmentStatus(
                    determineUnpaidScheduleStatus(schedule)
            );


            schedule.setPaymentDate(null);

        } else {

            // Some amount is still paid, so schedule is partial.
            schedule.setInstallmentStatus(
                    RepaymentSchedule.InstallmentStatus.PARTIAL
            );


            schedule.setPaymentDate(null);
        }


        repaymentScheduleRepository.save(schedule);
    }


    // ============================================================
    // HELPER — DETERMINE STATUS AFTER FULL REVERSAL
    // ============================================================ //

    private RepaymentSchedule.InstallmentStatus determineUnpaidScheduleStatus(
            RepaymentSchedule schedule
    ) {

        // If due date has passed, restore it as OVERDUE.
        if (schedule.getDueDate() != null
                && schedule.getDueDate().isBefore(LocalDate.now())) {

            return RepaymentSchedule.InstallmentStatus.OVERDUE;
        }


        // Otherwise it returns to PENDING.
        return RepaymentSchedule.InstallmentStatus.PENDING;
    }


    // ============================================================
    // HELPER — VALIDATE REPAYMENT
    // ============================================================ //

    private void validateRepayment(
            Repayment repayment
    ) {

        if (repayment == null) {

            throw new IllegalArgumentException(
                    "Repayment information is required"
            );
        }


        if (repayment.getReferenceNumber() == null
                || repayment.getReferenceNumber()
                .trim()
                .isEmpty()) {

            throw new IllegalArgumentException(
                    "Reference number is required"
            );
        }


        if (repayment.getRepaymentDate() == null) {

            throw new IllegalArgumentException(
                    "Repayment date is required"
            );
        }

        if (repayment.getRepaymentDate().isAfter(LocalDate.now())) {
            throw new IllegalArgumentException(
                    "Repayment date cannot be in the future"
            );
        }


        if (repayment.getRepaymentAmount() == null) {

            throw new IllegalArgumentException(
                    "Repayment amount is required"
            );
        }


        if (repayment.getRepaymentAmount()
                .compareTo(BigDecimal.ZERO) <= 0) {

            throw new IllegalArgumentException(
                    "Repayment amount must be greater than zero"
            );
        }


        if (repayment.getPaymentMethod() == null) {

            throw new IllegalArgumentException(
                    "Payment method is required"
            );
        }
    }


    // ============================================================
    // HELPER — VALIDATE SCHEDULE
    // ============================================================ //

    private void validateScheduleForRepayment(
            RepaymentSchedule schedule
    ) {

        if (schedule.getInstallmentStatus()
                == RepaymentSchedule.InstallmentStatus.PAID) {

            throw new IllegalStateException(
                    "Paid repayment schedule cannot receive another repayment"
            );
        }


        if (schedule.getInstallmentStatus()
                == RepaymentSchedule.InstallmentStatus.CANCELLED) {

            throw new IllegalStateException(
                    "Cancelled repayment schedule cannot receive repayment"
            );
        }


        if (schedule.getRemainingAmount() == null) {

            throw new IllegalStateException(
                    "Repayment schedule remaining amount is missing"
            );
        }


        if (schedule.getRemainingAmount()
                .compareTo(BigDecimal.ZERO) <= 0) {

            throw new IllegalStateException(
                    "Repayment schedule has no outstanding amount"
            );
        }
    }
}
