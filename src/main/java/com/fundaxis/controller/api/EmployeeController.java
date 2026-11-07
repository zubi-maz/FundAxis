package com.fundaxis.controller.api;

import com.fundaxis.controller.admin.AdminEmployeeController;
import com.fundaxis.dto.DeactivateRequest;
import com.fundaxis.entity.Employee;
import com.fundaxis.service.EmployeeService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.time.LocalDate;
import java.util.List;

///===========================================================================================///
///============================== @RestController for JSON APIs (REST API) ==============================///
///===========================================================================================///
@RestController
@RequestMapping("/api/employees")
public class EmployeeController {


    private static final Logger log = LoggerFactory.getLogger(AdminEmployeeController.class);


    private final EmployeeService employeeService;

    // Inject EmployeeService into the Controller
    public EmployeeController(EmployeeService employeeService){
        this.employeeService = employeeService;
    }

    ///==================== Deactivate an Employee ====================///

    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @PatchMapping("/{employeeId}/deactivate")
    public ResponseEntity<Employee> deactivateEmployee(@PathVariable String employeeId, @Valid @RequestBody DeactivateRequest deactivateRequest){

        Employee deactivateEmployee = employeeService.deactivateEmployee(employeeId, deactivateRequest.getDeactivateReason());

        return ResponseEntity.ok(deactivateEmployee);

    }

    ///==================== Activate an Employee ====================///

    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @PatchMapping("/{employeeId}/activate")
    public ResponseEntity<Employee> activateEmployee(@PathVariable String employeeId){

        Employee activateEmployee = employeeService.activateEmployee(employeeId);

        return ResponseEntity.ok(activateEmployee);
    }

    ///==================== List ====================///

    // Get all Employee List and return with HTTP 200 OK
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping
    public ResponseEntity<List<Employee>> getAllEmployeeList(){

        return ResponseEntity.ok(employeeService.getAllEmployeeList());
    }


    // Get all Active Employee List and return with HTTP 200 OK
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping("/active")
    public ResponseEntity<List<Employee>> getAllActiveEmployeeList(){

        return ResponseEntity.ok(employeeService.getAllActiveEmployeeList());
    }


    // Get all deactivate Employee List and return with HTTP 200 OK
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping("/inactive")
    public ResponseEntity<List<Employee>> getAllInactiveEmployeeList(){

        return ResponseEntity.ok(employeeService.getAllInactiveEmployeeList());
    }


    ///---------- Get Employees By Status ----------
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping("/status/{employeeStatus}")
    public ResponseEntity<List<Employee>> getAllEmployeeByEmployeeStatus(@PathVariable Employee.EmployeeStatus employeeStatus){

        return ResponseEntity.ok(employeeService.getAllEmployeeByEmployeeStatus(employeeStatus));

    }


    ///---------- Get Employees By Designation ----------
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping("/designation/{designation}")
    public ResponseEntity<List<Employee>> getEmployeesByDesignation(@PathVariable Employee.EmployeeDesignation employeeDesignation) {
        return ResponseEntity.ok(employeeService.getEmployeesByDesignation(employeeDesignation));
    }


    ///---------- Get Employees By Fund Join Date ----------
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping("/join-date")
    public ResponseEntity<List<Employee>> getEmployeesByJoinDate(@RequestParam @org.springframework.format.annotation.DateTimeFormat(pattern = "dd/MM/yyyy") LocalDate fundJoinDate) {
        return ResponseEntity.ok(employeeService.getEmployeesByJoinDate(fundJoinDate));
    }


    ///---------- Get Employees By Fund Join Date Range ----------
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping("/join-date-range")
    public ResponseEntity<List<Employee>> getEmployeesByJoinDateRange(
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(pattern = "dd/MM/yyyy") LocalDate startDate,
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(pattern = "dd/MM/yyyy") LocalDate endDate) {
        return ResponseEntity.ok(employeeService.getEmployeesByJoinDateRange(startDate, endDate));
    }


    ///==================== Create ====================///

    // Create a new Employee
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @PostMapping
    public ResponseEntity<Employee> createEmployee(@Valid @RequestBody Employee employee){

        // Create and save a new employee
        Employee createNewEmployee = employeeService.createEmployee(employee);

        // Return HTTP 201 CREATED after successful employee creation
        return ResponseEntity.status(HttpStatus.CREATED).body(createNewEmployee);

    }


    ///==================== Read ====================///

    // Get Employee by -> id
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping("/id/{id}")
    public ResponseEntity<Employee> getEmployeeById(@PathVariable Long id){

        // Return employee using database ID
        return ResponseEntity.ok(employeeService.findEmployeeById(id));

    }

    // Get Employee by -> employeeId
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping("/employeeId/{employeeId}")
    public ResponseEntity<Employee> getEmployeeByEmployeeId(@PathVariable String employeeId){

        // Return employee using official Employee ID
        return ResponseEntity.ok(employeeService.findEmployeeByEmployeeId(employeeId));

    }


    ///==================== Update ====================///

    // Update an existing employee with -> employeeId
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @PutMapping("/{employeeId}")
    public ResponseEntity<Employee> updateEmployeeByEmployeeId(@PathVariable String employeeId, @Valid @RequestBody Employee employee){

        Employee updateEmployee = employeeService.updateEmployee(employeeId, employee);

        return ResponseEntity.ok(updateEmployee);
    }




}