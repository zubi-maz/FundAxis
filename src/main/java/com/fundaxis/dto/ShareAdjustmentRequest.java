package com.fundaxis.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

///========== Share Adjustment Request DTO ==========///
// Incoming payload when an admin increases or decreases
// the quantity of an existing ShareHolding.

public record ShareAdjustmentRequest(

        // Shares to add (positive) or remove (negative). Must not be zero.
        @NotNull
        Integer quantityChange,

        // Date from which this adjustment takes effect.
        @NotNull
        LocalDate effectiveDate,

        // Why the change was made (required, max 500 characters).
        @NotBlank
        @Size(max = 500)
        String reason
) {
}