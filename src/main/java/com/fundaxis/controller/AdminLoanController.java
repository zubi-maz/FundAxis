package com.fundaxis.controller;

import com.fundaxis.entity.Loan;
import com.fundaxis.service.LoanService;
import com.fundaxis.service.LoanDisbursementService;
import com.fundaxis.service.RepaymentScheduleService;
import com.fundaxis.entity.RepaymentSchedule;
import com.fundaxis.entity.Repayment;
import com.fundaxis.entity.LoanRepayment;
import com.fundaxis.entity.LoanExtension;
import com.fundaxis.service.RepaymentService;
import com.fundaxis.service.LoanRepaymentService;
import com.fundaxis.service.LoanExtensionService;
import com.fundaxis.service.excel.LoanReportingExcelService;
import org.springframework.security.core.Authentication;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import com.fundaxis.exception.DuplicateLoanException;
import com.fundaxis.exception.EmployeeNotFoundException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import jakarta.validation.Valid;

import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

// Handles loan management pages accessible only to administrators.
@Controller
@PreAuthorize("hasRole('ADMIN')")
public class AdminLoanController {

    // ==================== Constructor Dependency Injection ==================== //

    // Service responsible for retrieving loan records.
    private final LoanService loanService;
    private final LoanDisbursementService loanDisbursementService;
    private final RepaymentScheduleService repaymentScheduleService;
    private final RepaymentService repaymentService;
    private final LoanRepaymentService loanRepaymentService;
    private final LoanReportingExcelService loanReportingExcelService;
    private final LoanExtensionService loanExtensionService;

    // Inject LoanService through the constructor.
    public AdminLoanController(LoanService loanService,
                               LoanDisbursementService loanDisbursementService,
                               RepaymentScheduleService repaymentScheduleService,
                               RepaymentService repaymentService,
                               LoanRepaymentService loanRepaymentService,
                               LoanReportingExcelService loanReportingExcelService,
                               LoanExtensionService loanExtensionService) {
        this.loanService = loanService;
        this.loanDisbursementService = loanDisbursementService;
        this.repaymentScheduleService = repaymentScheduleService;
        this.repaymentService = repaymentService;
        this.loanRepaymentService = loanRepaymentService;
        this.loanReportingExcelService = loanReportingExcelService;
        this.loanExtensionService = loanExtensionService;
    }

    @GetMapping("/admin/loans/export/excel")
    public ResponseEntity<byte[]> exportLoanReports(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String loanStatus,
            @RequestParam(required = false) String eligibilityStatus,
            @RequestParam(required = false) String loanType) {
        byte[] workbook = loanReportingExcelService.export(keyword, loanStatus, eligibilityStatus, loanType);
        String filename = "fundaxis-loan-reports-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(workbook.length)
                .body(workbook);
    }

    // ==================== Loan Management List / Search / Filter ==================== //

