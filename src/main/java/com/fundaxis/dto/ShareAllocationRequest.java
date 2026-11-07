
package com.fundaxis.dto;

import com.fundaxis.entity.ShareHolding;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;

//========== Share Allocation Request DTO ==========//

// Incoming payload when an admin allocates shares to an employee
// for the first time (creates a new ShareHolding + INITIAL_ALLOCATION).

public record ShareAllocationRequest(

        // Official Employee ID, e.g. "EMP-001".
        @NotBlank
        String employeeId,

        // Which category to allocate (OLD or NEW).
        @NotNull
        ShareHolding.ShareCategory shareCategory,

        // Number of shares to allocate. Must be at least 1.
        @NotNull
        @Min(1)
        Integer quantity,

        // Monthly contribution rate per share.
        // Must be at least 0.01 and fit DECIMAL(20,2).
        @NotNull
        @DecimalMin("0.01")
        @Digits(integer = 18, fraction = 2)
        BigDecimal monthlyRatePerShare,

        // Approved monetary value of each share.
        // Must be at least 0.01 and fit DECIMAL(20,2).
        @NotNull
        @DecimalMin("0.01")
        @Digits(integer = 18, fraction = 2)
        BigDecimal shareValuePerShare,

        // Date from which the allocation takes effect.
        @NotNull
        LocalDate effectiveDate

) {
}
