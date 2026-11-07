package com.fundaxis.repository;

import com.fundaxis.entity.ShareRateHistory;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

///========== Share Rate History Repository ==========///
// Provides data access operations for ShareRateHistory entities,
// including custom queries, entity graphing, and concurrency locking.
@Repository
public interface ShareRateHistoryRepository extends JpaRepository<ShareRateHistory, Long> {

    ///========== Custom Find Queries ==========///

    // Retrieves all rate history records for a specific share holding, fetching the related holding eagerly
    @EntityGraph(attributePaths = "shareHolding")
    List<ShareRateHistory> findByShareHoldingId(Long shareHoldingId);

    // Finds a specific rate history record by holding and effective date
    @EntityGraph(attributePaths = "shareHolding")
    Optional<ShareRateHistory> findByShareHoldingIdAndEffectiveDate(Long shareHoldingId, LocalDate effectiveDate);

    ///========== Overridden & Locked Queries ==========///

    // Overrides default findById to fetch the related share holding eagerly
    @Override
    @EntityGraph(attributePaths = "shareHolding")
    Optional<ShareRateHistory> findById(Long id);

    // Retrieves a rate history record with a pessimistic write lock for concurrent update safety
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = "shareHolding")
    Optional<ShareRateHistory> findWithLockById(Long id);


    ///========== Share Rate History Query Methods ==========///

    // Finds the most recent share rate history record for a holding up to a specific effective date (inclusive)
    Optional<ShareRateHistory> findTopByShareHoldingIdAndEffectiveDateLessThanEqualOrderByEffectiveDateDescIdDesc(
            Long holdingId, LocalDate effectiveDate);

    // Finds the latest overall share rate history record for a given holding
    Optional<ShareRateHistory> findTopByShareHoldingIdOrderByEffectiveDateDescIdDesc(Long holdingId);

    // Checks if a rate history entry already exists for a specific holding and effective date
    boolean existsByShareHoldingIdAndEffectiveDate(Long holdingId, LocalDate effectiveDate);

    // Retrieves all rate history records for a holding ordered chronologically (oldest to newest)
    List<ShareRateHistory> findByShareHoldingIdOrderByEffectiveDateAscIdAsc(Long holdingId);

    @EntityGraph(attributePaths = "shareHolding")
    @Query("select r from ShareRateHistory r join r.shareHolding h where h.employee.employeeId = :employeeId order by h.shareCategory, r.effectiveDate, r.id")
    List<ShareRateHistory> findHistoryByEmployeeId(@Param("employeeId") String employeeId);


}
