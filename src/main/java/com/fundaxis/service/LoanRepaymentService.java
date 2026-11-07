package com.fundaxis.service;

import com.fundaxis.entity.LoanRepayment;
import com.fundaxis.entity.RepaymentSchedule;
import com.fundaxis.exception.DuplicateLoanRepaymentException;
import com.fundaxis.exception.LoanRepaymentNotFoundException;
import com.fundaxis.exception.RepaymentScheduleNotFoundException;
import com.fundaxis.repository.LoanRepaymentRepository;
import com.fundaxis.repository.RepaymentScheduleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
public class LoanRepaymentService {

    private final LoanRepaymentRepository loanRepaymentRepository;
    private final RepaymentScheduleRepository repaymentScheduleRepository;

    ///---------- Constructor Injection ----------
    public LoanRepaymentService(LoanRepaymentRepository loanRepaymentRepository, RepaymentScheduleRepository repaymentScheduleRepository) {
        this.loanRepaymentRepository = loanRepaymentRepository;
        this.repaymentScheduleRepository = repaymentScheduleRepository;

    }


    ///==================== Create Loan Repayment ====================///

    ///---------- Create A Repayment Transaction For One Installment ----------
    @Transactional
    public LoanRepayment createLoanRepayment(Long repaymentScheduleId, LoanRepayment loanRepayment) {

        RepaymentSchedule schedule = findRepaymentScheduleByIdForUpdate(repaymentScheduleId);

        // Cancelled installments cannot receive repayments
        if (schedule.getInstallmentStatus() == RepaymentSchedule.InstallmentStatus.CANCELLED) {

            throw new IllegalStateException("Cannot create repayment for a cancelled installment: " + repaymentScheduleId);

        }

        // Fully paid installments cannot receive additional repayments
        if (schedule.getInstallmentStatus() == RepaymentSchedule.InstallmentStatus.PAID) {

            throw new IllegalStateException("Installment is already fully paid: " + repaymentScheduleId);

        }

        validateRepaymentAmount(loanRepayment.getRepaymentAmount(), schedule.getRemainingAmount());

        // Normalize and validate transaction reference
        String referenceNumber = normalizeRequiredText(loanRepayment.getReferenceNumber(), "Reference number is required");

        // Prevent duplicate transaction references
        if (loanRepaymentRepository.existsByReferenceNumber(referenceNumber)) {

            throw new DuplicateLoanRepaymentException("Repayment reference number already exists: " + referenceNumber);

        }

        // Prevent multiple active repayment workflows for the same installment
        boolean pendingRepaymentExists = loanRepaymentRepository.existsByRepaymentScheduleIdAndRepaymentStatus(repaymentScheduleId, LoanRepayment.RepaymentStatus.PENDING);

        boolean processingRepaymentExists = loanRepaymentRepository.existsByRepaymentScheduleIdAndRepaymentStatus(repaymentScheduleId, LoanRepayment.RepaymentStatus.PROCESSING);

        if (pendingRepaymentExists || processingRepaymentExists) {

            throw new IllegalStateException("An active repayment transaction already exists for repayment schedule ID: " + repaymentScheduleId);

        }

        // Connect repayment to its installment
        loanRepayment.setRepaymentSchedule(schedule);

        loanRepayment.setReferenceNumber(referenceNumber);

        // System-controlled workflow fields
        loanRepayment.setRepaymentStatus(LoanRepayment.RepaymentStatus.PENDING);

        loanRepayment.setProcessedBy(null);
        loanRepayment.setProcessingDate(null);
        loanRepayment.setFailureReason(null);
        loanRepayment.setCancellationReason(null);

        return loanRepaymentRepository.save(loanRepayment);

    }


    ///==================== Read Loan Repayment ====================///

    ///---------- Get All Repayment Transactions ----------
    public List<LoanRepayment> getAllLoanRepayments() {

        return loanRepaymentRepository.findAll();

    }


