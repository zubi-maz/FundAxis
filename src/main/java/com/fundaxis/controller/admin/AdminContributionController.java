package com.fundaxis.controller.admin;

import com.fundaxis.entity.Contribution;
import com.fundaxis.service.ContributionService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import com.fundaxis.exception.DuplicateContributionException;
import com.fundaxis.exception.EmployeeNotFoundException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.ArrayList;
import java.math.BigDecimal;
import java.time.ZoneId;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;

// Handles contribution management for administrators only.
@Controller
@PreAuthorize("hasRole('ADMIN')")
public class AdminContributionController {

    private static final Logger log = LoggerFactory.getLogger(AdminContributionController.class);
    private static final ZoneId BANK_ZONE = ZoneId.of("Asia/Dhaka");

    /** Form binding only; internal employee database IDs are never accepted. */
    public static class BatchContributionForm {
        @NotNull(message = "Select a contribution month.")
        private LocalDate contributionMonth;

        @NotEmpty(message = "Add at least one employee row.")
        @Size(max = 50, message = "A batch can contain at most 50 employee rows.")
        @Valid
        private List<BatchContributionEntryForm> entries = new ArrayList<>();

        public LocalDate getContributionMonth() { return contributionMonth; }
        public void setContributionMonth(LocalDate contributionMonth) { this.contributionMonth = contributionMonth; }
        public List<BatchContributionEntryForm> getEntries() { return entries; }
        public void setEntries(List<BatchContributionEntryForm> entries) { this.entries = entries; }
    }

    public static class BatchContributionEntryForm {
        @NotBlank(message = "Employee ID is required.")
        @Size(max = 20, message = "Employee ID is too long.")
        private String employeeId;

        @NotNull(message = "Contribution amount is required.")
        @DecimalMin(value = "0.01", message = "Amount must be greater than zero.")
        @Digits(integer = 18, fraction = 2, message = "Amount can have at most two decimal places.")
        private BigDecimal contributionAmount;

        @jakarta.validation.constraints.DecimalMin(value = "0.00", message = "Share Value cannot be negative.")
        @Digits(integer = 18, fraction = 2, message = "Share Value can have at most two decimal places.")
        private BigDecimal shareValue;

        @Size(max = 500, message = "Remarks must be 500 characters or fewer.")
        private String remarks;

        private com.fundaxis.entity.ContributionPayment.PaymentMethod intendedCollectionMethod;

        public String getEmployeeId() { return employeeId; }
        public void setEmployeeId(String employeeId) { this.employeeId = employeeId; }
        public BigDecimal getContributionAmount() { return contributionAmount; }
        public void setContributionAmount(BigDecimal contributionAmount) { this.contributionAmount = contributionAmount; }
        public BigDecimal getShareValue() { return shareValue; }
        public void setShareValue(BigDecimal shareValue) { this.shareValue = shareValue; }
        public String getRemarks() { return remarks; }
        public void setRemarks(String remarks) { this.remarks = remarks; }
        public com.fundaxis.entity.ContributionPayment.PaymentMethod getIntendedCollectionMethod() { return intendedCollectionMethod; }
        public void setIntendedCollectionMethod(com.fundaxis.entity.ContributionPayment.PaymentMethod intendedCollectionMethod) { this.intendedCollectionMethod = intendedCollectionMethod; }
    }

    // ==================== Constructor Dependency Injection ==================== //

    // Service responsible for retrieving contribution records.
    private final ContributionService contributionService;

    // Injects ContributionService through the constructor.
    public AdminContributionController(ContributionService contributionService) {
        this.contributionService = contributionService;
    }

    ///==================== Contribution Management List ====================///

