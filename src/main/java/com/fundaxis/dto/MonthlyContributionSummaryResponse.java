
package com.fundaxis.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record MonthlyContributionSummaryResponse(
        LocalDate month,
        long totalRecords,
        long pendingRecords,
        long paidRecords,
        long canceledRecords,
        BigDecimal expected,
        BigDecimal collected,
        BigDecimal creditApplied,
        BigDecimal totalSettled,
        BigDecimal outstanding,
        long payments,
        long reversals
) {}
