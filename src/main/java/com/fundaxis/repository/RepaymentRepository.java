package com.fundaxis.repository;

import com.fundaxis.entity.Repayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;


@Repository
public interface RepaymentRepository extends JpaRepository<Repayment, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Repayment r where r.id = :id")
    Optional<Repayment> findByIdForUpdate(@Param("id") Long id);


    // ==================== Reference Number ==================== //

    ///---------- Check If Reference Number Already Exists ----------
    boolean existsByReferenceNumberIgnoreCase(
            String referenceNumber
    );


    ///---------- Find Repayment By Reference Number ----------
    Optional<Repayment> findByReferenceNumberIgnoreCase(
            String referenceNumber
    );


    // ==================== All Repayments ==================== //

    ///---------- Get All Repayments Ordered By Newest First ----------
    List<Repayment> findAllByOrderByRepaymentDateDescCreatedAtDesc();


    // ==================== Repayments By Schedule ==================== //

    ///---------- Get Repayments For One Repayment Schedule ----------
    List<Repayment>
    findByRepaymentScheduleIdOrderByRepaymentDateDescCreatedAtDesc(
            Long repaymentScheduleId
    );


    // ==================== Repayments By Loan ==================== //

    ///---------- Get Repayments For One Loan ----------
    List<Repayment>
    findByRepaymentScheduleLoanIdOrderByRepaymentDateDescCreatedAtDesc(
            Long loanId
    );


    // ==================== Repayments By Employee ==================== //

    ///---------- Get Repayments For One Employee ----------
    List<Repayment>
    findByRepaymentScheduleLoanEmployeeEmployeeIdOrderByRepaymentDateDescCreatedAtDesc(
            String employeeId
    );


    // ==================== Repayments By Status ==================== //

    ///---------- Get Repayments By Repayment Status ----------
    List<Repayment>
    findByRepaymentStatusOrderByRepaymentDateDescCreatedAtDesc(
            Repayment.RepaymentStatus repaymentStatus
    );


    // ==================== Repayments By Payment Method ==================== //

    ///---------- Get Repayments By Payment Method ----------
    List<Repayment>
    findByPaymentMethodOrderByRepaymentDateDescCreatedAtDesc(
            Repayment.PaymentMethod paymentMethod
    );


    // ==================== Totals ==================== //

    ///---------- Total COMPLETED Repayment Amount For One Schedule ----------
    @Query(
            value = """
                    SELECT COALESCE(SUM(r.repayment_amount), 0)
                    FROM repayments r
                    WHERE r.repayment_schedule_id = :repaymentScheduleId
                      AND r.repayment_status = 'COMPLETED'
                    """,
            nativeQuery = true
    )
    BigDecimal sumCompletedRepaymentAmountByScheduleId(
            @Param("repaymentScheduleId")
            Long repaymentScheduleId
    );


    ///---------- Total COMPLETED Repayment Amount Across The System ----------
    @Query(
            value = """
                    SELECT COALESCE(SUM(r.repayment_amount), 0)
                    FROM repayments r
                    WHERE r.repayment_status = 'COMPLETED'
                    """,
            nativeQuery = true
    )
    BigDecimal sumCompletedRepaymentAmount();
}
