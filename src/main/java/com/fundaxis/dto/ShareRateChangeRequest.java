package com.fundaxis.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;

public record ShareRateChangeRequest(
        @NotNull @DecimalMin("0.01") @Digits(integer = 18, fraction = 2)
        BigDecimal monthlyRatePerShare,
        @NotNull LocalDate effectiveDate,
        @NotBlank @Size(max = 500) String reason
) {}
