package com.fundaxis.controller.api;

import com.fundaxis.dto.*;
import com.fundaxis.service.ContributionPaymentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/contributions/{contributionId}/payments")
public class ContributionPaymentController {
    private final ContributionPaymentService service;

    public ContributionPaymentController(ContributionPaymentService service) {
        this.service = service;
    }

    @PreAuthorize("hasAnyRole('ADMIN','IT_SUPPORT')")
    @GetMapping
    public ResponseEntity<ContributionPaymentSummaryResponse> summary(@PathVariable Long contributionId) {
        return ResponseEntity.ok(service.getSummary(contributionId));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<ContributionPaymentSummaryResponse> receive(
            @PathVariable Long contributionId, @Valid @RequestBody RecordContributionPaymentRequest request) {
        return ResponseEntity.ok(service.receive(contributionId, request));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{paymentId}/reverse")
    public ResponseEntity<ContributionPaymentSummaryResponse> reverse(
            @PathVariable Long contributionId, @PathVariable Long paymentId,
            @Valid @RequestBody ContributionPaymentReversalRequest request) {
        return ResponseEntity.ok(service.reverse(contributionId, paymentId, request));
    }
}