    // Displays all loans with read-only search and filtering.
    @GetMapping("/admin/loans")
    public String loanManagementPage(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String loanStatus,
            @RequestParam(required = false) String eligibilityStatus,
            @RequestParam(required = false) String loanType,
            Model model
    ) {

        // Retrieve all loans before applying search or filters.
        List<Loan> allLoans = loanService.getAllLoansList();

        // ==================== Summary Counts ==================== //

        // Calculate summary counts from the complete loan dataset.
        model.addAttribute("totalLoans", allLoans.size());

        // Count loans with DRAFT or SUBMITTED status.
        long draftSubmittedLoans = allLoans.stream()
                .filter(loan ->
                        loan.getLoanStatus() == Loan.LoanStatus.DRAFT
                                || loan.getLoanStatus() == Loan.LoanStatus.SUBMITTED
                )
                .count();

        model.addAttribute("draftAppliedLoans", draftSubmittedLoans);

        // Count loans with APPROVED or ACTIVE status.
        long approvedActiveLoans = allLoans.stream()
                .filter(loan ->
                        loan.getLoanStatus() == Loan.LoanStatus.APPROVED
                                || loan.getLoanStatus() == Loan.LoanStatus.ACTIVE
                )
                .count();

        model.addAttribute("approvedActiveLoans", approvedActiveLoans);

        // Count loans with REJECTED or CLOSED status.
        long rejectedClosedLoans = allLoans.stream()
                .filter(loan ->
                        loan.getLoanStatus() == Loan.LoanStatus.REJECTED
                                || loan.getLoanStatus() == Loan.LoanStatus.CLOSED
                )
                .count();

        model.addAttribute("rejectedClosedLoans", rejectedClosedLoans);

        // ==================== Search / Filter ==================== //

        // Apply keyword search and the selected filters.
        List<Loan> filteredLoans = allLoans.stream()

                // Search by Loan Application ID, Employee ID, or Employee Name.
                .filter(loan -> {

                    // Skip keyword filtering when the keyword is empty.
                    if (keyword == null || keyword.trim().isEmpty()) {
                        return true;
                    }

                    String searchValue = keyword.trim().toLowerCase();

                    // Check whether the Loan Application ID matches.
                    boolean applicationIdMatches =
                            loan.getLoanApplicationId() != null
                                    && loan.getLoanApplicationId()
                                    .toLowerCase()
                                    .contains(searchValue);

                    // Check whether the Employee ID matches.
                    boolean employeeIdMatches =
                            loan.getEmployee() != null
                                    && loan.getEmployee().getEmployeeId() != null
                                    && loan.getEmployee()
                                    .getEmployeeId()
                                    .toLowerCase()
                                    .contains(searchValue);

                    // Check whether the Employee Name matches.
                    boolean employeeNameMatches =
                            loan.getEmployee() != null
                                    && loan.getEmployee().getFullName() != null
                                    && loan.getEmployee()
                                    .getFullName()
                                    .toLowerCase()
                                    .contains(searchValue);

                    // Keep the loan if any searchable field matches.
                    return applicationIdMatches
                            || employeeIdMatches
                            || employeeNameMatches;
                })

                // Filter by Loan Status when a status is selected.
                .filter(loan ->
                        loanStatus == null
                                || loanStatus.isBlank()
                                || (
                                loan.getLoanStatus() != null
                                        && loan.getLoanStatus()
                                        .name()
                                        .equalsIgnoreCase(loanStatus)
                        )
                )

                // Filter by Eligibility Status when selected.
                .filter(loan ->
                        eligibilityStatus == null
                                || eligibilityStatus.isBlank()
                                || (
                                loan.getEligibilityStatus() != null
                                        && loan.getEligibilityStatus()
                                        .name()
                                        .equalsIgnoreCase(eligibilityStatus)
                        )
                )

                // Filter by Loan Type when a type is selected.
                .filter(loan ->
                        loanType == null
                                || loanType.isBlank()
                                || (
                                loan.getLoanType() != null
                                        && loan.getLoanType()
                                        .name()
                                        .equalsIgnoreCase(loanType)
                        )
                )

                .toList();

        // ==================== Thymeleaf Model ==================== //

        // Provide filtered loans for display in the table.
        model.addAttribute("loans", filteredLoans);

        // Preserve the keyword and selected filters in the view.
        model.addAttribute("keyword", keyword);
        model.addAttribute("selectedLoanStatus", loanStatus);
        model.addAttribute("selectedEligibilityStatus", eligibilityStatus);
        model.addAttribute("selectedLoanType", loanType);

        // Render the loan management page.
        return "admin/loans";
    }



    // ==================== Show Add Loan Form ==================== //

    // Displays the form for creating a new loan record.
    @GetMapping("/admin/loans/new")
    public String showAddLoanForm(Model model) {

        // Create an empty Loan object to bind the form input fields.
        Loan loan = new Loan();

        // Pass the Loan object to the Thymeleaf form.
        model.addAttribute("loan", loan);
        List<LoanExtension> loanExtensions = loanExtensionService.getExtensionsByLoanId(loan.getId());
        model.addAttribute("loanExtensions", loanExtensions);
        model.addAttribute("pendingLoanExtensions", loanExtensions.stream()
                .filter(extension -> extension.getExtensionStatus() == LoanExtension.ExtensionStatus.PENDING).toList());

        // Set edit mode to false because this is a new loan.
        model.addAttribute("editMode", false);
        model.addAttribute("guarantorEmployeeId", "");
        model.addAttribute("legacyGuarantorName", "");

        // Return the Thymeleaf template for the loan form.
        return "admin/loan-form";
    }



