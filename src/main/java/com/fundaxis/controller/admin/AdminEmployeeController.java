package com.fundaxis.controller.admin;

// Employee entity, exception, and service
import com.fundaxis.entity.Employee;
import com.fundaxis.service.EmployeeService;
// Runs the validation rules (@NotBlank, etc.) on the form object
import com.fundaxis.service.excel.EmployeeExcelService;
import jakarta.validation.Valid;
// Spring classes
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.Principal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

///===========================================================================================================///
///============================== @Controller for HTML pages (Thymeleaf Web UI) ==============================///
///===========================================================================================================///


@Controller
@PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
public class AdminEmployeeController {


    private final EmployeeService employeeService;
    private final EmployeeExcelService employeeExcelService;

    ///---------- Constructor Injection ----------
    public AdminEmployeeController(EmployeeService employeeService, EmployeeExcelService employeeExcelService) {
        this.employeeService = employeeService;
        this.employeeExcelService = employeeExcelService;
    }


    ///==================== Employee Management List ====================///

    ///---------- Show The Admin Employee Management Page ----------
    @GetMapping("/admin/employees")
    public String employeeManagementPage(
            @RequestParam(defaultValue = "0") int page,                     // Current page number (starts at 0)
            @RequestParam(defaultValue = "10") int size,                    // Number of items per page
            @RequestParam(required = false) String keyword,                 // Search text (optional)
            @RequestParam(required = false) String status,                  // Status filter: ALL / ACTIVE / INACTIVE (optional)
            @RequestParam(required = false) String employeeDesignation,     // Designation filter
            Model model                                                     // Sends data to the Thymeleaf page
    ) {

        // Create pageable object for pagination size and current page index
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size);

        // Fetch the complete employee list from the database to ensure accurate summary cards and filtering
        List<Employee> allEmployees = employeeService.getAllEmployeeList();

        // Summary cards (Calculated from the full list)
        long totalEmployees = allEmployees.size();                          // Total records in database

        // Count employees whose status is ACTIVE
        long activeEmployees = allEmployees.stream()
                .filter(e -> e.getEmployeeStatus() == Employee.EmployeeStatus.ACTIVE)           // Keep only ACTIVE
                .count();                                                                                // Count them

        // Count employees whose status is INACTIVE
        long inactiveEmployees = allEmployees.stream()
                .filter(e -> e.getEmployeeStatus() == Employee.EmployeeStatus.INACTIVE)         // Keep only INACTIVE
                .count();                                                                               // Count them

        // Send the summary numbers to the page
        model.addAttribute("totalEmployees", totalEmployees);                     // Total card
        model.addAttribute("activeEmployees", activeEmployees);                  // Active card
        model.addAttribute("inactiveEmployees", inactiveEmployees);             // Inactive card

        // ----- Table Filtering -----

        // Start with all employees, then narrow the list step by step
        List<Employee> filteredEmployees = allEmployees;

        /// Filter 1: search by Employee ID, Full Name
        if (keyword != null && !keyword.trim().isEmpty()) {                       // Only run if the user typed something

            // Remove extra spaces and lowercase it for case-insensitive search
            String searchKeyword = keyword.trim().toLowerCase();

            // Keep only employees that match the keyword
            filteredEmployees = filteredEmployees.stream()
                    .filter(e ->

                            // Match by official bank Employee ID (null check avoids errors)
                            (e.getEmployeeId() != null
                                    && e.getEmployeeId().toLowerCase().contains(searchKeyword))

                                    // OR match by Full Name
                                    || (e.getFullName() != null
                                    && e.getFullName().toLowerCase().contains(searchKeyword))

                    )
                    .toList();  // Collect the matching employees
        }

        /// Filter 2: employee status (skipped when empty or "ALL")
        if (status != null                                                        // Status was provided
                && !status.isBlank()                                             // and is not empty
                && !status.equalsIgnoreCase("ALL")) {                // and is not "ALL"

            // Keep only employees with the selected status
            filteredEmployees = filteredEmployees.stream()
                    .filter(e -> e.getEmployeeStatus() != null       // Avoid null status errors
                            && e.getEmployeeStatus().name().equalsIgnoreCase(status))  // Compare with selected status
                    .toList();                                       // Collect the matching employees
        }

