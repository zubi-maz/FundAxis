package com.fundaxis.controller.api;

import com.fundaxis.dto.BulkMonthlyGenerationResponse;
import com.fundaxis.dto.MonthlyContributionGenerationResponse;
import com.fundaxis.service.MonthlyContributionGenerationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.DateTimeException;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/contributions/generate")
@PreAuthorize("hasRole('ADMIN')")
public class MonthlyContributionGenerationController {
    private final MonthlyContributionGenerationService generationService;

    public MonthlyContributionGenerationController(MonthlyContributionGenerationService generationService) {
        this.generationService = generationService;
    }

    // POST /api/contributions/generate/employee/EMP18/2026/10
    @PostMapping("/employee/{employeeId}/{year}/{month}")
    public ResponseEntity<MonthlyContributionGenerationResponse> generateOne(
            @PathVariable String employeeId, @PathVariable int year, @PathVariable int month) {
        var result = generationService.generateOne(employeeId, toMonth(year, month));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result);
    }

    // POST /api/contributions/generate/month/2026/10
    @PostMapping("/month/{year}/{month}")
    public ResponseEntity<BulkMonthlyGenerationResponse> generateAll(
            @PathVariable int year, @PathVariable int month) {
        return ResponseEntity.ok(generationService.generateAll(toMonth(year, month)));
    }

    private LocalDate toMonth(int year, int month) {
        try {
            return LocalDate.of(year, month, 1);
        } catch (DateTimeException ex) {
            throw new IllegalArgumentException("Invalid contribution year or month");
        }
    }
}
