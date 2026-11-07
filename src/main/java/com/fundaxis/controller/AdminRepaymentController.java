package com.fundaxis.controller;

import com.fundaxis.entity.Loan;
import com.fundaxis.entity.Repayment;
import com.fundaxis.entity.RepaymentSchedule;
import com.fundaxis.service.LoanService;
import com.fundaxis.service.RepaymentScheduleService;
import com.fundaxis.service.RepaymentService;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;


@Controller
@RequestMapping("/admin/repayments")
@PreAuthorize("hasRole('ADMIN')")
public class AdminRepaymentController {


    // ==================== Service Dependencies ==================== //

    private final RepaymentService repaymentService;
    private final RepaymentScheduleService repaymentScheduleService;
    private final LoanService loanService;


    // ==================== Constructor Injection ==================== //

    public AdminRepaymentController(
            RepaymentService repaymentService,
            RepaymentScheduleService repaymentScheduleService,
            LoanService loanService
    ) {

        this.repaymentService = repaymentService;
        this.repaymentScheduleService = repaymentScheduleService;
        this.loanService = loanService;
    }


    // ============================================================
    // 1. REPAYMENT MANAGEMENT LIST
    // ============================================================ //

    ///---------- Display Repayment Management Page ----------
    @GetMapping
    public String showRepaymentManagementPage(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String repaymentStatus,
            @RequestParam(required = false) String paymentMethod,
            Model model
    ) {

        // Load every repayment transaction.
        List<Repayment> allRepayments =
                repaymentService.getAllRepayments();


        // ==================== Summary Cards ==================== //

        model.addAttribute(
                "totalRepayments",
                allRepayments.size()
        );


        long completedRepayments =
                allRepayments.stream()
                        .filter(repayment ->
                                repayment.getRepaymentStatus()
                                        == Repayment.RepaymentStatus.COMPLETED
                        )
                        .count();


        long reversedRepayments =
                allRepayments.stream()
                        .filter(repayment ->
                                repayment.getRepaymentStatus()
                                        == Repayment.RepaymentStatus.REVERSED
                        )
                        .count();


        model.addAttribute(
                "completedRepayments",
                completedRepayments
        );


        model.addAttribute(
                "reversedRepayments",
                reversedRepayments
        );


        // Total value of currently active COMPLETED repayments.
        BigDecimal totalCompletedAmount =
                repaymentService.getTotalCompletedRepaymentAmount();


        model.addAttribute(
                "totalCompletedAmount",
                totalCompletedAmount
        );


        // ==================== Search And Filter ==================== //

        List<Repayment> filteredRepayments =
                allRepayments.stream()

                        // ---------------- Keyword Search ---------------- //
                        .filter(repayment -> {

                            if (keyword == null
                                    || keyword.trim().isEmpty()) {

                                return true;
                            }


                            String searchValue =
                                    keyword.trim().toLowerCase();


                            // Search by repayment reference number.
                            boolean referenceNumberMatches =
                                    repayment.getReferenceNumber() != null
                                            && repayment
                                            .getReferenceNumber()
                                            .toLowerCase()
                                            .contains(searchValue);


                            // Search by Loan Application ID.
                            boolean loanApplicationIdMatches =
                                    repayment.getRepaymentSchedule() != null
                                            && repayment
                                            .getRepaymentSchedule()
                                            .getLoan() != null
                                            && repayment
                                            .getRepaymentSchedule()
                                            .getLoan()
                                            .getLoanApplicationId() != null
                                            && repayment
                                            .getRepaymentSchedule()
                                            .getLoan()
                                            .getLoanApplicationId()
                                            .toLowerCase()
                                            .contains(searchValue);


                            // Search by Employee ID.
                            boolean employeeIdMatches =
                                    repayment.getRepaymentSchedule() != null
                                            && repayment
                                            .getRepaymentSchedule()
                                            .getLoan() != null
                                            && repayment
                                            .getRepaymentSchedule()
                                            .getLoan()
                                            .getEmployee() != null
                                            && repayment
                                            .getRepaymentSchedule()
                                            .getLoan()
                                            .getEmployee()
                                            .getEmployeeId() != null
                                            && repayment
                                            .getRepaymentSchedule()
                                            .getLoan()
                                            .getEmployee()
                                            .getEmployeeId()
                                            .toLowerCase()
                                            .contains(searchValue);


                            // Search by Employee Name.
                            boolean employeeNameMatches =
                                    repayment.getRepaymentSchedule() != null
                                            && repayment
                                            .getRepaymentSchedule()
                                            .getLoan() != null
                                            && repayment
                                            .getRepaymentSchedule()
                                            .getLoan()
                                            .getEmployee() != null
                                            && repayment
                                            .getRepaymentSchedule()
                                            .getLoan()
                                            .getEmployee()
                                            .getFullName() != null
                                            && repayment
                                            .getRepaymentSchedule()
                                            .getLoan()
                                            .getEmployee()
                                            .getFullName()
                                            .toLowerCase()
                                            .contains(searchValue);


                            // Search by Installment Number.
                            boolean installmentNumberMatches =
                                    repayment.getRepaymentSchedule() != null
                                            && repayment
                                            .getRepaymentSchedule()
                                            .getInstallmentNumber() != null
                                            && repayment
                                            .getRepaymentSchedule()
                                            .getInstallmentNumber()
                                            .toString()
                                            .contains(searchValue);


                            return referenceNumberMatches
                                    || loanApplicationIdMatches
                                    || employeeIdMatches
                                    || employeeNameMatches
                                    || installmentNumberMatches;
                        })


                        // ---------------- Repayment Status Filter ---------------- //
                        .filter(repayment -> {

                            if (repaymentStatus == null
                                    || repaymentStatus.isBlank()) {

                                return true;
                            }


                            return repayment.getRepaymentStatus() != null
                                    && repayment
                                    .getRepaymentStatus()
                                    .name()
                                    .equalsIgnoreCase(
                                            repaymentStatus
                                    );
                        })


                        // ---------------- Payment Method Filter ---------------- //
                        .filter(repayment -> {

                            if (paymentMethod == null
                                    || paymentMethod.isBlank()) {

                                return true;
                            }


                            return repayment.getPaymentMethod() != null
                                    && repayment
                                    .getPaymentMethod()
                                    .name()
                                    .equalsIgnoreCase(
                                            paymentMethod
                                    );
                        })


                        .toList();


        // Pass filtered repayment records to Thymeleaf.
        model.addAttribute(
                "repayments",
                filteredRepayments
        );


        // Preserve search values.
        model.addAttribute(
                "keyword",
                keyword
        );


        model.addAttribute(
                "selectedRepaymentStatus",
                repaymentStatus
        );


        model.addAttribute(
                "selectedPaymentMethod",
                paymentMethod
        );


        // Enum values for dropdown filters.
        model.addAttribute(
                "repaymentStatuses",
                Repayment.RepaymentStatus.values()
        );


        model.addAttribute(
                "paymentMethods",
                Repayment.PaymentMethod.values()
        );


        return "admin/repayments";
    }