    // Displays all contributions to the admin management page.
    @GetMapping("/admin/contributions")
    public String contributionManagementPage(Model model) {

        // Retrieve all contribution records from the service.
        List<Contribution> contributions =
                contributionService.getAllContributionsList();

        // Pass the contribution list to the Thymeleaf template.
        model.addAttribute("contributions", contributions);

        // ==================== Total Contributions ==================== //

        // Calculate the total number of contribution records.
        model.addAttribute("totalContributions", contributions.size());

        // ==================== Pending Contributions ==================== //

        // Count contributions with PENDING status.
        long pendingContributions = contributions.stream()
                .filter(contribution ->
                        contribution.getContributionStatus()
                                == Contribution.ContributionStatus.PENDING)
                .count();

        // Pass the pending contribution count to the view.
        model.addAttribute("pendingContributions", pendingContributions);

        // ==================== Paid Contributions ==================== //

        // Count contributions with PAID status.
        long paidContributions = contributions.stream()
                .filter(contribution ->
                        contribution.getContributionStatus()
                                == Contribution.ContributionStatus.PAID)
                .count();

        // Pass the paid contribution count to the view.
        model.addAttribute("paidContributions", paidContributions);

        // ==================== Canceled Contributions ==================== //

        // Count contributions with CANCELED status.
        long canceledContributions = contributions.stream()
                .filter(contribution ->
                        contribution.getContributionStatus()
                                == Contribution.ContributionStatus.CANCELED)
                .count();

        // Pass the canceled contribution count to the view.
        model.addAttribute("canceledContributions", canceledContributions);

        // Return the Thymeleaf template for the admin contributions page.
        return "redirect:/admin/contribution-workspace";
    }



    // ==================== Show Add Contribution Form ==================== //

    // Displays the form for creating a new contribution record.
    @GetMapping("/admin/contributions/new")
    public String showAddContributionForm(
            @RequestParam(required = false) String employeeId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            Model model) {

        // Create an empty Contribution object to bind form input fields.
        Contribution contribution = new Contribution();

        // Pass the contribution object to the Thymeleaf form.
        model.addAttribute("contribution", contribution);

        // Set edit mode to false because this is a new contribution.
        model.addAttribute("editMode", false);
        model.addAttribute("prefillEmployeeId", employeeId == null ? "" : employeeId.trim());
        model.addAttribute("prefillYear", year);
        model.addAttribute("prefillMonth", month);

        // Return the Thymeleaf template for the contribution form.
        return "admin/contribution-form";
    }

    /** Saves a reviewed multi-employee batch atomically; no payment is recorded. */
    @PostMapping("/admin/contributions/bulk")
    public String createContributionBatch(
            @Valid @ModelAttribute("bulkContributionForm") BatchContributionForm form,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            String message = bindingResult.getFieldErrors().stream()
                    .map(error -> error.getDefaultMessage() == null
                            ? "Review the contribution batch fields."
                            : error.getDefaultMessage())
                    .findFirst()
                    .orElse("Review the contribution batch fields.");
            model.addAttribute("errorMessage", message);
            populateBatchFormModel(form, model);
            return "admin/contribution-form";
        }

