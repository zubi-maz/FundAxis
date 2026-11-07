package com.fundaxis.dto;

import com.fundaxis.entity.ShareTransaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

///========== Share Transaction Response DTO ==========///
// Outgoing payload that represents one ShareTransaction
// ready to be returned to the client / admin UI.

public record ShareTransactionResponse(

        // Database primary key of the transaction.
        Long transactionId,

        // Parent holding this transaction belongs to.
        Long holdingId,

        // Type of change includes INITIAL_ALLOCATION, INCREASE, DECREASE, WITHDRAWAL_POSTED, and VALUATION_CHANGE.
        ShareTransaction.ShareTransactionType transactionType,

        // Shares added (positive) or removed (negative).
        Integer quantityChange,

        // Holding quantity immediately before this transaction.
        Integer quantityBefore,

        // Holding quantity immediately after this transaction.
        Integer quantityAfter,

        // Date from which the change takes effect.
        LocalDate effectiveDate,

        // Rate that was in force when the transaction was recorded.
        BigDecimal ratePerShareSnapshot,

        // Optional note explaining why the change was made.
        String reason,

        // When the transaction row was first created.
        LocalDateTime createdAt,

        // Valuation snapshots are populated for allocation/valuation events.
        BigDecimal shareValueBefore,
        BigDecimal shareValueAfter
) {

    ///========== Factory Method ==========///

    // Builds a response DTO from a persisted ShareTransaction entity.
    public static ShareTransactionResponse from(ShareTransaction transaction) {
        return new ShareTransactionResponse(
                transaction.getId(),
                transaction.getShareHolding().getId(),
                transaction.getTransactionType(),
                transaction.getQuantityChange(),
                transaction.getQuantityBefore(),
                transaction.getQuantityAfter(),
                transaction.getEffectiveDate(),
                transaction.getRatePerShareSnapshot(),
                transaction.getReason(),
                transaction.getCreatedAt(),
                transaction.getShareValueBefore(),
                transaction.getShareValueAfter()
        );
    }
}
