package com.fundaxis.repository;

import com.fundaxis.entity.RepaymentSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface RepaymentScheduleRepository extends JpaRepository<RepaymentSchedule, Long> {

    /** Serializes all repayment pipelines that update the same installment balance. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RepaymentSchedule s where s.id = :id")
    Optional<RepaymentSchedule> findByIdForUpdate(@Param("id") Long id);


    ///==================== Read LoanRepayment Schedule ====================///

    ///---------- Get All LoanRepayment Schedules For A Specific Loan ----------
    List<RepaymentSchedule> findByLoanIdOrderByInstallmentNumberAsc(Long loanId);


    ///---------- Get A Specific Installment For A Specific Loan ----------
    Optional<RepaymentSchedule> findByLoanIdAndInstallmentNumber(Long loanId, Integer installmentNumber);


    ///---------- Get LoanRepayment Schedules With A Specific Installment Status ----------
    List<RepaymentSchedule> findByInstallmentStatusOrderByDueDateAsc(RepaymentSchedule.InstallmentStatus installmentStatus);


    ///---------- Get LoanRepayment Schedules For A Loan With A Specific Status ----------
    List<RepaymentSchedule> findByLoanIdAndInstallmentStatusOrderByInstallmentNumberAsc(Long loanId, RepaymentSchedule.InstallmentStatus installmentStatus);


    ///---------- Get LoanRepayment Schedules With A Specific Due Date ----------
    List<RepaymentSchedule> findByDueDateOrderByInstallmentNumberAsc(LocalDate dueDate);


    ///---------- Get LoanRepayment Schedules Due Before A Specific Date ----------
    List<RepaymentSchedule> findByDueDateBeforeOrderByDueDateAsc(LocalDate date);


    ///---------- Get Overdue LoanRepayment Schedules Before A Specific Date ----------
    List<RepaymentSchedule> findByDueDateBeforeAndInstallmentStatusOrderByDueDateAsc(LocalDate date, RepaymentSchedule.InstallmentStatus installmentStatus);


    ///---------- Get All LoanRepayment Schedules Belonging To An Employee's Loans ----------
    List<RepaymentSchedule> findByLoanEmployeeEmployeeIdOrderByDueDateAsc(String employeeId);


    ///---------- Get An Employee's LoanRepayment Schedules With A Specific Status ----------
    List<RepaymentSchedule> findByLoanEmployeeEmployeeIdAndInstallmentStatusOrderByDueDateAsc(String employeeId, RepaymentSchedule.InstallmentStatus installmentStatus);


    ///==================== Check LoanRepayment Schedule ====================///

    ///---------- Check Whether The Loan Already Has The Given Installment Number ----------
    boolean existsByLoanIdAndInstallmentNumber(Long loanId, Integer installmentNumber);
}
