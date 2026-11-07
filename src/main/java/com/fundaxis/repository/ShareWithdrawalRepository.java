package com.fundaxis.repository;

import com.fundaxis.entity.ShareWithdrawal;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface ShareWithdrawalRepository extends JpaRepository<ShareWithdrawal, Long>, JpaSpecificationExecutor<ShareWithdrawal> {
    long countByEmployeeEmployeeIdAndStatus(String employeeId, ShareWithdrawal.WithdrawalStatus status);
    @EntityGraph(attributePaths={"employee","shareHolding","shareTransaction"})
    List<ShareWithdrawal> findByEmployeeEmployeeIdOrderByRequestDateDescIdDesc(String employeeId);
    @Override
    @EntityGraph(attributePaths={"employee","shareHolding","shareTransaction"})
    Page<ShareWithdrawal> findAll(Specification<ShareWithdrawal> specification, Pageable pageable);

    @EntityGraph(attributePaths={"employee","shareHolding","shareTransaction"})
    @Query("select w from ShareWithdrawal w where w.id=:id")
    Optional<ShareWithdrawal> details(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from ShareWithdrawal w where w.id=:id")
    Optional<ShareWithdrawal> lock(@Param("id") Long id);

    Optional<ShareWithdrawal> findByRequestToken(String token);

    @Query("select coalesce(sum(w.requestedQuantity),0) from ShareWithdrawal w where w.shareHolding.id=:holding "
        + "and w.sharesPostedAt is null and w.status in (com.fundaxis.entity.ShareWithdrawal.WithdrawalStatus.PENDING,com.fundaxis.entity.ShareWithdrawal.WithdrawalStatus.APPROVED) and w.id<>:exclude")
    long reserved(@Param("holding") Long holding, @Param("exclude") Long exclude);

    @Query("select w.shareHolding.id,sum(w.requestedQuantity) from ShareWithdrawal w where w.employee.employeeId=:employee "
        + "and w.sharesPostedAt is null and w.status in (com.fundaxis.entity.ShareWithdrawal.WithdrawalStatus.PENDING,com.fundaxis.entity.ShareWithdrawal.WithdrawalStatus.APPROVED) group by w.shareHolding.id")
    List<Object[]> employeeReservations(@Param("employee") String employee);

    interface Summary {
        long getPending(); long getApproved(); long getAwaitingPosting();
        long getReserved(); long getUnknownDisbursements(); BigDecimal getDisbursed();
    }
    @Query("select coalesce(sum(case when w.status=com.fundaxis.entity.ShareWithdrawal.WithdrawalStatus.PENDING then 1 else 0 end),0) as pending, "
        + "coalesce(sum(case when w.status=com.fundaxis.entity.ShareWithdrawal.WithdrawalStatus.APPROVED then 1 else 0 end),0) as approved, "
        + "coalesce(sum(case when w.status=com.fundaxis.entity.ShareWithdrawal.WithdrawalStatus.APPROVED and w.sharesPostedAt is null then 1 else 0 end),0) as awaitingPosting, "
        + "coalesce(sum(case when w.sharesPostedAt is null and w.status in (com.fundaxis.entity.ShareWithdrawal.WithdrawalStatus.PENDING,com.fundaxis.entity.ShareWithdrawal.WithdrawalStatus.APPROVED) then w.requestedQuantity else 0 end),0) as reserved, "
        + "coalesce(sum(case when w.status=com.fundaxis.entity.ShareWithdrawal.WithdrawalStatus.DISBURSED and w.netAmount is null then 1 else 0 end),0) as unknownDisbursements, "
        + "sum(case when w.status=com.fundaxis.entity.ShareWithdrawal.WithdrawalStatus.DISBURSED then w.netAmount else 0 end) as disbursed from ShareWithdrawal w")
    Summary summary();
}
