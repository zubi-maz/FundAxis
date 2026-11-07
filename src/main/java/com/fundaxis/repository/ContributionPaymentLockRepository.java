package com.fundaxis.repository;

import com.fundaxis.entity.Contribution;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface ContributionPaymentLockRepository extends JpaRepository<Contribution, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Contribution c where c.id = :id")
    Optional<Contribution> lockById(@Param("id") Long id);
}
