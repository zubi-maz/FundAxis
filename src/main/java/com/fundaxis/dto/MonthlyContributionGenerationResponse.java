package com.fundaxis.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record MonthlyContributionGenerationResponse(
        Long contributionId,
        String employeeId,
        LocalDate contributionMonth,
        BigDecimal expectedAmount,
        String status,
        boolean created,
        String message) {}
