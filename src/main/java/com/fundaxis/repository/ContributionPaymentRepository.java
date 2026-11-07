package com.fundaxis.repository;

import com.fundaxis.entity.ContributionPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface ContributionPaymentRepository extends JpaRepository<ContributionPayment, Long> {

    List<ContributionPayment> findByContributionIdOrderByIdAsc(Long contributionId);

    // Find all payments belonging to a batch of contribution IDs
    List<ContributionPayment> findByContributionIdIn(List<Long> contributionIds);

    boolean existsByContributionIdAndReversedFalse(Long contributionId);

    @Query("select coalesce(sum(p.paymentAmount), 0) from ContributionPayment p " +
            "where p.contribution.id = :contributionId and p.reversed = false")
    BigDecimal sumActivePayments(@Param("contributionId") Long contributionId);
}