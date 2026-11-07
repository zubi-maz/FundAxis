package com.fundaxis.dto;

import com.fundaxis.entity.ContributionPayment;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;

public record RecordContributionPaymentRequest(
        @NotNull @DecimalMin("0.01") @Digits(integer = 18, fraction = 2) BigDecimal amount,
        @NotNull LocalDate paymentDate,
        @NotNull ContributionPayment.PaymentMethod paymentMethod,
        @Size(max = 120) String referenceNo,
        @Size(max = 500) String notes
) {}
