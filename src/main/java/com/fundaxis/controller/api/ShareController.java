package com.fundaxis.controller.api;

import com.fundaxis.dto.*;
import com.fundaxis.entity.ShareHolding;
import com.fundaxis.service.ShareService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

///========== Share Controller ==========///
// Exposes REST endpoints for managing employee contribution shares,
// tracking transactions, rate histories, and calculating expected monthly contributions.
@RestController
@RequestMapping("/api/contribution-shares")
public class ShareController {

    private final ShareService shareService;

    public ShareController(ShareService shareService) {
        this.shareService = shareService;
    }

    ///========== Read Share Holdings & Histories ==========///

    // Retrieves all share holdings associated with a specific employee
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping("/employee/{employeeId}")
    public ResponseEntity<List<ShareHoldingResponse>> getEmployeeShares(@PathVariable String employeeId) {
        List<ShareHoldingResponse> response = shareService.getEmployeeShares(employeeId)
                .stream()
                .map(ShareHoldingResponse::from)
                .toList();

        return ResponseEntity.ok(response);
    }

    // Retrieves the complete transaction audit history for a specific share holding
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping("/{holdingId}/transactions")
    public ResponseEntity<List<ShareTransactionResponse>> getTransactionHistory(@PathVariable Long holdingId) {
        List<ShareTransactionResponse> response = shareService.getTransactionHistory(holdingId)
                .stream()
                .map(ShareTransactionResponse::from)
                .toList();

        return ResponseEntity.ok(response);
    }

    // Retrieves the historical rate changes for a specific share holding
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping("/{holdingId}/rate-history")
    public ResponseEntity<List<ShareRateHistoryResponse>> getRateHistory(@PathVariable Long holdingId) {
        List<ShareRateHistoryResponse> response = shareService.getRateHistory(holdingId)
                .stream()
                .map(ShareRateHistoryResponse::from)
                .toList();

        return ResponseEntity.ok(response);
    }

    ///========== Share Allocation & Modifications (Admin Only) ==========///

    // Allocates new shares to an employee with an initial rate and effective date
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/allocate")
    public ResponseEntity<ShareHoldingResponse> allocateShares(
            @Valid @RequestBody ShareAllocationRequest request
    ) {
        ShareHolding holding = shareService.allocateShares(
                request.employeeId(),
                request.shareCategory(),
                request.quantity(),
                request.monthlyRatePerShare(),
                request.shareValuePerShare(),
                request.effectiveDate()
        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ShareHoldingResponse.from(holding));
    }

    // Adjusts the existing share quantity for a specific holding
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{holdingId}/adjust")
    public ResponseEntity<ShareHoldingResponse> adjustShares(
            @PathVariable Long holdingId,
            @Valid @RequestBody ShareAdjustmentRequest request
    ) {
        ShareHolding holding = shareService.adjustShares(
                holdingId,
                request.quantityChange(),
                request.effectiveDate(),
                request.reason()
        );

        return ResponseEntity.ok(
                ShareHoldingResponse.from(holding)
        );
    }

    // Updates or changes the monthly rate per share for an active holding
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{holdingId}/rate")
    public ResponseEntity<ShareHoldingResponse> changeMonthlyRate(
            @PathVariable Long holdingId,
            @Valid @RequestBody ShareRateChangeRequest request
    ) {
        ShareHolding holding = shareService.changeMonthlyRate(
                holdingId,
                request.monthlyRatePerShare(),
                request.effectiveDate(),
                request.reason()
        );

        return ResponseEntity.ok(
                ShareHoldingResponse.from(holding)
        );
    }

    ///========== Monthly Contribution Calculations ==========///

    // Calculates the current expected monthly contribution for an employee
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping("/employee/{employeeId}/monthly-expected")
    public ResponseEntity<BigDecimal> calculateCurrentMonthlyExpected(
            @PathVariable String employeeId
    ) {
        return ResponseEntity.ok(
                shareService.calculateCurrentMonthlyContribution(employeeId)
        );
    }

    // Calculates the expected monthly contribution for an employee for a specific year and month
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping("/employee/{employeeId}/monthly-expected/{year}/{month}")
    public ResponseEntity<MonthlyShareContributionResponse> calculateMonthlyExpected(
            @PathVariable String employeeId,
            @PathVariable int year,
            @PathVariable int month
    ) {
        // Validate year and month boundaries before processing
        if (year < 1900 || year > 9999 || month < 1 || month > 12) {
            throw new IllegalArgumentException("Invalid contribution year or month");
        }

        return ResponseEntity.ok(
                shareService.calculateMonthlyContribution(employeeId, LocalDate.of(year, month, 1))
        );
    }
}