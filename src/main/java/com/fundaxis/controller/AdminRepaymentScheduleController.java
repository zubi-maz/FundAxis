package com.fundaxis.controller; // Controller package

import com.fundaxis.entity.Loan; // Parent loan entity
import com.fundaxis.entity.RepaymentSchedule; // Installment entity
import com.fundaxis.service.LoanService; // Used to find a loan by its application ID
import com.fundaxis.service.RepaymentScheduleService; // Business rules for schedules

import jakarta.validation.Valid; // Triggers entity validation on the form object

import org.springframework.security.access.prepost.PreAuthorize; // Role check on the controller
import org.springframework.stereotype.Controller; // Marks a Spring MVC controller
import org.springframework.ui.Model; // Passes data to Thymeleaf
import org.springframework.validation.BindingResult; // Holds validation errors
import org.springframework.web.bind.WebDataBinder; // Lets us restrict which form fields are bound
import org.springframework.web.bind.annotation.*; // Mapping and parameter annotations
import org.springframework.web.servlet.mvc.support.RedirectAttributes; // Flash messages across redirects

import java.math.BigDecimal; // Money type for totals
import java.util.ArrayList; // Copy a list before sorting it
import java.util.Comparator; // Sort installments
import java.util.List; // List type
import java.util.Objects; // Skip null amounts
import java.util.function.Function; // Lets one helper sum any amount field


// Handles Repayment Schedule Management pages for administrators.
@Controller // Returns Thymeleaf view names
@PreAuthorize("hasRole('ADMIN')") // Only administrators may use these pages
public class AdminRepaymentScheduleController { // Repayment schedule admin controller


    // ==================== Constructor Dependency Injection ==================== //

    private final RepaymentScheduleService repaymentScheduleService; // Schedule business logic
    private final LoanService loanService; // Loan lookups


    public AdminRepaymentScheduleController(RepaymentScheduleService repaymentScheduleService, LoanService loanService) { // Constructor injection
        this.repaymentScheduleService = repaymentScheduleService; // Store the schedule service
        this.loanService = loanService; // Store the loan service
    }


    // ==================== Form Field Protection ==================== //

    // Only these five fields may be set from the browser. Paid amount, status and dates stay backend-controlled.
    @InitBinder("schedule") // Applies only to the "schedule" form object
    public void restrictScheduleFields(WebDataBinder binder) { // Runs before each form is bound
        binder.setAllowedFields("installmentNumber", "dueDate", "principalAmount", "interestAmount", "totalInstallmentAmount"); // Ignore every other posted field
    }


    // ============================================================
    // 1. REPAYMENT SCHEDULE LIST / SEARCH / FILTER
    // ============================================================ //

    // Displays all repayment schedules with summary counts, search and a status filter.
    @GetMapping("/admin/repayment-schedules") // GET /admin/repayment-schedules
    public String repaymentScheduleManagementPage(
            @RequestParam(required = false) String keyword, // Search text
            @RequestParam(required = false) String installmentStatus, // Selected status filter
            Model model // Data for the view
    ) {

        List<RepaymentSchedule> allSchedules = repaymentScheduleService.getAllRepaymentSchedulesList(); // Load every schedule

        model.addAttribute("totalSchedules", allSchedules.size()); // Total card
        model.addAttribute("pendingSchedules", countByStatus(allSchedules, RepaymentSchedule.InstallmentStatus.PENDING)); // Pending card
        model.addAttribute("partialSchedules", countByStatus(allSchedules, RepaymentSchedule.InstallmentStatus.PARTIAL)); // Partial card
        model.addAttribute("paidSchedules", countByStatus(allSchedules, RepaymentSchedule.InstallmentStatus.PAID)); // Paid card
        model.addAttribute("overdueSchedules", countByStatus(allSchedules, RepaymentSchedule.InstallmentStatus.OVERDUE)); // Overdue card
        model.addAttribute("cancelledSchedules", countByStatus(allSchedules, RepaymentSchedule.InstallmentStatus.CANCELLED)); // Cancelled card

        List<RepaymentSchedule> filteredSchedules = allSchedules.stream() // Start filtering
                .filter(schedule -> matchesKeyword(schedule, keyword)) // Apply the keyword search
                .filter(schedule -> matchesStatus(schedule, installmentStatus)) // Apply the status filter
                .toList(); // Collect the result

        model.addAttribute("schedules", filteredSchedules); // Rows for the table
        model.addAttribute("keyword", keyword); // Keep the typed search text
        model.addAttribute("selectedInstallmentStatus", installmentStatus); // Keep the chosen status

        return "admin/repayment-schedules"; // Render the list page
    }


    // ============================================================
    // 2. SHOW ADD REPAYMENT SCHEDULE FORM
    // ============================================================ //

