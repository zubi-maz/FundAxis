
package com.fundaxis.repository;

import com.fundaxis.entity.MemberCredit;
import com.fundaxis.entity.MemberCredit.CreditStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

@Repository
public interface MemberCreditRepository extends JpaRepository<MemberCredit, Long> {

    List<MemberCredit> findTop3ByEmployeeEmployeeIdOrderByIdDesc(String employeeId);

    @Query("select coalesce(sum(c.availableAmount), 0) from MemberCredit c "
            + "where c.employee.employeeId = :employeeId and c.status in :statuses")
    java.math.BigDecimal sumAvailable(@Param("employeeId") String employeeId,
            @Param("statuses") List<CreditStatus> statuses);

    // All additional collections for an official Employee ID.
    // Most recent records appear first.
    List<MemberCredit> findByEmployeeEmployeeIdOrderByIdDesc(
            String employeeId
    );

    // Unallocated credits available for future classification.
    List<MemberCredit> findByEmployeeEmployeeIdAndStatusOrderByIdAsc(
            String employeeId,
            CreditStatus status
    );

    // Lock a credit before applying or reversing it.
    // Must be called inside a @Transactional service method.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT mc FROM MemberCredit mc WHERE mc.id = :id")
    Optional<MemberCredit> findByIdForUpdate(
            @Param("id") Long id
    );
}
