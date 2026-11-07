package com.fundaxis.repository;

import com.fundaxis.entity.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    Page<Employee> findByFullNameContainingIgnoreCase(String fullName, Pageable pageable);

    // Find employee using official NBL Employee ID (employeeId)
    Optional<Employee> findByEmployeeId(String employeeId);

    // Exact matching remains the first choice for administrator lookup.
    Optional<Employee> findByEmployeeIdIgnoreCase(String employeeId);

    // Used only for numeric-only business-ID suffix searches (for example 1476).
    Page<Employee> findByEmployeeIdEndingWithIgnoreCase(String suffix, Pageable pageable);

    // Check duplicate Employee ID (employeeId) during creation
    boolean existsByEmployeeId(String employeeId);

    // Get employees by status
    List<Employee> findByEmployeeStatus(Employee.EmployeeStatus employeeStatus);

    // Find employees who joined the fund on a specific date
    List<Employee> findByFundJoinDate(LocalDate fundJoinDate);

    // Find employees who joined the fund within a date range
    List<Employee> findByFundJoinDateBetween(LocalDate startDate, LocalDate endDate);

    // Find employees without a recorded fund exit date
    List<Employee> findByFundExitDateIsNull();

    // Finds all employees with the specified designation enum
    List<Employee> findByEmployeeDesignation(Employee.EmployeeDesignation employeeDesignation);

    // Paginated method for fetching all employees
    @Query(value = "SELECT e FROM Employee e",
            countQuery = "SELECT COUNT(e) FROM Employee e")
    Page<Employee> findAllPaginatedEmployees(Pageable pageable);
    

}