    ///---------- Get Repayment By Database ID (id) ----------
    public LoanRepayment getLoanRepaymentById(Long id) {

        return findLoanRepaymentById(id);

    }


    ///---------- Get Repayments For One Installment ----------
    public List<LoanRepayment> getLoanRepaymentsByScheduleId(Long repaymentScheduleId) {

        return loanRepaymentRepository.findByRepaymentScheduleIdOrderByCreatedAtDesc(repaymentScheduleId);

    }


    ///---------- Get Repayment By Unique Transaction Reference ----------
    public LoanRepayment getLoanRepaymentByReferenceNumber(String referenceNumber) {

        String normalizedReference = normalizeRequiredText(referenceNumber, "Reference number is required");

        return loanRepaymentRepository.findByReferenceNumber(normalizedReference).orElseThrow(() -> new LoanRepaymentNotFoundException("Loan repayment not found with reference number: " + normalizedReference));

    }


    ///---------- Get Repayments By Processing Status ----------
    public List<LoanRepayment> getLoanRepaymentsByStatus(LoanRepayment.RepaymentStatus repaymentStatus) {

        return loanRepaymentRepository.findByRepaymentStatusOrderByCreatedAtDesc(repaymentStatus);

    }


    ///---------- Get Repayments By Payment Method ----------
    public List<LoanRepayment> getLoanRepaymentsByPaymentMethod(LoanRepayment.PaymentMethod paymentMethod) {

        return loanRepaymentRepository.findByPaymentMethodOrderByCreatedAtDesc(paymentMethod);

    }


    ///---------- Get Repayments Made On A Specific Date ----------
    public List<LoanRepayment> getLoanRepaymentsByDate(LocalDate repaymentDate) {

        if (repaymentDate == null) {

            throw new IllegalArgumentException("Repayment date is required");

        }

        return loanRepaymentRepository.findByRepaymentDateOrderByCreatedAtDesc(repaymentDate);

    }


    ///---------- Get All Repayment Transactions Belonging To One Loan ----------
    public List<LoanRepayment> getLoanRepaymentsByLoanId(Long loanId) {

        return loanRepaymentRepository.findByRepaymentScheduleLoanIdOrderByCreatedAtDesc(loanId);

    }


    ///---------- Get All Repayment Transactions Belonging To One Employee ----------
    public List<LoanRepayment> getEmployeeLoanRepayments(String employeeId) {

        return loanRepaymentRepository.findByRepaymentScheduleLoanEmployeeEmployeeIdOrderByCreatedAtDesc(employeeId);

    }


    ///---------- Get An Employee's Repayment Transactions By Status ----------
    public List<LoanRepayment> getEmployeeLoanRepaymentsByStatus(String employeeId, LoanRepayment.RepaymentStatus repaymentStatus) {

        return loanRepaymentRepository.findByRepaymentScheduleLoanEmployeeEmployeeIdAndRepaymentStatusOrderByCreatedAtDesc(employeeId, repaymentStatus);

    }


    ///---------- Get Repayment Transactions For One Installment By Status ----------
    public List<LoanRepayment> getScheduleLoanRepaymentsByStatus(Long repaymentScheduleId, LoanRepayment.RepaymentStatus repaymentStatus) {

        return loanRepaymentRepository.findByRepaymentScheduleIdAndRepaymentStatusOrderByCreatedAtDesc(repaymentScheduleId, repaymentStatus);

    }


    ///==================== Loan Repayment Workflow ====================///

