package com.fundaxis.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BulkMonthlyGenerationResponse(
        LocalDate contributionMonth,
        int eligibleEmployees,
        int created,
        int skippedExisting,
        int skippedNoDues,
        BigDecimal generatedExpectedTotal,
        List<MonthlyContributionGenerationResponse> results) {}
