package com.fundaxis.repository;

import com.fundaxis.entity.LoanDisbursement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

@Repository
public interface LoanDisbursementRepository extends JpaRepository<LoanDisbursement, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from LoanDisbursement d where d.id = :id")
    Optional<LoanDisbursement> findByIdForUpdate(@Param("id") Long id);


    ///==================== Read Loan Disbursement ====================///

    ///---------- Get All Disbursements For A Specific Loan ----------
    List<LoanDisbursement> findByLoanIdOrderByCreatedAtDesc(Long loanId);


    ///---------- Get All Disbursements For An Employee's Loans ----------
    List<LoanDisbursement> findByLoanEmployeeEmployeeIdOrderByCreatedAtDesc(String employeeId);


    ///---------- Get All Disbursements With A Specific Status ----------
    List<LoanDisbursement> findByDisbursementStatusOrderByCreatedAtDesc(LoanDisbursement.DisbursementStatus disbursementStatus);


    ///---------- Get All Disbursements Using A Specific Payment Method ----------
    List<LoanDisbursement> findByPaymentMethodOrderByCreatedAtDesc(LoanDisbursement.PaymentMethod paymentMethod);


    ///---------- Find A Disbursement Using Its Unique Transaction Reference ----------
    Optional<LoanDisbursement> findByReferenceNumber(String referenceNumber);


    ///---------- Get An Employee's Disbursements With A Specific Status ----------
    List<LoanDisbursement> findByLoanEmployeeEmployeeIdAndDisbursementStatusOrderByCreatedAtDesc(String employeeId, LoanDisbursement.DisbursementStatus disbursementStatus);


    ///---------- Get A Loan's Disbursements With A Specific Status ----------
    List<LoanDisbursement> findByLoanIdAndDisbursementStatusOrderByCreatedAtDesc(Long loanId, LoanDisbursement.DisbursementStatus disbursementStatus);


    ///==================== Check Loan Disbursement ====================///

    ///---------- Check Whether A Transaction Reference Already Exists ----------
    boolean existsByReferenceNumber(String referenceNumber);


    ///---------- Check Whether A Loan Already Has A Disbursement With The Given Status ----------
    boolean existsByLoanIdAndDisbursementStatus(Long loanId, LoanDisbursement.DisbursementStatus disbursementStatus);
}
