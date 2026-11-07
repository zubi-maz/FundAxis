package com.fundaxis.repository;

import com.fundaxis.entity.ShareTransaction;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Collection;
import java.util.Optional;

///========== ShareTransaction Repository ==========///
// Data-access layer for ShareTransaction entities.
// Extends JpaRepository for basic CRUD and provides
// audit trail and transaction history query methods.
@Repository
public interface ShareTransactionRepository extends JpaRepository<ShareTransaction, Long> {

    ///========== Full History Queries ==========///

    // Returns every transaction for the given holding, eagerly fetching the share holding,
    // ordered by effective date then id (oldest first).
    @EntityGraph(attributePaths = "shareHolding")
    List<ShareTransaction> findByShareHoldingIdOrderByEffectiveDateAscIdAsc(Long holdingId);

    @EntityGraph(attributePaths = "shareHolding")
    @Query("select t from ShareTransaction t join t.shareHolding h where h.employee.employeeId = :employeeId order by h.shareCategory, t.effectiveDate, t.id")
    List<ShareTransaction> findHistoryByEmployeeId(@Param("employeeId") String employeeId);

    ///========== Recent & Point-in-Time Lookups ==========///

    // Returns the latest transaction for the given holding
    // (newest effective date, then highest id as tie-breaker), eagerly fetching the share holding.
    @EntityGraph(attributePaths = "shareHolding")
    Optional<ShareTransaction> findTopByShareHoldingIdOrderByEffectiveDateDescIdDesc(Long holdingId);

    // Finds the most recent transaction for a holding up to a specific effective date (inclusive),
    // eagerly fetching the share holding.
    @EntityGraph(attributePaths = "shareHolding")
    Optional<ShareTransaction> findTopByShareHoldingIdAndEffectiveDateLessThanEqualOrderByEffectiveDateDescIdDesc(
            Long holdingId, LocalDate effectiveDate);

    @EntityGraph(attributePaths = "shareHolding")
    Optional<ShareTransaction> findTopByShareHoldingIdAndTransactionTypeInOrderByEffectiveDateDescIdDesc(
            Long holdingId, Collection<ShareTransaction.ShareTransactionType> transactionTypes);

    @EntityGraph(attributePaths = "shareHolding")
    Optional<ShareTransaction> findTopByShareHoldingIdAndTransactionTypeInAndEffectiveDateLessThanEqualOrderByEffectiveDateDescIdDesc(
            Long holdingId, Collection<ShareTransaction.ShareTransactionType> transactionTypes,
            LocalDate effectiveDate);
}
