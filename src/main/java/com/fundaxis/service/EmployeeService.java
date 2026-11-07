package com.fundaxis.service;

import com.fundaxis.entity.Employee;
import com.fundaxis.repository.EmployeeRepository;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fundaxis.exception.EmployeeNotFoundException;
import com.fundaxis.exception.DuplicateEmployeeException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class EmployeeService {


    ///==================== Constructor Dependency Injection ====================///

    private final EmployeeRepository employeeRepository;

    // EmployeeService needs EmployeeRepository to communicate with the database
    public EmployeeService(EmployeeRepository employeeRepository) {
        this.employeeRepository = employeeRepository;
    }


    ///==================== Deactivate Employee ====================///

    // business operations are intentionally using the official employee ID
    @Transactional
    public Employee deactivateEmployee(String employeeId, String reasonOfDeactivation) {

        // Find employee using official Employee ID
        Employee existingEmployee = employeeRepository.findByEmployeeId(employeeId)
                .orElseThrow(() -> new EmployeeNotFoundException("Employee with Employee ID: " + employeeId + " does not exist"));


        // Only active employees can be deactivated
        if (existingEmployee.getEmployeeStatus() != Employee.EmployeeStatus.ACTIVE){
            throw new IllegalStateException("Only active employees can be deactivated: " + employeeId);

        }

        // Mark the employee as inactive
        existingEmployee.setEmployeeStatus(Employee.EmployeeStatus.INACTIVE);

        // Store the reason for deactivation
        existingEmployee.setDeactivationReason(reasonOfDeactivation);

        // Store the deactivation date and time
        existingEmployee.setDeactivatedAt(LocalDateTime.now());

        // Save the updated employee
        return employeeRepository.save(existingEmployee);
    }


    ///==================== Activate Employee ====================///

    // business operations are intentionally using the official employee ID
    @Transactional
    public Employee activateEmployee(String employeeId) {

        // Find employee using official Employee ID
        Employee existingEmployee = employeeRepository.findByEmployeeId(employeeId)
                .orElseThrow(() -> new EmployeeNotFoundException("Employee with Employee ID: " + employeeId + " does not exist"));


        // Only inactive employees can be activated
        if (existingEmployee.getEmployeeStatus() != Employee.EmployeeStatus.INACTIVE){
            throw new IllegalStateException("Only inactive employees can be activated: " + employeeId);

        }

        // Change employee status to active
        existingEmployee.setEmployeeStatus(Employee.EmployeeStatus.ACTIVE);

        // Remove old deactivation information
        existingEmployee.setDeactivationReason(null);
        existingEmployee.setDeactivatedAt(null);

        // Save the updated employee
        return employeeRepository.save(existingEmployee);

    }


    ///==================== Employee List ====================///


    ///---------- Get All Employees ----------///

    public List<Employee> getAllEmployeeList() {

        return employeeRepository.findAll();
    }


    ///---------- Get Active Employees ----------///

    public List<Employee> getAllActiveEmployeeList() {

        return employeeRepository.findByEmployeeStatus(Employee.EmployeeStatus.ACTIVE);
    }


    ///---------- Get Inactive Employees ----------///

    public List<Employee> getAllInactiveEmployeeList() {

        return employeeRepository.findByEmployeeStatus(Employee.EmployeeStatus.INACTIVE);
    }


    ///---------- Read - By Employee Status ----------
    public List<Employee> getAllEmployeeByEmployeeStatus(Employee.EmployeeStatus employeeStatus){

        return employeeRepository.findByEmployeeStatus(employeeStatus);
    }


    ///---------- Find employees who joined the fund on a specific date ----------
    public List<Employee> getEmployeesByJoinDate(LocalDate fundJoinDate) {

        // Keep IllegalArgumentException (or handle null) for invalid input parameters
        if (fundJoinDate == null) {
            throw new IllegalArgumentException("Join date cannot be null");
        }

        // Retrieve employees who joined the fund on the specified date
        List<Employee> joinDateEmployees = employeeRepository.findByFundJoinDate(fundJoinDate);

        // Throw custom EmployeeNotFoundException if the list is empty
        if (joinDateEmployees.isEmpty()) {
            throw new EmployeeNotFoundException("No employees found with fund join date: " + fundJoinDate);
        }

        return joinDateEmployees;

    }


    ///---------- Find employees who joined the fund within a date range (Start & end date) ----------
    public List<Employee> getEmployeesByJoinDateRange(LocalDate startDate, LocalDate endDate) {

        // the start date must be earlier than or equal to the end date
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("Start date and end date must be provided");
        }
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("Start date cannot be after end date");
        }

        // Retrieve employees who joined the fund within the specified date range
        // Returns an empty list gracefully if no records match, preventing export/view errors
        return employeeRepository.findByFundJoinDateBetween(startDate, endDate);
    }



    ///---------- Find all employees with the specified designation ----------
    public List<Employee> getEmployeesByDesignation(Employee.EmployeeDesignation employeeDesignation){

        if(employeeDesignation == null){
            throw new IllegalArgumentException("Designation cannot be null");
        }

        List<Employee> employeesDesignation = employeeRepository.findByEmployeeDesignation(employeeDesignation);

        if (employeesDesignation.isEmpty()) {
            throw new EmployeeNotFoundException("No employees found with designation: " + employeeDesignation);
        }

        return employeesDesignation;
    }



    ///==================== Create Employee ====================///

    @Transactional
    public Employee createEmployee(Employee employee) {

        // Check employee and Employee ID
        if (employee == null || employee.getEmployeeId() == null || employee.getEmployeeId().trim().isEmpty()) {
            throw new IllegalArgumentException("Employee ID is required");
        }

        // Remove extra spaces from Employee ID
        String employeeId = employee.getEmployeeId().trim();
        employee.setEmployeeId(employeeId);

        // Check if Employee ID already exists
        if (employeeRepository.existsByEmployeeId(employeeId)) {
            throw new DuplicateEmployeeException("Employee ID already exists: " + employeeId);
        }

        // Set new employee status to ACTIVE
        employee.setEmployeeStatus(Employee.EmployeeStatus.ACTIVE);

        // Save employee to the database
        return employeeRepository.save(employee);
    }


    ///---------- Find Employee by Database ID ----------///

    public Employee findEmployeeById(Long id) {

        // Find employee using database ID
        // Throw an error if the employee does not exist
        return employeeRepository.findById(id)
                .orElseThrow(() -> new EmployeeNotFoundException("Employee with ID: " + id + " does not exist"));

    }


    ///---------- Find Employee by Official Employee ID ----------///

    public Employee findEmployeeByEmployeeId(String employeeId) {

        // Find employee using official Employee ID
        // Throw an error if the employee does not exist
        return employeeRepository.findByEmployeeId(employeeId)
                .orElseThrow(() -> new EmployeeNotFoundException("Employee with Employee ID: " + employeeId + " does not exist"));

    }


    ///---------- Update Employee ----------///

    @Transactional
    public Employee updateEmployee(String employeeId, Employee updatedEmployee) {

        // Validate updated employee data
        if (updatedEmployee == null) {
            throw new IllegalArgumentException("Employee data is required for update");
        }

        // Find existing employee using official Employee ID
        Employee existingEmployee = employeeRepository.findByEmployeeId(employeeId)
                .orElseThrow(() -> new EmployeeNotFoundException("Employee with Employee ID: " + employeeId + " does not exist"));


        // Update only editable employee fields
        // Keep database, status, audit, and deactivation fields unchanged
        BeanUtils.copyProperties(
                updatedEmployee,
                existingEmployee,
                "id",                   // Keep primary key database id protected
                "createdAt",                            // Keep creation date protected
                "updatedAt",                            // Managed automatically or protected
                "deactivationReason",
                "deactivatedAt"
        );

        // Step 3: Save updated employee
        return employeeRepository.save(existingEmployee);
    }

    // Get paginated employee list (10 per page)
    public Page<Employee> getPaginatedEmployees(int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return employeeRepository.findAllPaginatedEmployees(pageable);
    }

    // ==================== Create Bulk Employees ====================

    @Transactional
    public int createBulkEmployees(List<Employee> employees) {
        // Counter to track how many employees are successfully saved
        int savedCount = 0;

        // Return 0 immediately if the input list is null or empty
        if (employees == null || employees.isEmpty()) {
            return 0;
        }

        // Loop through each employee record in the list
        for (Employee employee : employees) {
            // Skip the current row if the Employee ID is blank or null
            if (employee.getEmployeeId() == null || employee.getEmployeeId().trim().isEmpty()) {
                continue;
            }

            // Clean up any extra whitespace from the Employee ID
            String employeeId = employee.getEmployeeId().trim();

            // Skip saving if this Employee ID already exists in the database to prevent duplicate key crashes
            if (employeeRepository.existsByEmployeeId(employeeId)) {
                continue;
            }

            // Set the cleaned employee ID back to the object
            employee.setEmployeeId(employeeId);

            // New employees always start with an ACTIVE status by default
            employee.setEmployeeStatus(Employee.EmployeeStatus.ACTIVE);

            // Save the employee record to the database
            employeeRepository.save(employee);

            // Increment the successful save count
            savedCount++;
        }

        // Return the total number of successfully inserted employees
        return savedCount;
    }

    // ==================== Bulk Save Employees from Excel ====================

    @Transactional
    public int createEmployeesFromExcel(List<Employee> employees) {
        // Counter to track how many employees are successfully saved
        int savedCount = 0;

        // Loop through each employee record in the parsed list
        for (Employee employee : employees) {
            // Check if Employee ID is valid (not null/empty) and does not already exist in the database
            if (employee.getEmployeeId() != null
                    && !employee.getEmployeeId().trim().isEmpty()
                    && !employeeRepository.existsByEmployeeId(employee.getEmployeeId().trim())) {

                // Clean up any extra whitespace from the employee ID
                employee.setEmployeeId(employee.getEmployeeId().trim());

                // Set default status to ACTIVE for newly imported records
                employee.setEmployeeStatus(Employee.EmployeeStatus.ACTIVE);

                // Save the valid employee record to the database
                employeeRepository.save(employee);

                // Increment the successful save counter
                savedCount++;
            }
        }

        // Return the total number of newly saved employees
        return savedCount;
    }

}