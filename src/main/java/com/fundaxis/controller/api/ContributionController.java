package com.fundaxis.controller.api;

import com.fundaxis.entity.Contribution;
import com.fundaxis.service.ContributionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.fundaxis.dto.ContributionPaymentRequest;
import org.springframework.security.access.prepost.PreAuthorize;


import java.util.List;

@RestController
@RequestMapping("/api/contributions")
public class ContributionController {

    private final ContributionService contributionService;

    // Inject ContributionService into the Controller
    public ContributionController(ContributionService contributionService) {
        this.contributionService = contributionService;
    }


    ///---------- List ----------///

    // Get all Contributions List
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping
    public ResponseEntity<List<Contribution>> getAllContributionsList() {

        return ResponseEntity.ok(contributionService.getAllContributionsList());
    }


    ///---------- Read ----------///

    // Get one Contribution by Contribution ID
    // Example: GET /api/contributions/25
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping("/{id}")
    public ResponseEntity<Contribution> getContributionById(@PathVariable Long id) {

        return ResponseEntity.ok(contributionService.getContributionById(id));

    }


    // Get all Contributions belonging to an Employee
    // Example: GET /api/contributions/employee/EMP001
    @PreAuthorize("hasAnyRole('ADMIN', 'IT_SUPPORT')")
    @GetMapping("/employee/{employeeId}")
    public ResponseEntity<List<Contribution>> getContributionsByEmployee(@PathVariable String employeeId) {

        return ResponseEntity.ok(contributionService.getEmployeeContributionsByEmployeeId(employeeId));
    }


    ///---------- Create ----------///

    // Create a Contribution for an Employee
    // Example: POST /api/contributions/employee/EMP001
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/employee/{employeeId}")
    public ResponseEntity<Contribution> createContribution(@PathVariable String employeeId, @Valid @RequestBody Contribution contribution) {

        Contribution newContribution = contributionService.createContribution(employeeId, contribution);

        return ResponseEntity.status(HttpStatus.CREATED).body(newContribution);

    }



    ///========== Mark a contribution as paid ==========///

    // Mark a pending Contribution as paid
    // Example: PATCH /api/contributions/25/pay
    // Returns HTTP 200 OK with the updated contribution
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id}/pay")
    public ResponseEntity<Contribution> markContributionAsPaid(@PathVariable Long id, @Valid @RequestBody ContributionPaymentRequest request) {

        // Call the service to mark the contribution as paid using the payment date from the request
        Contribution paidContribution = contributionService.markContributionAsPaid(id, request.getPaymentDate());

        // Return the updated contribution with HTTP 200 OK
        return ResponseEntity.ok(paidContribution);

    }


    ///---------- Update ----------///

    // Update a specific Contribution
    // Example: PUT /api/contributions/25
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public ResponseEntity<Contribution> updateContribution(@PathVariable Long id, @Valid @RequestBody Contribution contribution) {

        Contribution updatedContribution = contributionService.updateContribution(id, contribution);

        return ResponseEntity.ok(updatedContribution);
    }


    ///---------- Cancel ----------///

    // Cancel a specific Contribution
    // Example: PATCH /api/contributions/25/cancel
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id}/cancel")
    public ResponseEntity<Contribution> cancelContribution(@PathVariable Long id) {

        Contribution canceledContribution = contributionService.cancelContribution(id);

        return ResponseEntity.ok(canceledContribution);
    }

}