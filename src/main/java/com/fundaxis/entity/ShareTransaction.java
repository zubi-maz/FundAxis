package com.fundaxis.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

///========== ShareTransaction Entity ==========///
// Records quantity and share valuation changes made to a ShareHolding.
// Monthly rate history remains in ShareRateHistory.

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "share_transactions")
public class ShareTransaction {

    ///========== Primary Key ==========///

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    ///========== Relationships ==========///

    // Parent holding this transaction belongs to.
    // LAZY fetch keeps the entity lightweight when we only need transaction data.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "share_holding_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_share_transactions_holding")
    )
    private ShareHolding shareHolding;

    ///========== Transaction Type ==========///

    public enum ShareTransactionType {
        INITIAL_ALLOCATION,
        INCREASE,
        DECREASE,
        WITHDRAWAL_POSTED,
        VALUATION_CHANGE
    }

    // Stored as STRING so the DB value stays readable.
    @Enumerated(EnumType.STRING)
    @Column(
            name = "transaction_type",
            nullable = false,
            length = 30
    )
    private ShareTransactionType transactionType;

    ///========== Quantity Snapshot ==========///

    // How many shares were added or removed in this transaction.
    // Positive for INCREASE / INITIAL_ALLOCATION; negative for DECREASE / WITHDRAWAL_POSTED.
    @Column(name = "quantity_change", nullable = false)
    private Integer quantityChange;

    // Holding quantity immediately before this transaction.
    @Column(name = "quantity_before", nullable = false)
    private Integer quantityBefore;

    // Holding quantity immediately after this transaction.
    @Column(name = "quantity_after", nullable = false)
    private Integer quantityAfter;

    ///========== Rate & Date ==========///

    // Date from which this change takes effect.
    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    // Rate that was in force at the time of the transaction.
    // Stored as a snapshot so later rate changes do not rewrite history.
    @Column(
            name = "rate_per_share_snapshot",
            nullable = false,
            precision = 20,
            scale = 2
    )
    private BigDecimal ratePerShareSnapshot;

    // Optional valuation snapshots. These are populated for allocations with
    // an initial valuation and for VALUATION_CHANGE rows only.
    @Column(name = "share_value_before", precision = 20, scale = 2)
    private BigDecimal shareValueBefore;

    @Column(name = "share_value_after", precision = 20, scale = 2)
    private BigDecimal shareValueAfter;

    ///========== Optional Details ==========///

    // Free-text note explaining why the change was made (max 500 chars).
    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "recorded_by", length = 120)
    private String recordedBy;

    ///========== Audit Timestamp ==========///

    // Set once when the row is first inserted.
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
