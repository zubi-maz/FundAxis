package com.fundaxis.repository;

import com.fundaxis.entity.Contribution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface MonthlyContributionGenerationRepository extends JpaRepository<Contribution, Long> {
    @Query("select c from Contribution c where c.employee.id = :employeePk and c.contributionMonth = :month")
    Optional<Contribution> findByEmployeeIdAndContributionMonth(
            @Param("employeePk") Long employeeId, @Param("month") LocalDate contributionMonth);
}