    ///---------- Start Processing A Pending Repayment ----------
    @Transactional
    public LoanRepayment startProcessing(Long id) {

        LoanRepayment loanRepayment = findLoanRepaymentByIdForUpdate(id);

        // Only PENDING repayments can start processing
        if (loanRepayment.getRepaymentStatus() != LoanRepayment.RepaymentStatus.PENDING) {

            throw new IllegalStateException("Only PENDING repayments can be processed: " + id);

        }

        RepaymentSchedule schedule = findRepaymentScheduleByIdForUpdate(
                loanRepayment.getRepaymentSchedule().getId());

        // Recheck installment state before processing
        if (schedule.getInstallmentStatus() == RepaymentSchedule.InstallmentStatus.CANCELLED) {

            throw new IllegalStateException("Cannot process repayment for a cancelled installment");

        }

        if (schedule.getInstallmentStatus() == RepaymentSchedule.InstallmentStatus.PAID) {

            throw new IllegalStateException("Installment is already fully paid");

        }

        // Recheck current remaining balance
        validateRepaymentAmount(loanRepayment.getRepaymentAmount(), schedule.getRemainingAmount());

        loanRepayment.setRepaymentStatus(LoanRepayment.RepaymentStatus.PROCESSING);

        loanRepayment.setProcessingDate(LocalDate.now());

        return loanRepaymentRepository.save(loanRepayment);

    }


    ///---------- Complete A Repayment And Apply The Money To The Installment ----------
    @Transactional
    public LoanRepayment completeLoanRepayment(Long id, String processedBy) {

        LoanRepayment loanRepayment = findLoanRepaymentByIdForUpdate(id);

        // Only PROCESSING repayments can be completed
        if (loanRepayment.getRepaymentStatus() != LoanRepayment.RepaymentStatus.PROCESSING) {

            throw new IllegalStateException("Only PROCESSING repayments can be completed: " + id);

        }

        String normalizedProcessedBy = normalizeRequiredText(processedBy, "Processed by is required");

        RepaymentSchedule schedule = findRepaymentScheduleByIdForUpdate(
                loanRepayment.getRepaymentSchedule().getId());

        // Cancelled installments cannot receive money
        if (schedule.getInstallmentStatus() == RepaymentSchedule.InstallmentStatus.CANCELLED) {

            throw new IllegalStateException("Cannot complete repayment for a cancelled installment");

        }

        // Another completed transaction may have fully settled the installment while this transaction was processing
        if (schedule.getInstallmentStatus() == RepaymentSchedule.InstallmentStatus.PAID) {

            throw new IllegalStateException("Installment is already fully paid");

        }

        BigDecimal currentPaidAmount = schedule.getPaidAmount();

        BigDecimal currentRemainingAmount = schedule.getRemainingAmount();

        if (currentPaidAmount == null || currentRemainingAmount == null) {

            throw new IllegalStateException("Repayment schedule financial values are invalid");

        }

        // Recheck against the latest remaining balance
        validateRepaymentAmount(loanRepayment.getRepaymentAmount(), currentRemainingAmount);

        BigDecimal newPaidAmount = currentPaidAmount.add(loanRepayment.getRepaymentAmount());

        BigDecimal newRemainingAmount = schedule.getTotalInstallmentAmount().subtract(newPaidAmount);

        // Defensive financial integrity check
        if (newRemainingAmount.compareTo(BigDecimal.ZERO) < 0) {

            throw new IllegalStateException("Repayment would cause the installment remaining amount to become negative");

        }

        schedule.setPaidAmount(newPaidAmount);
        schedule.setRemainingAmount(newRemainingAmount);

        if (newRemainingAmount.compareTo(BigDecimal.ZERO) == 0) {

            // Installment has been completely settled
            schedule.setInstallmentStatus(RepaymentSchedule.InstallmentStatus.PAID);

            schedule.setPaymentDate(loanRepayment.getRepaymentDate());

        } else {

            // Installment still has an outstanding balance
            schedule.setInstallmentStatus(RepaymentSchedule.InstallmentStatus.PARTIAL);

            schedule.setPaymentDate(null);

        }

        // Complete the repayment transaction
        loanRepayment.setProcessedBy(normalizedProcessedBy);

        loanRepayment.setFailureReason(null);
        loanRepayment.setCancellationReason(null);

        loanRepayment.setRepaymentStatus(LoanRepayment.RepaymentStatus.COMPLETED);

        repaymentScheduleRepository.save(schedule);

        return loanRepaymentRepository.save(loanRepayment);

    }


