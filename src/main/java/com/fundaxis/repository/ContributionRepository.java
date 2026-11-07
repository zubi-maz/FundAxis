
package com.fundaxis.repository;

import com.fundaxis.entity.Contribution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface ContributionRepository extends JpaRepository<Contribution, Long> {

    List<Contribution> findByEmployeeEmployeeIdAndContributionMonth(String employeeId, LocalDate contributionMonth);

    @Query(value="""
        select c from Contribution c join fetch c.employee e
        where c.contributionMonth = :month
          and (:employeeId = '' or e.employeeId = :employeeId)
          and lower(e.fullName) like :namePattern escape '!'
          and (:status is null or c.contributionStatus = :status)
        """, countQuery="""
        select count(c) from Contribution c join c.employee e
        where c.contributionMonth = :month
          and (:employeeId = '' or e.employeeId = :employeeId)
          and lower(e.fullName) like :namePattern escape '!'
          and (:status is null or c.contributionStatus = :status)
        """)
    org.springframework.data.domain.Page<Contribution> findOperations(
            @Param("month") LocalDate month, @Param("employeeId") String employeeId,
            @Param("namePattern") String namePattern,
            @Param("status") Contribution.ContributionStatus status,
            org.springframework.data.domain.Pageable pageable);

    // Find all contributions belonging to a specific employee
    List<Contribution> findByEmployeeEmployeeId(String employeeId);

    // Find all contributions for a specific month, including employee details
    @Query("""
        SELECT c
        FROM Contribution c
        JOIN FETCH c.employee
        WHERE c.contributionMonth = :month
        ORDER BY c.employee.employeeId ASC
        """)
    List<Contribution> findByContributionMonth(
            @Param("month") LocalDate month
    );

    // Find contributions across a date range for the monthly register
    @Query("""
        SELECT c
        FROM Contribution c
        JOIN FETCH c.employee
        WHERE c.contributionMonth BETWEEN :startMonth AND :endMonth
        ORDER BY c.employee.employeeId ASC, c.contributionMonth ASC
        """)
    List<Contribution> findForMonthlyRegister(
            @Param("startMonth") LocalDate startMonth,
            @Param("endMonth") LocalDate endMonth
    );

    // Check whether an employee already has a contribution for this month
    boolean existsByEmployeeEmployeeIdAndContributionMonth(
            String employeeId,
            LocalDate contributionMonth
    );

    // Check whether another contribution already uses this month
    boolean existsByEmployeeEmployeeIdAndContributionMonthAndIdNot(
            String employeeId,
            LocalDate contributionMonth,
            Long id
    );


}