        /// Filter 3: employee designation (skipped when empty or "ALL")
        if (employeeDesignation != null
                && !employeeDesignation.isBlank()
                && !employeeDesignation.equalsIgnoreCase("ALL")) {

            filteredEmployees = filteredEmployees.stream()
                    .filter(e -> e.getEmployeeDesignation() != null
                            && e.getEmployeeDesignation().name().equalsIgnoreCase(employeeDesignation))
                    .toList();
        }

        // ----- Pagination applied on Filtered Results -----

        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), filteredEmployees.size());

        // Handle edge case where page index is out of bounds
        List<Employee> paginatedList = start > end ? java.util.Collections.emptyList() : filteredEmployees.subList(start, end);

        org.springframework.data.domain.Page<Employee> employeePage = new org.springframework.data.domain.PageImpl<>(paginatedList, pageable, filteredEmployees.size());

        // ----- Send data to the Thymeleaf view -----

        // The employee rows shown in the table (current page slice)
        model.addAttribute("employees", employeePage.getContent());

        // Pagination metadata for HTML navigation controls
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", employeePage.getTotalPages());
        model.addAttribute("pageSize", size);

        // Keep the search box value after page reload
        model.addAttribute("keyword", keyword == null ? "" : keyword);

        // Keep the status dropdown options and selected value after page reload
        model.addAttribute("statuses", Employee.EmployeeStatus.values());              // Options for the dropdown
        model.addAttribute("status", status == null ? "ALL" : status);                 // Selected value

        // Keep the designation dropdown options and selected value after page reload
        model.addAttribute("designations", Employee.EmployeeDesignation.values());             // Options for the dropdown
        model.addAttribute("designation", employeeDesignation == null ? "ALL" : employeeDesignation);   // Selected value

        // Number of rows currently displayed matching filters
        model.addAttribute("resultCount", filteredEmployees.size());

        // Show templates/admin/employees.html
        return "admin/employees";
    }


    ///==================== Add Employee (Multi-Row Form) ====================///

    ///---------- Show The Add Employee Form ----------
    @GetMapping("/admin/employees/new")
    public String showAddEmployeeForm(Model model) {

        // Send the wrapper object containing the initial empty employee row list
        model.addAttribute("employeeWrapper", new com.fundaxis.dto.EmployeeListWrapper());

        // Send designation enum values to populate dropdowns in each row
        model.addAttribute("designations", Employee.EmployeeDesignation.values());

        // Tell the shared form this is "add" mode
        model.addAttribute("editMode", false);

        // Show templates/admin/employee-form.html
        return "admin/employee-form";
    }


    ///---------- Create Multiple Employees From The Form ----------
    @PostMapping("/admin/employees/bulk")
    public String saveBulkEmployees(
            @ModelAttribute("employeeWrapper") com.fundaxis.dto.EmployeeListWrapper employeeWrapper,
            RedirectAttributes redirectAttributes,
            Model model) {

        try {
            // Sanitize and ensure EMP- prefix is attached to every employee ID from the form
            if (employeeWrapper != null && employeeWrapper.getEmployees() != null) {
                for (Employee emp : employeeWrapper.getEmployees()) {
                    if (emp.getEmployeeId() != null) {
                        String cleanId = emp.getEmployeeId().trim().replaceFirst("(?i)^EMP-?", "");
                        if (!cleanId.isEmpty()) {
                            emp.setEmployeeId("EMP-" + cleanId);
                        }
                    }
                }
            }

            int savedCount = employeeService.createBulkEmployees(employeeWrapper.getEmployees());

            if (savedCount == 0) {
                model.addAttribute("errorMessage", "Please provide valid and unique Employee IDs for at least one row.");
                model.addAttribute("designations", Employee.EmployeeDesignation.values());
                model.addAttribute("editMode", false);
                // FIX: Point to add-employee.html
                return "admin/employee-form";
            }

            // Success message for the next page
            redirectAttributes.addFlashAttribute("successMessage", "Successfully created " + savedCount + " employees.");

            // Success: go back to the employee list
            return "redirect:/admin/employees";

        } catch (Exception exception) {
            model.addAttribute("errorMessage", "Error saving employees: " + exception.getMessage());
            model.addAttribute("designations", Employee.EmployeeDesignation.values());
            model.addAttribute("editMode", false);
            // FIX: Point to add-employee.html
            return "admin/employee-form";
        }
    }


    ///==================== View Employee Details ====================///

    ///---------- Show One Employee's Details Page ----------
    // Opens at /admin/employees/{employeeId}
    @GetMapping("/admin/employees/{employeeId}")
    public String viewEmployeeDetails(
            @PathVariable String employeeId,  // Official Employee ID from the URL (like EMP001)
            Model model                       // Sends data to the page
    ) {

        // Find the employee by the official Employee ID
        Employee employee = employeeService.findEmployeeByEmployeeId(employeeId);

        // Send the employee to the details page
        model.addAttribute("employee", employee);

        // Show templates/admin/employee-details.html
        return "admin/employee-details";
    }


    ///==================== Edit Employee ====================///

    ///---------- Show The Edit Employee Form ----------
    // 1. Show the Edit Form endpoint
    @GetMapping("/admin/employees/{id}/edit")
    public String showEditEmployeeForm(
            @PathVariable("id") String id,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        // Fetch the employee record from the database by their ID
        Employee employee = employeeService.findEmployeeByEmployeeId(id);

        // If the employee does not exist, redirect back to the list with an error message
        if (employee == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "Employee not found.");
            return "redirect:/admin/employees";
        }

        // Add the employee object and available designation choices to the model
        model.addAttribute("employee", employee);
        model.addAttribute("designations", Employee.EmployeeDesignation.values());

        // Optional: If your status is an Enum, you can also pass status values dynamically:
        model.addAttribute("statuses", Employee.EmployeeStatus.values());

        // Render the edit template correctly from the admin folder
        return "admin/employee-edit-form";
    }

    ///---------- Update An Employee From The Edit Form ----------
    // 2. Process the Update Submission endpoint (Supports editing employee ID, validation, and error safety)
    // 2. Process the Update Submission endpoint
    @PostMapping("/admin/employees/{id}/edit")
    public String updateEmployee(
            @PathVariable("id") String oldEmployeeId,
            @Valid @ModelAttribute("employee") Employee employee,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        // If form validation fails, re-populate choices and re-render the edit form
        if (bindingResult.hasErrors()) {
            model.addAttribute("designations", Employee.EmployeeDesignation.values());
            model.addAttribute("statuses", Employee.EmployeeStatus.values()); // <-- Added this
            return "admin/employee-edit-form";
        }

        try {
            // Call the service layer to update name, ID, join date, designation, AND status
            employeeService.updateEmployee(oldEmployeeId, employee);

            redirectAttributes.addFlashAttribute("successMessage", "Employee " + employee.getEmployeeId() + " successfully updated.");
            return "redirect:/admin/employees/" + employee.getEmployeeId();

        } catch (Exception exception) {
            // Catch service errors, re-add dropdown options, and show error
            model.addAttribute("errorMessage", "Error updating employee: " + exception.getMessage());
            model.addAttribute("designations", Employee.EmployeeDesignation.values());
            model.addAttribute("statuses", Employee.EmployeeStatus.values()); // <-- Added this
            return "admin/employee-edit-form";
        }
    }


    ///==================== Activate Employee ====================///

    ///---------- Activate An Employee ----------
    // Form submit (POST) to /admin/employees/{employeeId}/activate
    @PostMapping("/admin/employees/{employeeId}/activate")
    public String activateEmployee(
            @PathVariable String employeeId,       // Official Employee ID from the URL
            RedirectAttributes redirectAttributes  // Carries a message across the redirect
    ) {

        try {

            // Activate the employee using the official Employee ID
            employeeService.activateEmployee(employeeId);

            // Success message shown on the next page
            redirectAttributes.addFlashAttribute("successMessage", "Employee activated successfully.");

        } catch (IllegalStateException exception) {

            // Error message from the service (for example, already active)
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        }

        // Go back to this employee's details page
        return "redirect:/admin/employees/" + employeeId;
    }


    ///==================== Deactivate Employee ====================///

    ///---------- Deactivate An Employee ----------
    // Form submit (POST) to /admin/employees/{employeeId}/deactivate
    @PostMapping("/admin/employees/{employeeId}/deactivate")
    public String deactivateEmployee(
            @PathVariable String employeeId,       // Official Employee ID from the URL
            @RequestParam String reasonOfDeactivation,  // Reason typed in the deactivate form
            RedirectAttributes redirectAttributes  // Carries a message across the redirect
    ) {

        try {

            // The reason is required: stop if it is empty or only spaces
            if (reasonOfDeactivation == null || reasonOfDeactivation.trim().isEmpty()) {

                // Error message for the next page, it disappears after one redirect
                redirectAttributes.addFlashAttribute("errorMessage", "Deactivation reason is required.");

                // Go back to this employee's details page
                return "redirect:/admin/employees/" + employeeId;
            }

            // Deactivate using the official Employee ID, the reason is saved without extra spaces
            employeeService.deactivateEmployee(employeeId, reasonOfDeactivation.trim());

            // Success message for the next page
            redirectAttributes.addFlashAttribute("successMessage", "Employee deactivated successfully.");

            // Go back to this employee's details page
            return "redirect:/admin/employees/" + employeeId;

        } catch (IllegalStateException exception) {

            // The service refused (for example, the employee is already inactive): show its message
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());

            // Go back to this employee's details page
            return "redirect:/admin/employees/" + employeeId;
        }
    }


    ///==================== Logger & Constants ====================///

    // Corrected Logger referencing AdminEmployeeController
    private static final Logger log = LoggerFactory.getLogger(AdminEmployeeController.class);

    // MIME type for Excel file (.xlsx format)
    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    // Date format for generating output file name (e.g., 20261022)
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ENGLISH);



    ///==================== Export Employees to Excel ====================///
    // Required Imports:
    // java.time.LocalDate, java.time.format.DateTimeFormatter, java.util.Locale
    // org.springframework.core.io.InputStreamResource, org.springframework.core.io.Resource
    // org.springframework.format.annotation.DateTimeFormat
    // org.springframework.http.* (ContentDisposition, HttpHeaders, HttpStatus, MediaType, ResponseEntity, CacheControl)
    // org.springframework.web.server.ResponseStatusException
    // java.security.Principal, org.slf4j.Logger / LoggerFactory

    // Endpoint to export employee records to an Excel (.xlsx) file, optionally filtered by a join date range
    @GetMapping("/admin/employees/export/excel")
    public ResponseEntity<Resource> exportToExcel(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            Principal principal) throws IOException {

        // Validate that either both dates are provided or neither is provided
        if ((startDate == null) != (endDate == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Both startDate and endDate are required for a date-range export.");
        }

        // Validate chronological order
        if (startDate != null && startDate.isAfter(endDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "startDate must not be after endDate.");
        }

        // Fetch employees within the date range if provided; otherwise, fetch the complete list
        List<Employee> employees = (startDate != null)
                ? employeeService.getEmployeesByJoinDateRange(startDate, endDate)
                : employeeService.getAllEmployeeList();

        // Audit log
        log.info("Employee Excel export by '{}' range=[{} .. {}] rows={}",
                principal != null ? principal.getName() : "unknown", startDate, endDate, employees.size());

        // Generate Excel binary stream
        ByteArrayInputStream in = employeeExcelService.exportEmployeesToExcel(employees, startDate, endDate);

        // Determine filename suffix
        String suffix = (startDate != null)
                ? startDate.format(FILE_DATE) + "_to_" + endDate.format(FILE_DATE)
                : "All";

        ContentDisposition disposition = ContentDisposition.attachment()
                .filename("National_Bank_PLC_Employee_List_" + suffix + ".xlsx")
                .build();

        // Null-safe, clean Cache-Control response
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .cacheControl(CacheControl.noStore())
                .contentLength(in.available())
                .contentType(XLSX)
                .body(new InputStreamResource(in));
    }


    ///==================== Import Bulk Employees from Excel ====================///

    @PostMapping("/admin/employees/upload-excel")
    public String uploadBulkEmployeesFromExcel(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            RedirectAttributes redirectAttributes) {

        if (file.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Please select a valid Excel (.xlsx) file.");
            return "redirect:/admin/employees/new";
        }

        try {
            // Parse employees using ExcelExportService
            List<Employee> parsedEmployees = employeeExcelService.parseEmployeesFromExcel(file);

            // Save parsed list using EmployeeService
            int savedCount = employeeService.createEmployeesFromExcel(parsedEmployees);

            redirectAttributes.addFlashAttribute("successMessage",
                    "Successfully imported " + savedCount + " employees from Excel!");

            return "redirect:/admin/employees";

        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Error parsing Excel file: " + e.getMessage());
            return "redirect:/admin/employees/new";
        }
    }




//    ///==================== Import Employees from Excel ====================///
//
//    @PostMapping("/admin/employees/import/excel")
//    public String importEmployeesFromExcel(@RequestParam("file") MultipartFile file,
//                                           RedirectAttributes redirectAttributes) {
//        if (file == null || file.isEmpty()) {
//            redirectAttributes.addFlashAttribute("errorMessage", "Please select a valid Excel (.xlsx) file to upload.");
//            return "redirect:/admin/employees";
//        }
//
//        try {
//            // Parse employees via ExcelExportService
//            List<Employee> parsedEmployees = excelExportService.parseEmployeesFromExcel(file);
//
//            // Save list via EmployeeService
//            int savedCount = employeeService.createEmployeesFromExcel(parsedEmployees);
//
//            redirectAttributes.addFlashAttribute("successMessage",
//                    "Successfully imported " + savedCount + " employees from Excel!");
//
//        } catch (Exception e) {
//            log.error("Failed to import employee Excel file", e);
//            redirectAttributes.addFlashAttribute("errorMessage",
//                    "Error processing Excel file: " + e.getMessage());
//        }
//
//        return "redirect:/admin/employees";
//    }



    ///==================== Preview & Import Excel into Form ====================///

    @PostMapping("/admin/employees/import/excel")
    public String previewEmployeesFromExcel(@RequestParam("file") MultipartFile file,
                                            Model model,
                                            RedirectAttributes redirectAttributes) {
        if (file.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Please select a valid Excel or CSV file.");
            return "redirect:/admin/employees/new";
        }

        try {
            // 1. Parse rows using ExcelExportService, mapping names, designations, and dates dynamically
            List<Employee> parsedEmployees = employeeExcelService.parseEmployeesFromExcel(file);

            if (parsedEmployees.isEmpty()) {
                redirectAttributes.addFlashAttribute("errorMessage", "No employee records found in the uploaded file.");
                return "redirect:/admin/employees/new";
            }

            // 2. Wrap list for the multi-row form model binding
            com.fundaxis.dto.EmployeeListWrapper wrapper = new com.fundaxis.dto.EmployeeListWrapper();
            wrapper.setEmployees(parsedEmployees);

            // 3. Populate model attributes required by employee-form.html
            model.addAttribute("employeeWrapper", wrapper);
            model.addAttribute("designations", Employee.EmployeeDesignation.values());
            model.addAttribute("editMode", false);

            redirectAttributes.addFlashAttribute("successMessage",
                    "Successfully parsed " + parsedEmployees.size() + " records from Excel. Review and click 'Save Employee' to finalize.");

            // 4. Render the form page pre-filled with the parsed data
            return "add-excel-employee";

        } catch (Exception e) {
            log.error("Failed to parse employee Excel file", e);
            redirectAttributes.addFlashAttribute("errorMessage", "Error parsing file: " + e.getMessage());
            return "redirect:/admin/employees/new";
        }
    }



    // =========================================================
    // EMPLOYEE LOOKUP FOR BULK CONTRIBUTION FORM
    // GET /admin/employees/lookup?employeeId=EMP001
    // ADMIN / IT_SUPPORT - READ ONLY
    // =========================================================

    @GetMapping(
            value = "/admin/employees/lookup",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @org.springframework.web.bind.annotation.ResponseBody
    public ResponseEntity<Map<String, Object>> lookupEmployee(
            @RequestParam String employeeId
    ) {

        if (employeeId == null || employeeId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        String normalizedId = employeeId.trim();
        if (normalizedId.matches("\\d+")) normalizedId = "EMP-" + normalizedId;
        if (normalizedId.length() > 50) return ResponseEntity.badRequest().build();

        // Use the existing Employee Service.
        Employee employee;

        try {
            employee = employeeService
                    .findEmployeeByEmployeeId(normalizedId);

        } catch (IllegalArgumentException |
                 IllegalStateException exception) {

            return ResponseEntity.notFound().build();
        }

        if (employee == null) {
            return ResponseEntity.notFound().build();
        }

        boolean active =
                employee.getEmployeeStatus()
                        == Employee.EmployeeStatus.ACTIVE;

        String designation =
                employee.getEmployeeDesignation() == null
                        ? ""
                        : employee.getEmployeeDesignation().name();

        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("employeeId", employee.getEmployeeId());
        response.put("fullName", employee.getFullName() == null ? "" : employee.getFullName());
        response.put("designation", designation);
        response.put("active", active);
        response.put("employeeStatus", employee.getEmployeeStatus() == null ? "Not recorded" : employee.getEmployeeStatus().name());
        response.put("fundJoinDate", employee.getFundJoinDate() == null ? "" : employee.getFundJoinDate().toString());
        response.put("fundExitDate", employee.getFundExitDate() == null ? "" : employee.getFundExitDate().toString());

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }







}
