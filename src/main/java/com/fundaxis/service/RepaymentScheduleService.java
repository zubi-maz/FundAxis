package com.fundaxis.service;

import com.fundaxis.entity.Loan;
import com.fundaxis.entity.RepaymentSchedule;
import com.fundaxis.exception.DuplicateRepaymentScheduleException;
import com.fundaxis.exception.LoanNotFoundException;
import com.fundaxis.exception.RepaymentScheduleNotFoundException;
import com.fundaxis.repository.LoanRepository;
import com.fundaxis.repository.RepaymentScheduleRepository;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
public class RepaymentScheduleService {

    private final RepaymentScheduleRepository repaymentScheduleRepository;
    private final LoanRepository loanRepository;

    ///---------- Constructor Injection ----------
    public RepaymentScheduleService(RepaymentScheduleRepository repaymentScheduleRepository, LoanRepository loanRepository) {
        this.repaymentScheduleRepository = repaymentScheduleRepository;
        this.loanRepository = loanRepository;

    }


    ///==================== Create LoanRepayment Schedule ====================///

    ///---------- Create A LoanRepayment Schedule Installment For A Loan ----------
    @Transactional
    public RepaymentSchedule createRepaymentSchedule(Long loanId, RepaymentSchedule repaymentSchedule) {

        // Find the parent loan
        if (loanId == null) {
            throw new IllegalArgumentException("Loan is required");
        }
        // Serialize schedule creation for this loan; the database unique constraint
        // remains the final guard if a concurrent writer bypasses this service.
        Loan loan = loanRepository.findByIdForUpdate(loanId).orElseThrow(() -> new LoanNotFoundException("Loan not found with ID: " + loanId));

        validateScheduleIdentity(repaymentSchedule);

        // Prevent duplicate installment numbers for the same loan
        if (repaymentScheduleRepository.existsByLoanIdAndInstallmentNumber(loanId, repaymentSchedule.getInstallmentNumber())) {
            throw new DuplicateRepaymentScheduleException("Installment number " + repaymentSchedule.getInstallmentNumber() + " already exists for Loan ID: " + loanId);

        }

        // Validate installment financial values
        validateInstallmentAmounts(repaymentSchedule);

        // Connect the repayment schedule to the parent loan
        repaymentSchedule.setLoan(loan);

        // New installments always start as PENDING
        repaymentSchedule.setInstallmentStatus(RepaymentSchedule.InstallmentStatus.PENDING);

        // No amount has been paid when the installment is created
        repaymentSchedule.setPaidAmount(BigDecimal.ZERO);

        // Initially the full installment amount remains payable
        repaymentSchedule.setRemainingAmount(repaymentSchedule.getTotalInstallmentAmount());

        // Payment date exists only after full payment
        repaymentSchedule.setPaymentDate(null);

        return repaymentScheduleRepository.save(repaymentSchedule);

    }


    ///==================== Read LoanRepayment Schedule ====================///

    ///---------- Get All LoanRepayment Schedules ----------
    public List<RepaymentSchedule> getAllRepaymentSchedulesList() {

        return repaymentScheduleRepository.findAll();

    }


    ///---------- Get LoanRepayment Schedule By Database ID (id) ----------
    public RepaymentSchedule getRepaymentScheduleById(Long id) {

        return repaymentScheduleRepository.findById(id).orElseThrow(() -> new RepaymentScheduleNotFoundException("LoanRepayment schedule not found with ID: " + id));

    }


    ///---------- Get All LoanRepayment Schedules For A Loan ----------
    public List<RepaymentSchedule> getRepaymentSchedulesListByLoanId(Long loanId) {

        return repaymentScheduleRepository.findByLoanIdOrderByInstallmentNumberAsc(loanId);

    }


    ///---------- Get A Specific Installment For A Loan ----------
    public RepaymentSchedule getRepaymentScheduleByLoanAndInstallmentNumber(Long loanId, Integer installmentNumber) {

        return repaymentScheduleRepository.findByLoanIdAndInstallmentNumber(loanId, installmentNumber).orElseThrow(() -> new RepaymentScheduleNotFoundException("Installment number " + installmentNumber + " not found for Loan ID: " + loanId));

    }


    ///---------- Get LoanRepayment Schedules By Installment Status ----------
    public List<RepaymentSchedule> getRepaymentSchedulesListByStatus(RepaymentSchedule.InstallmentStatus installmentStatus) {

        return repaymentScheduleRepository.findByInstallmentStatusOrderByDueDateAsc(installmentStatus);

    }


