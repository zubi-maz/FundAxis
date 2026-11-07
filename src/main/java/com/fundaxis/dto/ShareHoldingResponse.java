package com.fundaxis.dto;

import com.fundaxis.entity.ShareHolding;

import java.math.BigDecimal;
import java.time.LocalDate;

///========== Share Holding Response DTO ==========///
// Outgoing payload that represents one ShareHolding
// ready to be returned to the client / admin UI.

public record ShareHoldingResponse(

        // Database primary key of the holding.
        Long holdingId,

        // Official Employee ID, e.g. "EMP-001".
        String employeeId,

        // Employee's display name.
        String employeeName,

        // Category of this holding (OLD or NEW).
        ShareHolding.ShareCategory shareCategory,

        // Current number of shares held.
        Integer shareQuantity,

        // Monthly rate charged per share.
        BigDecimal monthlyRatePerShare,

        // Calculated contribution: rate × quantity.
        BigDecimal expectedMonthlyContribution,

        // Date when the shares were first allocated.
        LocalDate allocationDate
) {

    ///========== Factory Method ==========///

    // Builds a response DTO from a persisted ShareHolding entity.
    public static ShareHoldingResponse from(ShareHolding holding) {
        return new ShareHoldingResponse(
                holding.getId(),
                holding.getEmployee().getEmployeeId(),
                holding.getEmployee().getFullName(),
                holding.getShareCategory(),
                holding.getShareQuantity(),
                holding.getMonthlyRatePerShare(),
                holding.getExpectedMonthlyContribution(),
                holding.getAllocationDate()
        );
    }
}