    // Displays the form for creating a new repayment schedule.
    @GetMapping("/admin/repayment-schedules/new") // GET /admin/repayment-schedules/new
    public String showAddRepaymentScheduleForm(
            @RequestParam(required = false) String loanApplicationId,
            @RequestParam(defaultValue = "false") boolean returnToLoanDetails,
            Model model) { // Open the empty form

        model.addAttribute("schedule", new RepaymentSchedule()); // Empty object for form binding
        model.addAttribute("loanApplicationId", loanApplicationId == null ? "" : loanApplicationId); // Preserve context from Loan Details
        model.addAttribute("returnToLoanDetails", returnToLoanDetails);
        model.addAttribute("editMode", false); // Create mode

        return "admin/repayment-schedule-form"; // Render the shared form
    }


    // ============================================================
    // 3. CREATE REPAYMENT SCHEDULE
    // ============================================================ //

    // Creates a new PENDING repayment schedule for an existing loan.
    @PostMapping("/admin/repayment-schedules") // POST /admin/repayment-schedules
    public String createRepaymentSchedule(
            @RequestParam String loanApplicationId, // Business ID typed by the admin
            @RequestParam(defaultValue = "false") boolean returnToLoanDetails,
            @Valid @ModelAttribute("schedule") RepaymentSchedule schedule, // Bound and validated form object
            BindingResult bindingResult, // Validation errors
            Model model, // Data for the view
            RedirectAttributes redirectAttributes // Flash messages
    ) {

        if (loanApplicationId == null || loanApplicationId.trim().isEmpty()) { // Loan ID is required
            model.addAttribute("errorMessage", "Loan Application ID is required."); // Show the problem
            model.addAttribute("loanApplicationId", loanApplicationId); // Keep what was typed
            model.addAttribute("returnToLoanDetails", returnToLoanDetails);
            model.addAttribute("editMode", false); // Stay in create mode
            return "admin/repayment-schedule-form"; // Back to the form
        }

        if (bindingResult.hasErrors()) { // Field validation failed
            model.addAttribute("loanApplicationId", loanApplicationId); // Keep what was typed
            model.addAttribute("returnToLoanDetails", returnToLoanDetails);
            model.addAttribute("editMode", false); // Stay in create mode
            return "admin/repayment-schedule-form"; // Back to the form
        }

        try { // Business rules can still reject the request

            Loan existingLoan = loanService.getLoanByApplicationId(loanApplicationId.trim()); // Find the parent loan by its business ID
            repaymentScheduleService.createRepaymentSchedule(existingLoan.getId(), schedule); // Service needs the internal loan ID

            redirectAttributes.addFlashAttribute("successMessage", "Repayment schedule created successfully."); // Success message
            if (returnToLoanDetails) {
                return "redirect:/admin/loans/" + existingLoan.getLoanApplicationId();
            }
            return "redirect:/admin/repayment-schedules"; // Go to the list

        } catch (RuntimeException exception) { // Loan not found, duplicate installment and similar

            model.addAttribute("errorMessage", exception.getMessage()); // Show the business error
            model.addAttribute("loanApplicationId", loanApplicationId); // Keep what was typed
            model.addAttribute("returnToLoanDetails", returnToLoanDetails);
            model.addAttribute("editMode", false); // Stay in create mode
            return "admin/repayment-schedule-form"; // Back to the form
        }
    }


    // ============================================================
    // 4. VIEW REPAYMENT SCHEDULE DETAILS (WITH THE FULL LOAN PLAN)
    // ============================================================ //