    // ==================== Create Loan ==================== //

    // Handles form submission for creating a new loan record.
    @PostMapping("/admin/loans")
    public String createLoan(
            @RequestParam String employeeId,
            @RequestParam(required = false) String guarantorEmployeeId,
            @RequestParam(required = false) String legacyGuarantorName,
            Authentication authentication,
            @Valid @ModelAttribute("loan") Loan loan,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes
    ) {

        // ==================== Validate Employee ID ==================== //

        // Ensure the employee ID is provided before processing the form.
        if (employeeId == null || employeeId.trim().isEmpty()) {

            // Display an error message when the employee ID is missing.
            model.addAttribute("errorMessage", "Employee ID is required.");
            model.addAttribute("employeeId", employeeId);
            model.addAttribute("guarantorEmployeeId", guarantorEmployeeId);
            model.addAttribute("legacyGuarantorName", legacyGuarantorName);
            model.addAttribute("editMode", false);

            // Return to the form so the employee ID can be corrected.
            return "admin/loan-form";
        }

        // ==================== Validate Loan Fields ==================== //

        // Check whether the submitted loan fields contain validation errors.
        if (bindingResult.hasErrors()) {

            // Preserve the employee ID and keep the form in create mode.
            model.addAttribute("employeeId", employeeId);
            model.addAttribute("guarantorEmployeeId", guarantorEmployeeId);
            model.addAttribute("legacyGuarantorName", legacyGuarantorName);
            model.addAttribute("editMode", false);

            // Return to the form so the user can correct invalid fields.
            return "admin/loan-form";
        }

        try {

            // Create the loan using the official bank Employee ID.
            loanService.createLoan(
                    employeeId.trim(),
                    guarantorEmployeeId,
                    legacyGuarantorName,
                    loan,
                    authentication.getName()
            );

            // Display a success message after the loan is created.
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Loan created successfully."
            );

            // Redirect to the loan management page.
            return "redirect:/admin/loans";

        } catch (DuplicateLoanException | EmployeeNotFoundException | IllegalArgumentException exception) {

            // Display the error message when a duplicate loan is detected.
            model.addAttribute("errorMessage", exception.getMessage());

            // Preserve the employee ID and keep the form in create mode.
            model.addAttribute("employeeId", employeeId);
            model.addAttribute("guarantorEmployeeId", guarantorEmployeeId);
            model.addAttribute("legacyGuarantorName", legacyGuarantorName);
            model.addAttribute("editMode", false);

            // Return to the form so the user can review the error.
            return "admin/loan-form";
        }
    }



    // ==================== View Loan Details ==================== //

    @GetMapping("/admin/loans/{loanApplicationId}")
    public String viewLoanDetails(@PathVariable String loanApplicationId,
                                  Authentication authentication, Model model) {

        // Find loan using official Loan Application ID
        Loan loan = loanService.getLoanByApplicationId(loanApplicationId);

        // Send loan to Thymeleaf
        model.addAttribute("loan", loan);
        List<com.fundaxis.entity.LoanDisbursement> disbursements =
                loanDisbursementService.getDisbursementsByLoanId(loan.getId());
        model.addAttribute("loanDisbursements", disbursements);
        model.addAttribute("totalDisbursed", loanDisbursementService.getTotalDisbursedAmount(loan.getId()));
        model.addAttribute("remainingApprovedAmount", loan.getApprovedAmount() == null
                ? null : loanDisbursementService.getRemainingApprovedAmount(loan.getId()));
        boolean independentReviewer = loan.getCreatedBy() != null
                && authentication != null
                && !loan.getCreatedBy().equalsIgnoreCase(authentication.getName());
        model.addAttribute("independentLoanReviewer", independentReviewer);
        model.addAttribute("loanCreatorMissing", loan.getCreatedBy() == null
                || loan.getCreatedBy().isBlank());
        model.addAttribute("loanApprovalEnabled", loanService.isLoanApprovalEnabled());

        List<RepaymentSchedule> schedules = repaymentScheduleService.getRepaymentSchedulesListByLoanId(loan.getId());
        List<Repayment> directRepayments = repaymentService.getRepaymentsByLoanId(loan.getId());
        List<LoanRepayment> workflowRepayments = loanRepaymentService.getLoanRepaymentsByLoanId(loan.getId());
        model.addAttribute("repaymentSchedules", schedules);
        model.addAttribute("directRepayments", directRepayments);
        model.addAttribute("workflowRepayments", workflowRepayments);
        model.addAttribute("scheduleCount", schedules.size());
        model.addAttribute("activeScheduleCount", schedules.stream()
                .filter(schedule -> schedule.getInstallmentStatus() != RepaymentSchedule.InstallmentStatus.CANCELLED).count());
        model.addAttribute("cancelledScheduleCount", schedules.stream()
                .filter(schedule -> schedule.getInstallmentStatus() == RepaymentSchedule.InstallmentStatus.CANCELLED).count());
        model.addAttribute("scheduledPrincipalTotal", sumScheduleAmount(schedules, RepaymentSchedule::getPrincipalAmount));
        model.addAttribute("scheduledInterestTotal", sumScheduleAmount(schedules, RepaymentSchedule::getInterestAmount));
        model.addAttribute("scheduledInstallmentTotal", sumScheduleAmount(schedules, RepaymentSchedule::getTotalInstallmentAmount));
        model.addAttribute("paidInstallmentTotal", sumScheduleAmount(schedules, RepaymentSchedule::getPaidAmount));
        model.addAttribute("remainingInstallmentTotal", sumScheduleAmount(schedules, RepaymentSchedule::getRemainingAmount));
        boolean scheduleTotalsConsistent = schedules.stream()
                .filter(schedule -> schedule.getInstallmentStatus() != RepaymentSchedule.InstallmentStatus.CANCELLED)
                .allMatch(AdminLoanController::isScheduleAmountsConsistent);
        BigDecimal ledgerCompletedTotal = directRepayments.stream()
                .filter(payment -> payment.getRepaymentStatus() == Repayment.RepaymentStatus.COMPLETED)
                .map(Repayment::getRepaymentAmount).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(workflowRepayments.stream()
                        .filter(payment -> payment.getRepaymentStatus() == LoanRepayment.RepaymentStatus.COMPLETED)
                        .map(LoanRepayment::getRepaymentAmount).filter(java.util.Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal schedulePaid = sumScheduleAmount(schedules, RepaymentSchedule::getPaidAmount);
        boolean hasActiveSchedule = schedules.stream()
                .anyMatch(schedule -> schedule.getInstallmentStatus() != RepaymentSchedule.InstallmentStatus.CANCELLED);
        boolean duplicateCrossLedgerReference = hasCrossLedgerReference(directRepayments, workflowRepayments);
        boolean repaymentLedgerReconciled = hasActiveSchedule && scheduleTotalsConsistent
                && !duplicateCrossLedgerReference
                && ledgerCompletedTotal.compareTo(schedulePaid) == 0;
        model.addAttribute("scheduleTotalsConsistent", scheduleTotalsConsistent);
        model.addAttribute("repaymentLedgerTotal", ledgerCompletedTotal);
        model.addAttribute("repaymentLedgerReconciled", repaymentLedgerReconciled);
        model.addAttribute("duplicateCrossLedgerReference", duplicateCrossLedgerReference);
        model.addAttribute("loanClosureReadiness", repaymentLedgerReconciled && remainingInstallmentIsZero(schedules)
                ? "REVIEW REQUIRED — principal/interest allocation and closure policy are not reconciled"
                : "NOT READY — repayment ledger or installment balance requires reconciliation");
        model.addAttribute("lateFeePolicyConfigured", false);
        model.addAttribute("scheduleToday", LocalDate.now());

        return "admin/loan-details";

    }

    private static boolean remainingInstallmentIsZero(List<RepaymentSchedule> schedules) {
        List<RepaymentSchedule> activeSchedules = schedules.stream()
                .filter(schedule -> schedule.getInstallmentStatus() != RepaymentSchedule.InstallmentStatus.CANCELLED)
                .toList();
        return !activeSchedules.isEmpty() && activeSchedules.stream()
                .allMatch(schedule -> schedule.getRemainingAmount() != null
                        && schedule.getRemainingAmount().compareTo(BigDecimal.ZERO) == 0);
    }

    private static boolean hasCrossLedgerReference(List<Repayment> directRepayments,
                                                   List<LoanRepayment> workflowRepayments) {
        java.util.Set<String> directReferences = directRepayments.stream()
                .map(Repayment::getReferenceNumber)
                .filter(java.util.Objects::nonNull)
                .map(reference -> reference.trim().toUpperCase(java.util.Locale.ROOT))
                .filter(reference -> !reference.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
        return workflowRepayments.stream()
                .map(LoanRepayment::getReferenceNumber)
                .filter(java.util.Objects::nonNull)
                .map(reference -> reference.trim().toUpperCase(java.util.Locale.ROOT))
                .anyMatch(directReferences::contains);
    }

    private static BigDecimal sumScheduleAmount(List<RepaymentSchedule> schedules,
                                                java.util.function.Function<RepaymentSchedule, BigDecimal> amount) {
        return schedules.stream()
                .filter(schedule -> schedule.getInstallmentStatus() != RepaymentSchedule.InstallmentStatus.CANCELLED)
                .map(amount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static boolean isScheduleAmountsConsistent(RepaymentSchedule schedule) {
        return schedule.getPrincipalAmount() != null
                && schedule.getInterestAmount() != null
                && schedule.getTotalInstallmentAmount() != null
                && schedule.getPaidAmount() != null
                && schedule.getRemainingAmount() != null
                && schedule.getPrincipalAmount().add(schedule.getInterestAmount())
                .compareTo(schedule.getTotalInstallmentAmount()) == 0
                && schedule.getPaidAmount().add(schedule.getRemainingAmount())
                .compareTo(schedule.getTotalInstallmentAmount()) == 0;
    }



    // ==================== Show Edit Draft Loan Form ==================== //

    // Displays the edit form for an existing draft loan.
    @GetMapping("/admin/loans/{loanApplicationId}/edit")
    public String showEditLoanForm(
            @PathVariable String loanApplicationId,
            Model model,
            RedirectAttributes redirectAttributes
    ) {

        // Retrieve the loan using its unique Loan Application ID.
        Loan loan = loanService.getLoanByApplicationId(
                loanApplicationId
        );

        // Allow editing only when the loan status is DRAFT.
        if (loan.getLoanStatus() != Loan.LoanStatus.DRAFT) {

            // Display an error message if the loan cannot be edited.
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Only draft loans can be edited."
            );

            // Redirect to the loan details page.
            return "redirect:/admin/loans/" + loanApplicationId;
        }

        // Pass the existing loan details to the Thymeleaf form.
        model.addAttribute("loan", loan);

        // Provide the employee ID for read-only display in edit mode.
        model.addAttribute(
                "employeeId",
                loan.getEmployee().getEmployeeId()
        );
        model.addAttribute("guarantorEmployeeId", loan.getGuarantorEmployee() == null
                ? "" : loan.getGuarantorEmployee().getEmployeeId());
        model.addAttribute("legacyGuarantorName", loan.getLegacyGuarantorName() == null
                ? "" : loan.getLegacyGuarantorName());

        // Enable edit mode to distinguish editing from creating a loan.
        model.addAttribute("editMode", true);

        // Return the shared loan form in edit mode.
        return "admin/loan-form";
    }



    // ==================== Update Draft Loan ==================== //

    // Handles form submission for updating an existing draft loan.
    @PostMapping("/admin/loans/{loanApplicationId}/update")
    public String updateLoan(
            @PathVariable String loanApplicationId,
            @RequestParam(required = false) String guarantorEmployeeId,
            @RequestParam(required = false) String legacyGuarantorName,
            @Valid @ModelAttribute("loan") Loan loan,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes
    ) {

        // ==================== Validation Errors ==================== //

        // Check whether the submitted loan fields contain validation errors.
        if (bindingResult.hasErrors()) {

            // Retrieve the existing loan using its business Loan Application ID.
            Loan existingLoan =
                    loanService.getLoanByApplicationId(loanApplicationId);

            // Pass the employee ID back to the form.
            model.addAttribute(
                    "employeeId",
                    existingLoan.getEmployee().getEmployeeId()
            );
            model.addAttribute("guarantorEmployeeId", guarantorEmployeeId);
            model.addAttribute("legacyGuarantorName", legacyGuarantorName);

            // Keep the form in edit mode.
            model.addAttribute("editMode", true);

            // Return to the form so the user can correct invalid fields.
            return "admin/loan-form";
        }

        try {

            // Retrieve the existing loan using its business Loan Application ID.
            Loan existingLoan =
                    loanService.getLoanByApplicationId(loanApplicationId);

            // Use the internal database ID when calling the update service.
            loanService.updateLoan(
                    existingLoan.getId(),
                    loan,
                    guarantorEmployeeId,
                    legacyGuarantorName
            );

            // Display a success message after the loan is updated.
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Loan updated successfully."
            );

            // Redirect using the business Loan Application ID.
            return "redirect:/admin/loans/" + loanApplicationId;

        } catch (IllegalStateException | EmployeeNotFoundException | IllegalArgumentException exception) {

            // Retrieve the existing loan to restore employee information.
            Loan existingLoan =
                    loanService.getLoanByApplicationId(loanApplicationId);

            // Preserve the employee ID when returning to the form.
            model.addAttribute(
                    "employeeId",
                    existingLoan.getEmployee().getEmployeeId()
            );

            // Keep the form in edit mode.
            model.addAttribute("editMode", true);
            model.addAttribute("guarantorEmployeeId", guarantorEmployeeId);
            model.addAttribute("legacyGuarantorName", legacyGuarantorName);

            // Display the business error returned by the service layer.
            model.addAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            // Return to the form so the user can review the error.
            return "admin/loan-form";
        }
    }



    // ==================== Submit Draft Loan ==================== //

    // Submits an existing DRAFT loan into the loan workflow.
    @PostMapping("/admin/loans/{loanApplicationId}/submit")
    public String submitLoan(
            @PathVariable String loanApplicationId,
            RedirectAttributes redirectAttributes
    ) {

        try {

            // Retrieve the loan using its business Loan Application ID.
            Loan existingLoan =
                    loanService.getLoanByApplicationId(loanApplicationId);

            // Submit the loan using its internal database ID.
            loanService.submitLoan(existingLoan.getId());

            // Display a success message after successful submission.
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Loan submitted successfully."
            );

            // Redirect to the loan details page.
            return "redirect:/admin/loans/" + loanApplicationId;

        } catch (IllegalStateException | IllegalArgumentException exception) {

            // Display the error message if the loan cannot be submitted.
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            // Redirect to the loan details page to display the error.
            return "redirect:/admin/loans/" + loanApplicationId;
        }
    }



    // ==================== Start Loan Review ==================== //

    // Moves a submitted loan into the review stage.
    @PostMapping("/admin/loans/{loanApplicationId}/review")
    public String startLoanReview(
            @PathVariable String loanApplicationId,
            RedirectAttributes redirectAttributes
    ) {

        try {

            // Retrieve the loan using its business Loan Application ID.
            Loan existingLoan =
                    loanService.getLoanByApplicationId(loanApplicationId);

            // Start the review process using the internal database ID.
            loanService.startLoanReview(existingLoan.getId());

            // Display a success message after the review starts.
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Loan review started successfully."
            );

            // Redirect to the loan details page.
            return "redirect:/admin/loans/" + loanApplicationId;

        } catch (IllegalStateException | IllegalArgumentException exception) {

            // Display the business error returned by the service layer.
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            // Redirect to the loan details page to display the error.
            return "redirect:/admin/loans/" + loanApplicationId;
        }
    }



    // ==================== Update Loan Eligibility ==================== //

    // Updates loan eligibility during the review stage.
    @PostMapping("/admin/loans/{loanApplicationId}/eligibility")
    public String updateLoanEligibility(
            @PathVariable String loanApplicationId,
            @RequestParam Loan.EligibilityStatus eligibilityStatus,
            RedirectAttributes redirectAttributes
    ) {

        try {

            // Retrieve the loan using its business Loan Application ID.
            Loan existingLoan =
                    loanService.getLoanByApplicationId(loanApplicationId);

            // Update eligibility using the internal database ID.
            loanService.updateEligibilityStatus(
                    existingLoan.getId(),
                    eligibilityStatus
            );

            // Display a success message after updating eligibility.
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Loan eligibility updated successfully."
            );

            // Redirect to the loan details page.
            return "redirect:/admin/loans/" + loanApplicationId;

        } catch (IllegalStateException exception) {

            // Display the business error returned by the service layer.
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            // Redirect to the loan details page to display the error.
            return "redirect:/admin/loans/" + loanApplicationId;
        }
    }



    // ==================== Approve Loan ==================== //

    // Approves an eligible loan that is under review.
    @PostMapping("/admin/loans/{loanApplicationId}/approve")
    public String approveLoan(
            @PathVariable String loanApplicationId,
            @RequestParam BigDecimal approvedAmount,
            @RequestParam BigDecimal interestRate,
            @RequestParam Integer approvedTenureMonths,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {

        try {

            // Retrieve the loan using its business Loan Application ID.
            Loan existingLoan =
                    loanService.getLoanByApplicationId(loanApplicationId);

            // Approve the loan using its internal database ID.
            loanService.approveLoan(
                    existingLoan.getId(),
                    approvedAmount,
                    interestRate,
                    approvedTenureMonths,
                    authentication.getName()
            );

            // Display a success message after approval.
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Loan approved successfully."
            );

            // Redirect to the loan details page.
            return "redirect:/admin/loans/" + loanApplicationId;

        } catch (IllegalStateException | IllegalArgumentException exception) {

            // Display the business error returned by the service layer.
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            // Redirect to the loan details page to display the error.
            return "redirect:/admin/loans/" + loanApplicationId;
        }
    }



    // ==================== Reject Loan ==================== //

    // Rejects a loan that is currently under review.
    @PostMapping("/admin/loans/{loanApplicationId}/reject")
    public String rejectLoan(
            @PathVariable String loanApplicationId,
            @RequestParam String rejectionReason,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {

        // Validate that a rejection reason is provided.
        if (rejectionReason == null || rejectionReason.trim().isEmpty()) {

            // Display an error if the rejection reason is missing.
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Rejection reason is required."
            );

            return "redirect:/admin/loans/" + loanApplicationId;
        }

        try {

            // Retrieve the loan using its business Loan Application ID.
            Loan existingLoan =
                    loanService.getLoanByApplicationId(loanApplicationId);

            // Reject the loan using its internal database ID and reason.
            loanService.rejectLoan(
                    existingLoan.getId(),
                    rejectionReason.trim(),
                    authentication.getName()
            );

            // Display a success message after rejection.
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Loan rejected successfully."
            );

            // Redirect to the loan details page.
            return "redirect:/admin/loans/" + loanApplicationId;

        } catch (IllegalStateException | IllegalArgumentException exception) {

            // Display the business error returned by the service layer.
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            // Redirect to the loan details page to display the error.
            return "redirect:/admin/loans/" + loanApplicationId;
        }
    }


    // ==================== Cancel Loan ==================== //

    // Cancels a loan that is still in DRAFT or SUBMITTED status.
    @PostMapping("/admin/loans/{loanApplicationId}/cancel")
    public String cancelLoan(
            @PathVariable String loanApplicationId,
            RedirectAttributes redirectAttributes
    ) {

        try {

            // Retrieve the loan using its business Loan Application ID.
            Loan existingLoan =
                    loanService.getLoanByApplicationId(loanApplicationId);

            // Cancel the loan using its internal database ID.
            loanService.cancelLoan(existingLoan.getId());

            // Display a success message after cancellation.
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Loan canceled successfully."
            );

            // Redirect to the loan details page.
            return "redirect:/admin/loans/" + loanApplicationId;

        } catch (IllegalStateException exception) {

            // Display the business error returned by the service layer.
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            // Redirect to the loan details page to display the error.
            return "redirect:/admin/loans/" + loanApplicationId;
        }
    }


}
