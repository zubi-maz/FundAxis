package com.fundaxis.repository;
import com.fundaxis.entity.Contribution;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.List;
public interface ContributionReportingRepository extends JpaRepository<Contribution, Long> {
    // Aggregate payments and credits independently before joining: multiple payments
    // and allocations must never multiply one another or the original obligation.
    // Keep aggregate arithmetic below precision 38 so SQL Server retains cents.
    String ANNUAL = """
        SELECT e.employee_id AS employeeId, e.full_name AS fullName,
          e.employee_designation AS designation, e.employee_status AS employeeStatus,
          CAST(COALESCE(SUM(CASE WHEN c.contribution_status <> 'CANCELED' THEN c.contribution_amount ELSE 0 END),0) AS DECIMAL(34,2)) AS expected,
          CAST(COALESCE(SUM(p.cash),0) AS DECIMAL(34,2)) AS collected, CAST(COALESCE(SUM(a.credit),0) AS DECIMAL(34,2)) AS creditApplied,
          CAST(COALESCE(SUM(CASE WHEN c.contribution_status <> 'CANCELED' THEN c.contribution_amount ELSE 0 END),0) AS DECIMAL(34,2))
            - CAST(COALESCE(SUM(p.cash),0) AS DECIMAL(34,2)) - CAST(COALESCE(SUM(a.credit),0) AS DECIMAL(34,2)) AS outstanding,
          COUNT(CASE WHEN c.contribution_status <> 'CANCELED' THEN 1 END) AS obligations,
          SUM(CASE WHEN (c.contribution_status = 'CANCELED' AND COALESCE(p.cash,0)+COALESCE(a.credit,0) <> 0)
            OR COALESCE(p.cash,0)+COALESCE(a.credit,0) > c.contribution_amount
            OR COALESCE(p.cash,0) < 0 OR COALESCE(a.credit,0) < 0 THEN 1 ELSE 0 END) AS anomalies
        FROM employees e
        LEFT JOIN contributions c ON c.employee_id=e.id AND c.contribution_month BETWEEN :start AND :end
        LEFT JOIN (SELECT p.contribution_id, CAST(SUM(p.payment_amount) AS DECIMAL(34,2)) AS cash FROM contribution_payments p
          JOIN contributions d ON d.id=p.contribution_id WHERE p.reversed=0 AND d.contribution_month BETWEEN :start AND :end
          GROUP BY p.contribution_id) p ON p.contribution_id=c.id
        LEFT JOIN (SELECT a.contribution_id, CAST(SUM(a.amount) AS DECIMAL(34,2)) AS credit FROM member_credit_allocations a
          JOIN contributions d ON d.id=a.contribution_id WHERE a.reversed=0 AND d.contribution_month BETWEEN :start AND :end
          GROUP BY a.contribution_id) a ON a.contribution_id=c.id
        WHERE (:employeeId='' OR LOWER(e.employee_id) LIKE :employeeId ESCAPE '!')
          AND (:employeeName='' OR LOWER(e.full_name) LIKE :employeeName ESCAPE '!')
        GROUP BY e.employee_id,e.full_name,e.employee_designation,e.employee_status
        """;
    String FILTERED = "SELECT r.* FROM (" + ANNUAL + """
        ) r WHERE (:status='ALL'
          OR (:status='NO_RECORDS' AND r.obligations=0)
          OR (:status='PAID' AND r.obligations>0 AND r.outstanding=0)
          OR (:status='PENDING' AND r.outstanding>0 AND r.collected+r.creditApplied=0)
          OR (:status='PARTIAL' AND r.outstanding>0 AND r.collected+r.creditApplied>0))
        """;

    interface AnnualTotal {
        String getEmployeeId();
        String getFullName();
        String getDesignation();
        String getEmployeeStatus();
        java.math.BigDecimal getExpected();
        java.math.BigDecimal getCollected();
        java.math.BigDecimal getCreditApplied();
        java.math.BigDecimal getOutstanding();
        long getObligations();
        long getAnomalies();
    }