    // Displays one installment together with every installment of its parent loan.
    @GetMapping("/admin/repayment-schedules/{id}") // GET /admin/repayment-schedules/{id}
    public String viewRepaymentScheduleDetails(@PathVariable Long id, Model model) { // Open the details page

        RepaymentSchedule schedule = repaymentScheduleService.getRepaymentScheduleById(id); // Load the installment being viewed
        model.addAttribute("schedule", schedule); // Make it available to the template

        List<RepaymentSchedule> loanSchedules; // Every installment of the parent loan
        if (schedule.getLoan() != null) { // Normal case
            loanSchedules = new ArrayList<>(repaymentScheduleService.getRepaymentSchedulesListByLoanId(schedule.getLoan().getId())); // Copy so sorting is safe
        } else { // Defensive case, a schedule without a loan
            loanSchedules = new ArrayList<>(List.of(schedule)); // Show just this installment
        }
        loanSchedules.sort(Comparator.comparing(RepaymentSchedule::getInstallmentNumber, Comparator.nullsLast(Comparator.naturalOrder()))); // Order by installment number
        model.addAttribute("loanSchedules", loanSchedules); // Rows for the installment table

        model.addAttribute("loanInstallmentCount", loanSchedules.size()); // The Y in "Installment X of Y"
        model.addAttribute("loanTotalPrincipal", sumAmounts(loanSchedules, RepaymentSchedule::getPrincipalAmount)); // Total principal
        model.addAttribute("loanTotalInterest", sumAmounts(loanSchedules, RepaymentSchedule::getInterestAmount)); // Total interest
        model.addAttribute("loanTotalScheduledAmount", sumAmounts(loanSchedules, RepaymentSchedule::getTotalInstallmentAmount)); // Total scheduled repayment
        model.addAttribute("loanTotalPaidAmount", sumAmounts(loanSchedules, RepaymentSchedule::getPaidAmount)); // Total paid
        model.addAttribute("loanTotalRemainingAmount", sumAmounts(loanSchedules, RepaymentSchedule::getRemainingAmount)); // Total remaining

        int currentIndex = -1; // Position of this installment in the sorted list
        for (int i = 0; i < loanSchedules.size(); i++) { // Look through every installment
            if (Objects.equals(loanSchedules.get(i).getId(), schedule.getId())) { // Found the one being viewed
                currentIndex = i; // Remember where it is
                break; // Stop searching
            }
        }

        Long previousScheduleId = currentIndex > 0 ? loanSchedules.get(currentIndex - 1).getId() : null; // Null on the first installment
        Long nextScheduleId = (currentIndex >= 0 && currentIndex < loanSchedules.size() - 1) ? loanSchedules.get(currentIndex + 1).getId() : null; // Null on the last installment
        model.addAttribute("previousScheduleId", previousScheduleId); // Template hides the button when null
        model.addAttribute("nextScheduleId", nextScheduleId); // Template hides the button when null

        return "admin/repayment-schedule-details"; // Render the details page
    }


    // ============================================================
    // 5. SHOW EDIT PENDING SCHEDULE FORM
    // ============================================================ //

    // Opens the shared form in Edit mode.
    @GetMapping("/admin/repayment-schedules/{id}/edit") // GET /admin/repayment-schedules/{id}/edit
    public String showEditRepaymentScheduleForm(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) { // Open the edit form

        RepaymentSchedule schedule = repaymentScheduleService.getRepaymentScheduleById(id); // Load the installment

        if (schedule.getInstallmentStatus() != RepaymentSchedule.InstallmentStatus.PENDING) { // Only PENDING schedules can be edited
            redirectAttributes.addFlashAttribute("errorMessage", "Only pending repayment schedules can be edited."); // Explain why
            return "redirect:/admin/repayment-schedules/" + id; // Back to the details page
        }

        model.addAttribute("schedule", schedule); // Pre-fill the form
        model.addAttribute("loanApplicationId", schedule.getLoan() != null ? schedule.getLoan().getLoanApplicationId() : ""); // Show the parent loan ID
        model.addAttribute("editMode", true); // Edit mode

        return "admin/repayment-schedule-form"; // Render the shared form
    }


    // ============================================================
    // 6. UPDATE PENDING REPAYMENT SCHEDULE
    // ============================================================ //

    // Updates the administrator-editable values of an existing PENDING schedule.
    @PostMapping("/admin/repayment-schedules/{id}/update") // POST /admin/repayment-schedules/{id}/update
    public String updateRepaymentSchedule(
            @PathVariable Long id, // Database ID of the installment
            @Valid @ModelAttribute("schedule") RepaymentSchedule schedule, // Bound and validated form object
            BindingResult bindingResult, // Validation errors
            Model model, // Data for the view
            RedirectAttributes redirectAttributes // Flash messages
    ) {

        RepaymentSchedule existingSchedule = repaymentScheduleService.getRepaymentScheduleById(id); // Original record for the parent loan
        String parentLoanApplicationId = existingSchedule.getLoan() != null ? existingSchedule.getLoan().getLoanApplicationId() : ""; // Shown read-only in the form

        schedule.setId(id); // FIX: restore the id so the form action and links still work after an error

        if (bindingResult.hasErrors()) { // Field validation failed
            model.addAttribute("loanApplicationId", parentLoanApplicationId); // Keep the parent loan visible
            model.addAttribute("editMode", true); // Stay in edit mode
            return "admin/repayment-schedule-form"; // Back to the form
        }

        try { // Business rules can still reject the update

            repaymentScheduleService.updateRepaymentSchedule(id, schedule); // Service applies the rules and saves

            redirectAttributes.addFlashAttribute("successMessage", "Repayment schedule updated successfully."); // Success message
            return "redirect:/admin/repayment-schedules/" + id; // Go to the details page

        } catch (RuntimeException exception) { // Not pending, invalid amounts and similar

            model.addAttribute("errorMessage", exception.getMessage()); // Show the business error
            model.addAttribute("loanApplicationId", parentLoanApplicationId); // Keep the parent loan visible
            model.addAttribute("editMode", true); // Stay in edit mode
            return "admin/repayment-schedule-form"; // Back to the form
        }
    }