    ///---------- Get LoanRepayment Schedules For A Loan By Installment Status ----------
    public List<RepaymentSchedule> getLoanRepaymentSchedulesListByStatus(Long loanId, RepaymentSchedule.InstallmentStatus installmentStatus) {

        return repaymentScheduleRepository.findByLoanIdAndInstallmentStatusOrderByInstallmentNumberAsc(loanId, installmentStatus);

    }


    ///---------- Get LoanRepayment Schedules With A Specific Due Date ----------
    public List<RepaymentSchedule> getRepaymentSchedulesListByDueDate(LocalDate dueDate) {

        return repaymentScheduleRepository.findByDueDateOrderByInstallmentNumberAsc(dueDate);

    }


    ///---------- Get LoanRepayment Schedules Due Before A Specific Date ----------
    public List<RepaymentSchedule> getRepaymentSchedulesBeforeDate(LocalDate date) {

        return repaymentScheduleRepository.findByDueDateBeforeOrderByDueDateAsc(date);

    }


    ///---------- Get LoanRepayment Schedules Already Marked As OVERDUE Before A Date ----------
    public List<RepaymentSchedule> getOverdueRepaymentSchedulesList(LocalDate date) {

        return repaymentScheduleRepository.findByDueDateBeforeAndInstallmentStatusOrderByDueDateAsc(date, RepaymentSchedule.InstallmentStatus.OVERDUE);

    }


    ///---------- Get All LoanRepayment Schedules Belonging To An Employee ----------
    public List<RepaymentSchedule> getEmployeeRepaymentSchedulesList(String employeeId) {

        return repaymentScheduleRepository.findByLoanEmployeeEmployeeIdOrderByDueDateAsc(employeeId);

    }


    ///---------- Get Employee LoanRepayment Schedules By Installment Status ----------
    public List<RepaymentSchedule> getEmployeeRepaymentSchedulesListByStatus(String employeeId, RepaymentSchedule.InstallmentStatus installmentStatus) {

        return repaymentScheduleRepository.findByLoanEmployeeEmployeeIdAndInstallmentStatusOrderByDueDateAsc(employeeId, installmentStatus);

    }


    ///==================== Update LoanRepayment Schedule ====================///

    ///---------- Update An Unpaid PENDING LoanRepayment Schedule ----------
    @Transactional
    public RepaymentSchedule updateRepaymentSchedule(Long id, RepaymentSchedule updatedSchedule) {

        // Find the existing repayment schedule
        RepaymentSchedule existingSchedule = findScheduleForUpdate(id);

        // Only PENDING installments can be normally updated
        if (existingSchedule.getInstallmentStatus() != RepaymentSchedule.InstallmentStatus.PENDING) {
            throw new IllegalStateException("Only pending repayment schedules can be updated: " + id);

        }

        // Copy only administrator-editable schedule fields
        BeanUtils.copyProperties(updatedSchedule, existingSchedule, "id", "loan", "installmentNumber", "paidAmount", "remainingAmount", "installmentStatus", "paymentDate", "createdAt", "updatedAt");

        validateScheduleIdentity(existingSchedule);
        // Validate updated installment financial values
        validateInstallmentAmounts(existingSchedule);

        // A PENDING installment has no recorded payment
        existingSchedule.setPaidAmount(BigDecimal.ZERO);

        // Full installment amount remains outstanding
        existingSchedule.setRemainingAmount(existingSchedule.getTotalInstallmentAmount());

        // PENDING installment cannot have a payment date
        existingSchedule.setPaymentDate(null);

        return repaymentScheduleRepository.save(existingSchedule);

    }


    ///==================== LoanRepayment Schedule Workflow ====================///

