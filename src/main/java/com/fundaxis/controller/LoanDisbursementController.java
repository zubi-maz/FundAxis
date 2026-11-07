package com.fundaxis.controller;

import com.fundaxis.dto.LoanDisbursementCompleteRequest;
import com.fundaxis.dto.LoanDisbursementFailureRequest;
import com.fundaxis.dto.LoanDisbursementCancellationRequest;
import com.fundaxis.entity.LoanDisbursement;
import com.fundaxis.service.LoanDisbursementService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/loan-disbursements")
public class LoanDisbursementController {

    private final LoanDisbursementService loanDisbursementService;

    ///---------- Inject LoanDisbursementService ----------
    public LoanDisbursementController(LoanDisbursementService loanDisbursementService) {
        this.loanDisbursementService = loanDisbursementService;

    }


    ///==================== Create Loan Disbursement ====================///

    ///---------- Create A New Disbursement Request For A Loan ----------
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/loan/{loanId}")
    public ResponseEntity<LoanDisbursement> createDisbursement(@PathVariable Long loanId, @Valid @RequestBody LoanDisbursement disbursement) {

        LoanDisbursement createdDisbursement = loanDisbursementService.createDisbursement(loanId, disbursement);

        return ResponseEntity.status(HttpStatus.CREATED).body(createdDisbursement);

    }


    ///==================== Read Loan Disbursement ====================///

    ///---------- Get All Disbursements ----------
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping
    public ResponseEntity<List<LoanDisbursement>> getAllDisbursements() {

        return ResponseEntity.ok(loanDisbursementService.getAllDisbursements());

    }


    ///---------- Get One Disbursement By Database ID (id) ----------
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping("/{id}")
    public ResponseEntity<LoanDisbursement> getDisbursementById(@PathVariable Long id) {

        return ResponseEntity.ok(loanDisbursementService.getDisbursementById(id));

    }


    ///---------- Get All Disbursements For A Specific Loan ----------
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping("/loan/{loanId}")
    public ResponseEntity<List<LoanDisbursement>> getDisbursementsByLoanId(@PathVariable Long loanId) {

        return ResponseEntity.ok(loanDisbursementService.getDisbursementsByLoanId(loanId));

    }


    ///---------- Get All Disbursements Belonging To An Employee's Loans ----------
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping("/employee/{employeeId}")
    public ResponseEntity<List<LoanDisbursement>> getEmployeeDisbursements(@PathVariable String employeeId) {

        return ResponseEntity.ok(loanDisbursementService.getEmployeeDisbursements(employeeId));

    }


    ///---------- Get All Disbursements With A Specific Status ----------
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping("/status/{disbursementStatus}")
    public ResponseEntity<List<LoanDisbursement>> getDisbursementsByStatus(@PathVariable LoanDisbursement.DisbursementStatus disbursementStatus) {

        return ResponseEntity.ok(loanDisbursementService.getDisbursementsByStatus(disbursementStatus));

    }


    ///---------- Get All Disbursements Using A Specific Payment Method ----------
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping("/payment-method/{paymentMethod}")
    public ResponseEntity<List<LoanDisbursement>> getDisbursementsByPaymentMethod(@PathVariable LoanDisbursement.PaymentMethod paymentMethod) {

        return ResponseEntity.ok(loanDisbursementService.getDisbursementsByPaymentMethod(paymentMethod));

    }


    ///---------- Get One Disbursement Using Its Transaction Reference ----------
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping("/reference/{referenceNumber}")
    public ResponseEntity<LoanDisbursement> getDisbursementByReferenceNumber(@PathVariable String referenceNumber) {

        return ResponseEntity.ok(loanDisbursementService.getDisbursementByReferenceNumber(referenceNumber));

    }


    ///---------- Get An Employee's Disbursements With A Specific Status ----------
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping("/employee/{employeeId}/status/{disbursementStatus}")
    public ResponseEntity<List<LoanDisbursement>> getEmployeeDisbursementsByStatus(@PathVariable String employeeId, @PathVariable LoanDisbursement.DisbursementStatus disbursementStatus) {

        return ResponseEntity.ok(loanDisbursementService.getEmployeeDisbursementsByStatus(employeeId, disbursementStatus));

    }


    ///---------- Get A Loan's Disbursements With A Specific Status ----------
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping("/loan/{loanId}/status/{disbursementStatus}")
    public ResponseEntity<List<LoanDisbursement>> getLoanDisbursementsByStatus(@PathVariable Long loanId, @PathVariable LoanDisbursement.DisbursementStatus disbursementStatus) {

        return ResponseEntity.ok(loanDisbursementService.getLoanDisbursementsByStatus(loanId, disbursementStatus));

    }


    ///==================== Disbursement Amount ====================///

    ///---------- Get Total Amount Successfully Disbursed For A Loan ----------
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping("/loan/{loanId}/total-disbursed")
    public ResponseEntity<BigDecimal> getTotalDisbursedAmount(@PathVariable Long loanId) {

        return ResponseEntity.ok(loanDisbursementService.getTotalDisbursedAmount(loanId));

    }


    ///---------- Get Remaining Approved Amount Available For Disbursement ----------
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping("/loan/{loanId}/remaining-amount")
    public ResponseEntity<BigDecimal> getRemainingApprovedAmount(@PathVariable Long loanId) {

        return ResponseEntity.ok(loanDisbursementService.getRemainingApprovedAmount(loanId));

    }


    ///==================== Loan Disbursement Workflow ====================///

    ///---------- Start Processing A Pending Disbursement ----------
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @PatchMapping("/{id}/process")
    public ResponseEntity<LoanDisbursement> startProcessing(@PathVariable Long id) {

        return ResponseEntity.ok(loanDisbursementService.startProcessing(id));

    }


    ///---------- Complete A Processing Disbursement ----------
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @PatchMapping("/{id}/complete")
    public ResponseEntity<LoanDisbursement> completeDisbursement(@PathVariable Long id, @Valid @RequestBody LoanDisbursementCompleteRequest request, Authentication authentication) {

        LoanDisbursement disbursement = loanDisbursementService.completeDisbursement(id, authentication.getName(), request.getReferenceNumber());

        return ResponseEntity.ok(disbursement);

    }


    ///---------- Mark A Processing Disbursement As Failed ----------
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @PatchMapping("/{id}/fail")
    public ResponseEntity<LoanDisbursement> failDisbursement(@PathVariable Long id, @Valid @RequestBody LoanDisbursementFailureRequest request) {

        LoanDisbursement disbursement = loanDisbursementService.failDisbursement(id, request.getFailureReason());

        return ResponseEntity.ok(disbursement);

    }


    ///---------- Cancel A Pending Disbursement ----------
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @PatchMapping("/{id}/cancel")
    public ResponseEntity<LoanDisbursement> cancelDisbursement(@PathVariable Long id, @Valid @RequestBody LoanDisbursementCancellationRequest request) {

        LoanDisbursement disbursement = loanDisbursementService.cancelDisbursement(id, request.getCancellationReason());

        return ResponseEntity.ok(disbursement);

    }
}