    @Query(value=FILTERED, countQuery="SELECT COUNT(*) FROM (" + FILTERED + ") counted", nativeQuery=true)
    org.springframework.data.domain.Page<AnnualTotal> annualPage(
        @Param("start") LocalDate start, @Param("end") LocalDate end,
        @Param("employeeId") String employeeId, @Param("employeeName") String employeeName,
        @Param("status") String status, org.springframework.data.domain.Pageable pageable);

    @Query(value=FILTERED, nativeQuery=true)
    List<AnnualTotal> annualTotals(@Param("start") LocalDate start, @Param("end") LocalDate end,
        @Param("employeeId") String employeeId, @Param("employeeName") String employeeName,
        @Param("status") String status);

    @Query(value="""
        SELECT c.contribution_month,
          CAST(SUM(CASE WHEN c.contribution_status<>'CANCELED' THEN c.contribution_amount ELSE 0 END) AS DECIMAL(34,2)),
          CAST(SUM(COALESCE(p.cash,0)) AS DECIMAL(34,2)), CAST(SUM(COALESCE(a.credit,0)) AS DECIMAL(34,2)),
          CAST(SUM(CASE WHEN c.contribution_status<>'CANCELED' THEN c.contribution_amount ELSE 0 END) AS DECIMAL(34,2))-CAST(SUM(COALESCE(p.cash,0)) AS DECIMAL(34,2))-CAST(SUM(COALESCE(a.credit,0)) AS DECIMAL(34,2))
        FROM contributions c JOIN employees e ON e.id=c.employee_id
        JOIN (
        """ + FILTERED + """
        ) eligible ON eligible.employeeId=e.employee_id
        LEFT JOIN (SELECT p.contribution_id,CAST(SUM(p.payment_amount) AS DECIMAL(34,2)) cash FROM contribution_payments p
          JOIN contributions d ON d.id=p.contribution_id WHERE p.reversed=0 AND d.contribution_month BETWEEN :start AND :end
          GROUP BY p.contribution_id) p ON p.contribution_id=c.id
        LEFT JOIN (SELECT a.contribution_id,CAST(SUM(a.amount) AS DECIMAL(34,2)) credit FROM member_credit_allocations a
          JOIN contributions d ON d.id=a.contribution_id WHERE a.reversed=0 AND d.contribution_month BETWEEN :start AND :end
          GROUP BY a.contribution_id) a ON a.contribution_id=c.id
        WHERE c.contribution_month BETWEEN :start AND :end GROUP BY c.contribution_month
        """, nativeQuery=true)
    List<Object[]> annualMonths(@Param("start") LocalDate start, @Param("end") LocalDate end,
        @Param("employeeId") String employeeId, @Param("employeeName") String employeeName,
        @Param("status") String status);

    @EntityGraph(attributePaths="employee")
    @Query("select c from Contribution c where c.contributionMonth between :start and :end and "
        + "(:employeeId='' or lower(c.employee.employeeId) like :employeeId escape '!') and "
        + "(:employeeName='' or lower(c.employee.fullName) like :employeeName escape '!') order by c.contributionMonth,c.id")
    List<Contribution> findFilteredPeriod(@Param("start") LocalDate start, @Param("end") LocalDate end,
        @Param("employeeId") String employeeId, @Param("employeeName") String employeeName);
    @EntityGraph(attributePaths = "employee")
    @Query("select c from Contribution c where c.contributionMonth between :start and :end order by c.contributionMonth, c.id")
    List<Contribution> findPeriod(@Param("start") LocalDate start, @Param("end") LocalDate end);
    @EntityGraph(attributePaths = "employee")
    @Query("select c from Contribution c where c.employee.id = :employeePk and c.contributionMonth between :start and :end order by c.contributionMonth, c.id")
    List<Contribution> findMemberPeriod(@Param("employeePk") Long employeePk,
                                        @Param("start") LocalDate start, @Param("end") LocalDate end);
}