        try {
            List<ContributionService.BatchContributionInput> entries = form.getEntries().stream()
                    .map(entry -> new ContributionService.BatchContributionInput(
                            entry.getEmployeeId(), entry.getContributionAmount(), entry.getIntendedCollectionMethod(),
                            entry.getShareValue(), entry.getRemarks()))
                    .toList();
            int savedCount = contributionService.createContributionBatch(
                    form.getContributionMonth(), entries);

            redirectAttributes.addFlashAttribute("successMessage",
                    savedCount + (savedCount == 1
                            ? " pending contribution created. No payment was recorded."
                            : " pending contributions created. No payments were recorded."));
            redirectAttributes.addAttribute("tab", "member-operations");
            redirectAttributes.addAttribute("year", form.getContributionMonth().getYear());
            redirectAttributes.addAttribute("month", form.getContributionMonth().getMonthValue());
            if (savedCount == 1) {
                // Use the already submitted business ID; do not dereference a lazy
                // Employee relationship after the service transaction has closed.
                redirectAttributes.addAttribute("employeeId",
                        form.getEntries().get(0).getEmployeeId().trim());
            }
            return "redirect:/admin/contribution-workspace";
        } catch (DuplicateContributionException | EmployeeNotFoundException
                 | IllegalArgumentException | IllegalStateException exception) {
            model.addAttribute("errorMessage", exception.getMessage());
            populateBatchFormModel(form, model);
            return "admin/contribution-form";
        } catch (Exception exception) {
            // Keep the exception in server logs while showing a safe in-page error.
            log.error("Contribution batch save failed for month {} ({} rows)",
                    form.getContributionMonth(),
                    form.getEntries() == null ? 0 : form.getEntries().size(), exception);
            model.addAttribute("errorMessage",
                    "The batch could not be saved. No success was confirmed. Review the entries and try again.");
            populateBatchFormModel(form, model);
            return "admin/contribution-form";
        }
    }

    private static void populateBatchFormModel(BatchContributionForm form, Model model) {
        LocalDate selectedMonth = form == null ? null : form.getContributionMonth();
        if (selectedMonth == null) selectedMonth = LocalDate.now(BANK_ZONE).withDayOfMonth(1);
        String firstEmployeeId = form != null && form.getEntries() != null
                && !form.getEntries().isEmpty() && form.getEntries().get(0) != null
                ? form.getEntries().get(0).getEmployeeId() : "";
        model.addAttribute("contribution", new Contribution());
        model.addAttribute("editMode", false);
        model.addAttribute("prefillEmployeeId", firstEmployeeId == null ? "" : firstEmployeeId);
        model.addAttribute("prefillYear", selectedMonth.getYear());
        model.addAttribute("prefillMonth", selectedMonth.getMonthValue());
    }


    // ==================== Create Contribution ==================== //

    // Handles form submission for creating a new contribution.
    @PostMapping("/admin/contributions")
    public String createContribution(
            @RequestParam String employeeId,
            @Valid @ModelAttribute("contribution") Contribution contribution,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes
    ) {

        // ==================== Validate Employee ID ==================== //

        // Ensure the employee ID is provided before processing the form.
        if (employeeId == null || employeeId.trim().isEmpty()) {

            model.addAttribute("errorMessage", "Employee ID is required.");
            model.addAttribute("employeeId", employeeId);
            model.addAttribute("editMode", false);

            // Return to the form and display the validation error.
            return "admin/contribution-form";
        }

        // ==================== Validate Contribution Fields ==================== //

        // Check whether the contribution fields satisfy validation rules.
        if (bindingResult.hasErrors()) {

            model.addAttribute("employeeId", employeeId);
            model.addAttribute("editMode", false);

            // Return to the form so the user can correct invalid fields.
            return "admin/contribution-form";
        }

        try {

            // Create the contribution using the official bank Employee ID.
            contributionService.createContribution(
                    employeeId.trim(),
                    contribution
            );

            // Display a success message after redirecting to the list page.
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Contribution created successfully."
            );

            // Redirect to prevent accidental duplicate form submissions.
            return "redirect:/admin/contributions";

        } catch (DuplicateContributionException exception) {

            // Display the business validation error, such as a duplicate contribution.
            model.addAttribute("errorMessage", exception.getMessage());
            model.addAttribute("employeeId", employeeId);
            model.addAttribute("editMode", false);

            // Return to the form so the user can review and correct the issue.
            return "admin/contribution-form";
        }
    }


    // ==================== View Contribution Details ==================== //

    // Displays the details of a specific contribution record.
    @GetMapping("/admin/contributions/{id}")
    public String viewContributionDetails(
            @PathVariable Long id,
            Model model
    ) {

        // Retrieve the contribution record using its database ID.
        Contribution contribution =
                contributionService.findContributionById(id);

        // Pass the contribution details to the Thymeleaf template.
        model.addAttribute("contribution", contribution);

        // Return the contribution details page.
        return "admin/contribution-details";
    }


    // ==================== Show Edit Contribution Form ==================== //

    // Displays the edit form for an existing contribution.
    @GetMapping("/admin/contributions/{id}/edit")
    public String showEditContributionForm(
            @PathVariable Long id,
            Model model,
            RedirectAttributes redirectAttributes
    ) {

        // Retrieve the contribution record using its database ID.
        Contribution contribution =
                contributionService.findContributionById(id);

        // Allow editing only when the contribution status is PENDING.
        if (contribution.getContributionStatus()
                != Contribution.ContributionStatus.PENDING) {

            // Display an error message if the contribution cannot be edited.
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Only pending contributions can be edited."
            );

            // Redirect to the contribution details page.
            return "redirect:/admin/contributions/" + id;
        }

        // Pass the existing contribution data to the Thymeleaf form.
        model.addAttribute("contribution", contribution);

        // Provide the associated employee's official Employee ID.
        model.addAttribute(
                "employeeId",
                contribution.getEmployee().getEmployeeId()
        );

        // Enable edit mode to distinguish editing from creating.
        model.addAttribute("editMode", true);

        // Return the shared contribution form in edit mode.
        return "admin/contribution-form";
    }



    // ==================== Update Contribution ==================== //

    // Handles form submission for updating an existing contribution.
    @PostMapping("/admin/contributions/{id}/update")
    public String updateContribution(
            @PathVariable Long id,
            @Valid @ModelAttribute("contribution") Contribution contribution,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes
    ) {

        // ==================== Validate Contribution Fields ==================== //

        // Check whether the submitted contribution fields contain validation errors.
        if (bindingResult.hasErrors()) {

            // Retrieve the existing record to preserve the associated employee ID.
            Contribution existingContribution =
                    contributionService.findContributionById(id);

            // Pass the employee ID back to the form.
            model.addAttribute(
                    "employeeId",
                    existingContribution.getEmployee().getEmployeeId()
            );

            // Keep the form in edit mode.
            model.addAttribute("editMode", true);

            // Return to the form so the user can correct invalid fields.
            return "admin/contribution-form";
        }

        try {

            // Update the existing contribution using its database ID.
            contributionService.updateContribution(
                    id,
                    contribution
            );

            // Display a success message after the update completes.
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Contribution updated successfully."
            );

            // Redirect to the updated contribution's details page.
            return "redirect:/admin/contributions/" + id;

        } catch (DuplicateContributionException | IllegalStateException exception) {

            // Retrieve the existing record to restore its employee information.
            Contribution existingContribution =
                    contributionService.findContributionById(id);

            // Preserve the employee ID when returning to the edit form.
            model.addAttribute(
                    "employeeId",
                    existingContribution.getEmployee().getEmployeeId()
            );

            // Display the error message returned by the service layer.
            model.addAttribute("errorMessage", exception.getMessage());

            // Keep the form in edit mode after the error.
            model.addAttribute("editMode", true);

            // Return to the form so the user can review the error.
            return "admin/contribution-form";
        }
    }



    // ==================== Mark Contribution Paid ==================== //

    // Handles the action of marking a contribution as paid.
    @PostMapping("/admin/contributions/{id}/pay")
    public String markContributionPaid(
            @PathVariable Long id,
            @RequestParam LocalDate paymentDate,
            RedirectAttributes redirectAttributes
    ) {

        try {

            // Update the contribution status to PAID through the service layer.
            contributionService.markContributionAsPaid(id, paymentDate);

            // Display a success message after the status is updated.
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Contribution marked as paid successfully."
            );

            // Redirect to the contribution details page.
            return "redirect:/admin/contributions/" + id;

        } catch (IllegalStateException exception) {

            // Display the error message if the contribution cannot be marked as paid.
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            // Redirect to the contribution details page to show the error.
            return "redirect:/admin/contributions/" + id;
        }
    }



    // ==================== Cancel Contribution ==================== //

    // Handles the action of canceling an existing contribution.
    @PostMapping("/admin/contributions/{id}/cancel")
    public String cancelContribution(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes
    ) {

        try {

            // Cancel the contribution through the service layer.
            contributionService.cancelContribution(id);

            // Display a success message after the contribution is canceled.
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Contribution canceled successfully."
            );

            // Redirect to the contribution details page.
            return "redirect:/admin/contributions/" + id;

        } catch (IllegalStateException exception) {

            // Display the error message if the contribution cannot be canceled.
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            // Redirect to the details page to display the error message.
            return "redirect:/admin/contributions/" + id;
        }
    }



}
