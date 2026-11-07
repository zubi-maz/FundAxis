
package com.fundaxis.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ContributionDashboardResponse(
        LocalDate fromMonth,
        LocalDate toMonth,
        long totalRecords,
        long pendingRecords,
        long paidRecords,
        long canceledRecords,
        BigDecimal expected,
        BigDecimal collected,
        BigDecimal creditApplied,
        BigDecimal totalSettled,
        BigDecimal outstanding,
        int currentOldShares,
        int currentNewShares,
        long paymentCount,
        long reversalCount
) {}
