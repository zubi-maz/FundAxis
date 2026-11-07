package com.fundaxis.dto;

import com.fundaxis.entity.ContributionPayment;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record ContributionPaymentResponse(
        Long paymentId, Long contributionId, BigDecimal amount, LocalDate paymentDate,
        ContributionPayment.PaymentMethod paymentMethod, String referenceNo, String notes,
        boolean reversed, LocalDate reversalDate, String reversalReason,
        boolean legacyImport, LocalDateTime createdAt
) {
    public static ContributionPaymentResponse from(ContributionPayment p) {
        return new ContributionPaymentResponse(p.getId(), p.getContribution().getId(),
                p.getPaymentAmount(), p.getPaymentDate(), p.getPaymentMethod(), p.getReferenceNo(),
                p.getNotes(), p.isReversed(), p.getReversalDate(), p.getReversalReason(),
                p.isLegacyImport(), p.getCreatedAt());
    }
}
