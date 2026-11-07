package com.fundaxis.dto;

import com.fundaxis.entity.Contribution;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ContributionPaymentSummaryResponse(
        Long contributionId, String employeeId, LocalDate contributionMonth,
        BigDecimal expectedAmount, BigDecimal collectedAmount, BigDecimal outstandingAmount,
        Contribution.ContributionStatus status, List<ContributionPaymentResponse> payments
) {}
