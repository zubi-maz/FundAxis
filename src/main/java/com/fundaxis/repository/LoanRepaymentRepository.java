package com.fundaxis.repository;

import com.fundaxis.entity.LoanRepayment;
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
public interface LoanRepaymentRepository extends JpaRepository<LoanRepayment, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from LoanRepayment r where r.id = :id")
    Optional<LoanRepayment> findByIdForUpdate(@Param("id") Long id);


    ///==================== Read Loan Repayment ====================///

    ///---------- Find All Repayment Transactions For One Installment ----------
    List<LoanRepayment> findByRepaymentScheduleIdOrderByCreatedAtDesc(Long repaymentScheduleId);


    ///---------- Find A Repayment Using Its Unique Transaction Reference ----------
    Optional<LoanRepayment> findByReferenceNumber(String referenceNumber);


    ///---------- Find Repayments By Processing Status ----------
    List<LoanRepayment> findByRepaymentStatusOrderByCreatedAtDesc(LoanRepayment.RepaymentStatus repaymentStatus);


    ///---------- Find Repayments By Payment Method ----------
    List<LoanRepayment> findByPaymentMethodOrderByCreatedAtDesc(LoanRepayment.PaymentMethod paymentMethod);


    ///---------- Find Repayments Made On A Specific Date ----------
    List<LoanRepayment> findByRepaymentDateOrderByCreatedAtDesc(LocalDate repaymentDate);


    ///---------- Find All Repayment Transactions Belonging To One Loan ----------
    List<LoanRepayment> findByRepaymentScheduleLoanIdOrderByCreatedAtDesc(Long loanId);


    ///---------- Find All Repayment Transactions Belonging To One Employee ----------
    List<LoanRepayment> findByRepaymentScheduleLoanEmployeeEmployeeIdOrderByCreatedAtDesc(String employeeId);


    ///---------- Find An Employee's Repayment Transactions By Status ----------
    List<LoanRepayment> findByRepaymentScheduleLoanEmployeeEmployeeIdAndRepaymentStatusOrderByCreatedAtDesc(String employeeId, LoanRepayment.RepaymentStatus repaymentStatus);


    ///---------- Find Repayment Transactions For One Installment By Status ----------
    List<LoanRepayment> findByRepaymentScheduleIdAndRepaymentStatusOrderByCreatedAtDesc(Long repaymentScheduleId, LoanRepayment.RepaymentStatus repaymentStatus);


    ///==================== Check Loan Repayment ====================///

    ///---------- Check Whether A Repayment Reference Already Exists ----------
    boolean existsByReferenceNumber(String referenceNumber);


    ///---------- Check Whether An Installment Already Has A Repayment In A Specific Status ----------
    boolean existsByRepaymentScheduleIdAndRepaymentStatus(Long repaymentScheduleId, LoanRepayment.RepaymentStatus repaymentStatus);
}
