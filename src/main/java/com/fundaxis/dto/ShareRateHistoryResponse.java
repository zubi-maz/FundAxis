package com.fundaxis.dto;

import com.fundaxis.entity.ShareRateHistory;
import java.math.BigDecimal;
import java.time.LocalDate;

public record ShareRateHistoryResponse(Long id, Long holdingId, LocalDate effectiveDate,
                                       BigDecimal monthlyRatePerShare, String reason) {
    public static ShareRateHistoryResponse from(ShareRateHistory r) {
        return new ShareRateHistoryResponse(r.getId(), r.getShareHolding().getId(),
                r.getEffectiveDate(), r.getMonthlyRatePerShare(), r.getReason());
    }
}