    ///---------- Mark A Processing Repayment As Failed ----------
    @Transactional
    public LoanRepayment failLoanRepayment(Long id, String failureReason) {

        LoanRepayment loanRepayment = findLoanRepaymentByIdForUpdate(id);

        // Only PROCESSING repayments can fail
        if (loanRepayment.getRepaymentStatus() != LoanRepayment.RepaymentStatus.PROCESSING) {

            throw new IllegalStateException("Only PROCESSING repayments can be marked as FAILED: " + id);

        }

        String normalizedFailureReason = normalizeRequiredText(failureReason, "Failure reason is required");

        loanRepayment.setFailureReason(normalizedFailureReason);

        loanRepayment.setCancellationReason(null);
        loanRepayment.setProcessedBy(null);

        loanRepayment.setRepaymentStatus(LoanRepayment.RepaymentStatus.FAILED);

        return loanRepaymentRepository.save(loanRepayment);

    }


    ///---------- Cancel A Pending Repayment ----------
    @Transactional
    public LoanRepayment cancelLoanRepayment(Long id, String cancellationReason) {

        LoanRepayment loanRepayment = findLoanRepaymentByIdForUpdate(id);

        // Only PENDING repayments can be cancelled
        if (loanRepayment.getRepaymentStatus() != LoanRepayment.RepaymentStatus.PENDING) {

            throw new IllegalStateException("Only PENDING repayments can be cancelled: " + id);

        }

        String normalizedCancellationReason = normalizeRequiredText(cancellationReason, "Cancellation reason is required");

        loanRepayment.setCancellationReason(normalizedCancellationReason);

        loanRepayment.setFailureReason(null);
        loanRepayment.setProcessedBy(null);
        loanRepayment.setProcessingDate(null);

        loanRepayment.setRepaymentStatus(LoanRepayment.RepaymentStatus.CANCELLED);

        return loanRepaymentRepository.save(loanRepayment);

    }


    ///==================== Helper Methods ====================///

    ///---------- Find Repayment Or Return A Meaningful 404 Error ----------
    private LoanRepayment findLoanRepaymentById(Long id) {

        return loanRepaymentRepository.findById(id).orElseThrow(() -> new LoanRepaymentNotFoundException("Loan repayment not found with ID: " + id));

    }

    private LoanRepayment findLoanRepaymentByIdForUpdate(Long id) {
        return loanRepaymentRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new LoanRepaymentNotFoundException(
                        "Loan repayment not found with ID: " + id));
    }


    private RepaymentSchedule findRepaymentScheduleByIdForUpdate(Long repaymentScheduleId) {
        return repaymentScheduleRepository.findByIdForUpdate(repaymentScheduleId)
                .orElseThrow(() -> new RepaymentScheduleNotFoundException(
                        "Repayment schedule not found with ID: " + repaymentScheduleId));
    }


    ///---------- Validate Repayment Amount Against The Current Installment Balance ----------
    private void validateRepaymentAmount(BigDecimal repaymentAmount, BigDecimal remainingAmount) {

        if (repaymentAmount == null) {

            throw new IllegalArgumentException("Repayment amount is required");

        }

        if (repaymentAmount.compareTo(BigDecimal.ZERO) <= 0) {

            throw new IllegalArgumentException("Repayment amount must be greater than zero");

        }

        if (remainingAmount == null || remainingAmount.compareTo(BigDecimal.ZERO) <= 0) {

            throw new IllegalStateException("Installment has no remaining amount to repay");

        }

        if (repaymentAmount.compareTo(remainingAmount) > 0) {

            throw new IllegalArgumentException("Repayment amount exceeds remaining installment amount. Remaining amount: " + remainingAmount);

        }

    }


    ///---------- Validate And Normalize Required Text Values ----------
    private String normalizeRequiredText(String value, String errorMessage) {

        if (value == null || value.trim().isEmpty()) {

            throw new IllegalArgumentException(errorMessage);

        }

        return value.trim();

    }
}
