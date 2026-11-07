
package com.fundaxis.dto;

import com.fundaxis.entity.Contribution;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ContributionStatementLine(
        Long contributionId,
        LocalDate month,
        BigDecimal expected,
        BigDecimal collected,       // Active direct cash payments
        BigDecimal creditApplied,   // Active member-credit allocations
        BigDecimal totalSettled,    // Cash + applied credit
        BigDecimal outstanding,
        Contribution.ContributionStatus status,
        int paymentCount,
        int reversalCount
) {}
