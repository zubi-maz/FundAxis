package com.fundaxis.controller;

import com.fundaxis.dto.LoanApprovalRequest;
import com.fundaxis.dto.LoanEligibilityRequest;
import com.fundaxis.dto.LoanRejectionRequest;
import com.fundaxis.entity.Loan;
import com.fundaxis.service.LoanService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;

import java.util.List;

@RestController
@RequestMapping("/api/loans")
public class LoanController {

    private final LoanService loanService;

    public LoanController(LoanService loanService) {
        this.loanService = loanService;

    }


    ///==================== Create Loan ====================///

    ///---------- Create A New Loan For An Employee ----------

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/employee/{employeeId}")
    public ResponseEntity<Loan> createLoan(@PathVariable String employeeId,
                                           @Valid @RequestBody Loan loan,
                                           Authentication authentication) {

        Loan createdLoan = loanService.createLoan(employeeId, null, null, loan,
                authentication.getName());

        return ResponseEntity.status(HttpStatus.CREATED).body(createdLoan);

    }


    ///==================== Read Loan ====================///

    ///---------- Get All Loans ----------
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping
    public ResponseEntity<List<Loan>> getAllLoansList() {

        return ResponseEntity.ok(loanService.getAllLoansList());

    }


    ///---------- Get Loan By Database ID (id) ----------
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping("/id/{id}")
    public ResponseEntity<Loan> getLoanById(@PathVariable Long id) {

        return ResponseEntity.ok(loanService.getLoanById(id));

    }


    ///---------- Get Loan By Application ID ----------
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping("/application/{loanApplicationId}")
    public ResponseEntity<Loan> getLoanByApplicationId(@PathVariable String loanApplicationId) {

        return ResponseEntity.ok(loanService.getLoanByApplicationId(loanApplicationId));

    }


    ///---------- Get All Loans Of An Employee ----------
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping("/employee/{employeeId}")
    public ResponseEntity<List<Loan>> getEmployeeLoans(@PathVariable String employeeId) {

        return ResponseEntity.ok(loanService.getEmployeeLoans(employeeId));

    }


    ///---------- Get Loans By Loan Status ----------
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping("/status/{loanStatus}")
    public ResponseEntity<List<Loan>> getLoansByLoanStatus(@PathVariable Loan.LoanStatus loanStatus) {

        return ResponseEntity.ok(loanService.getLoansByLoanStatus(loanStatus));

    }


    ///---------- Get Loans By Eligibility Status ----------
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping("/eligibility/{eligibilityStatus}")
    public ResponseEntity<List<Loan>> getLoansByEligibilityStatus(@PathVariable Loan.EligibilityStatus eligibilityStatus) {

        return ResponseEntity.ok(loanService.getLoansByEligibilityStatus(eligibilityStatus));

    }


    ///---------- Get Loans By Loan Type ----------
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping("/type/{loanType}")
    public ResponseEntity<List<Loan>> getLoansByType(@PathVariable Loan.LoanType loanType) {

        return ResponseEntity.ok(loanService.getLoansByType(loanType));

    }


    ///---------- Get Employee Loans By Status ----------
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping("/employee/{employeeId}/status/{loanStatus}")
    public ResponseEntity<List<Loan>> getEmployeeLoansByStatus(@PathVariable String employeeId, @PathVariable Loan.LoanStatus loanStatus) {

        return ResponseEntity.ok(loanService.getEmployeeLoansByStatus(employeeId, loanStatus));

    }


    ///---------- Get Employee Loans By Eligibility Status ----------
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping("/employee/{employeeId}/eligibility/{eligibilityStatus}")
    public ResponseEntity<List<Loan>> getEmployeeLoansByEligibilityStatus(@PathVariable String employeeId, @PathVariable Loan.EligibilityStatus eligibilityStatus) {

        return ResponseEntity.ok(loanService.getEmployeeLoansByEligibilityStatus(employeeId, eligibilityStatus));

    }


    ///==================== Update Loan ====================///

    ///---------- Update Editable Information Of A DRAFT Loan ----------
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @PutMapping("/{id}")
    public ResponseEntity<Loan> updateLoan(@PathVariable Long id, @Valid @RequestBody Loan loan) {

        Loan updatedLoan = loanService.updateLoan(id, loan);

        return ResponseEntity.ok(updatedLoan);

    }


    ///==================== Loan Workflow ====================///

    ///---------- Submit A DRAFT Loan ----------
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @PatchMapping("/{id}/submit")
    public ResponseEntity<Loan> submitLoan(@PathVariable Long id) {

        return ResponseEntity.ok(loanService.submitLoan(id));

    }


    ///---------- Move A SUBMITTED Loan Into Review ----------
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @PatchMapping("/{id}/review")
    public ResponseEntity<Loan> startLoanReview(@PathVariable Long id) {

        return ResponseEntity.ok(loanService.startLoanReview(id));

    }


    ///---------- Update Eligibility While The Loan Is Under Review ----------
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @PatchMapping("/{id}/eligibility")
    public ResponseEntity<Loan> updateEligibilityStatus(@PathVariable Long id, @Valid @RequestBody LoanEligibilityRequest request) {

        return ResponseEntity.ok(loanService.updateEligibilityStatus(id, request.getEligibilityStatus()));

    }


    ///---------- Approve An Eligible Loan ----------
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id}/approve")
    public ResponseEntity<Loan> approveLoan(@PathVariable Long id,
                                            @Valid @RequestBody LoanApprovalRequest request,
                                            Authentication authentication) {

        return ResponseEntity.ok(loanService.approveLoan(id, request.getApprovedAmount(),
                request.getInterestRate(), request.getApprovedTenureMonths(), authentication.getName()));

    }


    ///---------- Reject A Loan That Is Under Review ----------
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @PatchMapping("/{id}/reject")
    public ResponseEntity<Loan> rejectLoan(@PathVariable Long id,
                                           @Valid @RequestBody LoanRejectionRequest request,
                                           Authentication authentication) {

        return ResponseEntity.ok(loanService.rejectLoan(id, request.getRejectionReason(),
                authentication.getName()));

    }


    ///---------- Cancel A DRAFT Or SUBMITTED Loan ----------
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @PatchMapping("/{id}/cancel")
    public ResponseEntity<Loan> cancelLoan(@PathVariable Long id) {

        return ResponseEntity.ok(loanService.cancelLoan(id));

    }


    // Allow only specified loan fields to be bound from form submissions
    @InitBinder("loan")
    public void initBinder(WebDataBinder binder) {
        binder.setAllowedFields("loanApplicationId", "loanType", "loanPurpose",
                "requestedAmount", "interestRate", "tenureMonths", "applicationDate");
    }


}
