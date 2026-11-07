package com.fundaxis.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record ContributionPaymentReversalRequest(
        @NotNull LocalDate reversalDate,
        @NotBlank @Size(max = 500) String reason
) {}
