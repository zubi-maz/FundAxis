package com.fundaxis.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

///========== Share Rate History Entity ==========///
// Tracks historical changes to share rates for specific holdings over time.
// Same-day corrections are retained as separate audited rows; the highest ID wins.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "share_rate_history")
public class ShareRateHistory {

    ///========== Identifiers & Relations ==========///

    // Primary key for the share rate history record
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // The share holding entity associated with this rate record
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "share_holding_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_share_rate_history_holding")
    )
    private ShareHolding shareHolding;

    ///========== Rate Details ==========///

    // Date when this monthly share rate becomes effective
    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    // Monthly rate per share value
    @Column(name = "monthly_rate_per_share", nullable = false, precision = 20, scale = 2)
    private BigDecimal monthlyRatePerShare;

    // Business reason or justification for this rate adjustment
    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "recorded_by", length = 120)
    private String recordedBy;

    ///========== Audit Fields ==========///

    // Timestamp when this history entry was recorded
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