    // ============================================================
    // 7. MARK REPAYMENT SCHEDULE AS OVERDUE
    // ============================================================ //

    // Marks an eligible unpaid installment as OVERDUE (the service decides eligibility).
    @PostMapping("/admin/repayment-schedules/{id}/mark-overdue") // POST /admin/repayment-schedules/{id}/mark-overdue
    public String markRepaymentScheduleAsOverdue(@PathVariable Long id, RedirectAttributes redirectAttributes) { // Handle the action

        try { // The service may reject the request
            repaymentScheduleService.markAsOverdue(id); // Apply the OVERDUE transition
            redirectAttributes.addFlashAttribute("successMessage", "Repayment schedule marked as overdue successfully."); // Success message
        } catch (RuntimeException exception) { // Not past due, wrong status and similar
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage()); // Show the reason on the details page
        }

        return "redirect:/admin/repayment-schedules/" + id; // Back to the details page
    }


    // ============================================================
    // 8. CANCEL REPAYMENT SCHEDULE
    // ============================================================ //

    // Cancels an untouched PENDING repayment schedule.
    @PostMapping("/admin/repayment-schedules/{id}/cancel") // POST /admin/repayment-schedules/{id}/cancel
    public String cancelRepaymentSchedule(@PathVariable Long id, RedirectAttributes redirectAttributes) { // Handle the action

        try { // The service may reject the request
            repaymentScheduleService.cancelRepaymentSchedule(id); // Apply the CANCELLED transition
            redirectAttributes.addFlashAttribute("successMessage", "Repayment schedule cancelled successfully."); // Success message
        } catch (RuntimeException exception) { // Already paid, wrong status and similar
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage()); // Show the reason on the details page
        }

        return "redirect:/admin/repayment-schedules/" + id; // Back to the details page
    }


    // ============================================================
    // PRIVATE HELPERS
    // ============================================================ //

    // Counts schedules that have the given status.
    private long countByStatus(List<RepaymentSchedule> schedules, RepaymentSchedule.InstallmentStatus status) { // Used by the summary cards
        return schedules.stream() // Walk through the schedules
                .filter(schedule -> schedule.getInstallmentStatus() == status) // Keep matching ones
                .count(); // Count them
    }


    // True when the schedule matches the search text (or when no search text was given).
    private boolean matchesKeyword(RepaymentSchedule schedule, String keyword) { // Used by the list filter

        if (keyword == null || keyword.trim().isEmpty()) { // No search text
            return true; // Everything matches
        }

        String searchValue = keyword.trim().toLowerCase(); // Case-insensitive search
        Loan loan = schedule.getLoan(); // Parent loan, may be null

        boolean loanApplicationIdMatches = loan != null && loan.getLoanApplicationId() != null
                && loan.getLoanApplicationId().toLowerCase().contains(searchValue); // Loan Application ID

        boolean employeeIdMatches = loan != null && loan.getEmployee() != null && loan.getEmployee().getEmployeeId() != null
                && loan.getEmployee().getEmployeeId().toLowerCase().contains(searchValue); // Employee ID

        boolean employeeNameMatches = loan != null && loan.getEmployee() != null && loan.getEmployee().getFullName() != null
                && loan.getEmployee().getFullName().toLowerCase().contains(searchValue); // Employee name

        boolean installmentNumberMatches = schedule.getInstallmentNumber() != null
                && schedule.getInstallmentNumber().toString().contains(searchValue); // Installment number

        return loanApplicationIdMatches || employeeIdMatches || employeeNameMatches || installmentNumberMatches; // Any field may match
    }


    // True when the schedule has the selected status (or when no status was selected).
    private boolean matchesStatus(RepaymentSchedule schedule, String installmentStatus) { // Used by the list filter
        return installmentStatus == null || installmentStatus.isBlank() // No status selected
                || (schedule.getInstallmentStatus() != null && schedule.getInstallmentStatus().name().equalsIgnoreCase(installmentStatus)); // Or the names match
    }


    // Adds up one amount field across a list of installments, ignoring missing values.
    private BigDecimal sumAmounts(List<RepaymentSchedule> schedules, Function<RepaymentSchedule, BigDecimal> getter) { // Getter picks the amount
        return schedules.stream() // Walk through the installments
                .map(getter) // Read the chosen amount
                .filter(Objects::nonNull) // Skip nulls
                .reduce(BigDecimal.ZERO, BigDecimal::add); // Add everything together
    }
}