    // ============================================================
    // 2. SHOW ADD REPAYMENT FORM
    // ============================================================ //

    ///---------- Display Form For Recording A New Repayment ----------
    @GetMapping("/new")
    public String showAddRepaymentForm(
            @RequestParam(required = false) String loanApplicationId,
            @RequestParam(required = false) Integer installmentNumber,
            Model model
    ) {

        Repayment repayment =
                new Repayment();


        // Default repayment date to today.
        repayment.setRepaymentDate(
                LocalDate.now()
        );


        model.addAttribute(
                "repayment",
                repayment
        );


        // Business identifiers are not direct Repayment fields.
        model.addAttribute(
                "loanApplicationId",
                loanApplicationId == null ? "" : loanApplicationId.trim()
        );


        model.addAttribute(
                "installmentNumber",
                installmentNumber
        );


        // Populate payment-method dropdown.
        model.addAttribute(
                "paymentMethods",
                Repayment.PaymentMethod.values()
        );


        return "admin/repayment-form";
    }


    // ============================================================
    // 3. CREATE / RECORD REPAYMENT
    // ============================================================ //

    ///---------- Record A New Repayment Against A Schedule ----------
    @PostMapping
    public String createRepayment(
            @RequestParam String loanApplicationId,
            @RequestParam Integer installmentNumber,

            @Valid
            @ModelAttribute("repayment")
            Repayment repayment,

            BindingResult bindingResult,
            Authentication authentication,
            Model model,
            RedirectAttributes redirectAttributes
    ) {


        // ==================== Validate Loan Application ID ==================== //

        if (loanApplicationId == null
                || loanApplicationId.trim().isEmpty()) {

            model.addAttribute(
                    "errorMessage",
                    "Loan Application ID is required."
            );


            prepareRepaymentFormModel(
                    model,
                    loanApplicationId,
                    installmentNumber
            );


            return "admin/repayment-form";
        }


        // ==================== Validate Installment Number ==================== //

        if (installmentNumber == null
                || installmentNumber < 1) {

            model.addAttribute(
                    "errorMessage",
                    "Valid installment number is required."
            );


            prepareRepaymentFormModel(
                    model,
                    loanApplicationId,
                    installmentNumber
            );


            return "admin/repayment-form";
        }


        // ==================== Bean Validation ==================== //

        if (bindingResult.hasErrors()) {

            prepareRepaymentFormModel(
                    model,
                    loanApplicationId,
                    installmentNumber
            );


            return "admin/repayment-form";
        }


        try {

            // Find parent Loan using business Loan Application ID.
            Loan loan =
                    loanService.getLoanByApplicationId(
                            loanApplicationId.trim()
                    );


            // Find repayment schedule using Loan ID + installment number.
            RepaymentSchedule repaymentSchedule =
                    repaymentScheduleService
                            .getRepaymentScheduleByLoanAndInstallmentNumber(
                                    loan.getId(),
                                    installmentNumber
                            );


            // Logged-in administrator username.
            String recordedBy =
                    authentication != null
                            ? authentication.getName()
                            : null;


            // Record repayment.
            // The service also updates paidAmount,
            // remainingAmount and installment status.
            Repayment savedRepayment =
                    repaymentService.recordRepayment(
                            repaymentSchedule.getId(),
                            repayment,
                            recordedBy
                    );


            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Repayment recorded successfully."
            );


            return "redirect:/admin/repayments/"
                    + savedRepayment.getId();


        } catch (RuntimeException exception) {

            model.addAttribute(
                    "errorMessage",
                    exception.getMessage()
            );


            prepareRepaymentFormModel(
                    model,
                    loanApplicationId,
                    installmentNumber
            );


            return "admin/repayment-form";
        }
    }


    // ============================================================
    // 4. VIEW REPAYMENT DETAILS
    // ============================================================ //

    ///---------- Display Details Of One Repayment ----------
    @GetMapping("/{id}")
    public String viewRepaymentDetails(
            @PathVariable Long id,
            Model model
    ) {

        // Find requested repayment.
        Repayment repayment =
                repaymentService.getRepaymentById(id);


        // Parent repayment schedule.
        RepaymentSchedule repaymentSchedule =
                repayment.getRepaymentSchedule();


        model.addAttribute(
                "repayment",
                repayment
        );


        model.addAttribute(
                "schedule",
                repaymentSchedule
        );


        if (repaymentSchedule != null) {

            // Total active COMPLETED repayment value
            // currently applied to this installment.
            BigDecimal scheduleCompletedAmount =
                    repaymentService
                            .getTotalCompletedAmountForSchedule(
                                    repaymentSchedule.getId()
                            );


            model.addAttribute(
                    "scheduleCompletedAmount",
                    scheduleCompletedAmount
            );


            // Load complete transaction history
            // belonging to the same installment.
            List<Repayment> scheduleRepayments =
                    repaymentService
                            .getRepaymentsByScheduleId(
                                    repaymentSchedule.getId()
                            );


            model.addAttribute(
                    "scheduleRepayments",
                    scheduleRepayments
            );
        }


        return "admin/repayment-details";
    }


    // ============================================================
    // 5. REVERSE REPAYMENT
    // ============================================================ //

    ///---------- Reverse An Incorrect Completed Repayment ----------
    @PostMapping("/{id}/reverse")
    public String reverseRepayment(
            @PathVariable Long id,
            @RequestParam String reversalReason,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {

        try {

            // Logged-in administrator performing reversal.
            String reversedBy =
                    authentication != null
                            ? authentication.getName()
                            : null;


            repaymentService.reverseRepayment(
                    id,
                    reversalReason,
                    reversedBy
            );


            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Repayment reversed successfully."
            );


        } catch (RuntimeException exception) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );
        }


        return "redirect:/admin/repayments/" + id;
    }


    // ============================================================
    // HELPER METHOD
    // ============================================================ //

    ///----------
    /// Restore Non-Entity Form Values When The Form
    /// Has Validation Or Business Rule Errors
    ///----------
    private void prepareRepaymentFormModel(
            Model model,
            String loanApplicationId,
            Integer installmentNumber
    ) {

        // Restore Loan Application ID.
        model.addAttribute(
                "loanApplicationId",
                loanApplicationId
        );


        // Restore installment number.
        model.addAttribute(
                "installmentNumber",
                installmentNumber
        );


        // Repopulate payment method dropdown.
        model.addAttribute(
                "paymentMethods",
                Repayment.PaymentMethod.values()
        );
    }
}