    ///---------- Mark An Unpaid Installment As OVERDUE ----------
    @Transactional
    public RepaymentSchedule markAsOverdue(Long id) {

        RepaymentSchedule schedule = findScheduleForUpdate(id);

        // Paid installments cannot become overdue
        if (schedule.getInstallmentStatus() == RepaymentSchedule.InstallmentStatus.PAID) {
            throw new IllegalStateException("Paid installment cannot be marked as overdue: " + id);

        }

        // Cancelled installments cannot become overdue
        if (schedule.getInstallmentStatus() == RepaymentSchedule.InstallmentStatus.CANCELLED) {
            throw new IllegalStateException("Cancelled installment cannot be marked as overdue: " + id);

        }

        // Prevent duplicate OVERDUE transition
        if (schedule.getInstallmentStatus() == RepaymentSchedule.InstallmentStatus.OVERDUE) {
            throw new IllegalStateException("Installment is already overdue: " + id);

        }

        // Due date must already have passed
        if (schedule.getDueDate() == null || !schedule.getDueDate().isBefore(LocalDate.now())) {
            throw new IllegalStateException("Installment due date has not passed yet: " + id);

        }

        // Installment must still have an outstanding amount
        if (schedule.getRemainingAmount() == null || schedule.getRemainingAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Installment has no remaining amount to become overdue: " + id);

        }

        schedule.setInstallmentStatus(RepaymentSchedule.InstallmentStatus.OVERDUE);

        return repaymentScheduleRepository.save(schedule);

    }


    ///---------- Cancel A PENDING LoanRepayment Schedule ----------
    @Transactional
    public RepaymentSchedule cancelRepaymentSchedule(Long id) {

        RepaymentSchedule schedule = findScheduleForUpdate(id);

        // Only untouched PENDING installments can be cancelled
        if (schedule.getInstallmentStatus() != RepaymentSchedule.InstallmentStatus.PENDING) {
            throw new IllegalStateException("Only pending repayment schedules can be cancelled: " + id);

        }

        // LoanRepayment schedules with recorded payments cannot be cancelled
        if (schedule.getPaidAmount() == null || schedule.getPaidAmount().compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalStateException("LoanRepayment schedule with recorded payments cannot be cancelled: " + id);

        }

        schedule.setInstallmentStatus(RepaymentSchedule.InstallmentStatus.CANCELLED);

        return repaymentScheduleRepository.save(schedule);

    }


    ///==================== Helper Methods ====================///

    ///---------- Validate Installment Financial Values ----------
    private void validateInstallmentAmounts(RepaymentSchedule repaymentSchedule) {

        BigDecimal principalAmount = repaymentSchedule.getPrincipalAmount();

        BigDecimal interestAmount = repaymentSchedule.getInterestAmount();

        BigDecimal totalInstallmentAmount = repaymentSchedule.getTotalInstallmentAmount();

        // Required financial values must be available
        if (principalAmount == null || interestAmount == null || totalInstallmentAmount == null) {
            throw new IllegalArgumentException("Principal amount, interest amount, and total installment amount are required");

        }

        validateMoneyPrecision(principalAmount, "Principal amount");
        validateMoneyPrecision(interestAmount, "Interest amount");
        validateMoneyPrecision(totalInstallmentAmount, "Total installment amount");

        // Principal cannot be negative
        if (principalAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Principal amount cannot be negative");

        }

        // Interest cannot be negative
        if (interestAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Interest amount cannot be negative");

        }

        // Total installment amount must be greater than zero
        if (totalInstallmentAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Total installment amount must be greater than zero");

        }

        // Total must equal principal plus interest
        BigDecimal calculatedTotal = principalAmount.add(interestAmount);

        if (calculatedTotal.compareTo(totalInstallmentAmount) != 0) {
            throw new IllegalArgumentException("Total installment amount must equal principal amount plus interest amount");

        }
    }

    private void validateScheduleIdentity(RepaymentSchedule schedule) {
        if (schedule == null || schedule.getInstallmentNumber() == null || schedule.getInstallmentNumber() < 1) {
            throw new IllegalArgumentException("Installment number must be at least 1");
        }
        if (schedule.getDueDate() == null) {
            throw new IllegalArgumentException("Due date is required");
        }
        if (schedule.getDueDate().getYear() < 1 || schedule.getDueDate().getYear() > 9999) {
            throw new IllegalArgumentException("Due date is outside the supported SQL Server date range");
        }
    }

    private void validateMoneyPrecision(BigDecimal amount, String field) {
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(field + " cannot have more than two decimal places");
        }
        int integerDigits = Math.max(0, amount.precision() - amount.scale());
        if (amount.precision() > 20 || integerDigits > 18) {
            throw new IllegalArgumentException(field + " exceeds the supported database precision");
        }
    }

    private RepaymentSchedule findScheduleForUpdate(Long id) {
        return repaymentScheduleRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new RepaymentScheduleNotFoundException("LoanRepayment schedule not found with ID: " + id));
    }
}
