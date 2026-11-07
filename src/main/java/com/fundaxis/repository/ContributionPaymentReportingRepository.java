package com.fundaxis.repository;

import com.fundaxis.entity.ContributionPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ContributionPaymentReportingRepository
        extends JpaRepository<ContributionPayment, Long> {

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths="contribution")
    @Query("SELECT p FROM ContributionPayment p "
            + "WHERE p.contribution.id IN :ids")
    List<ContributionPayment> findPaymentsForContributions(
            @Param("ids") Collection<Long> ids);
}