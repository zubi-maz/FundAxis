package com.fundaxis.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter @Setter @NoArgsConstructor
@Entity
@Table(name = "contribution_payments", indexes = {
        @Index(name = "ix_contribution_payments_contribution", columnList = "contribution_id")
})
public class ContributionPayment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contribution_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_contribution_payments_contribution"))
    private Contribution contribution;

    @Column(name = "payment_amount", precision = 20, scale = 2, nullable = false)
    private BigDecimal paymentAmount;

    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate;

    /** Actual transaction methods plus the three collection channels used by the monthly process. */
    public enum PaymentMethod { CASH, BANK_TRANSFER, BANK_DEPOSIT, OTHER, UNSPECIFIED, HO_HAND, BANK_FT, ITD_HAND }

    public static String displayPaymentMethod(PaymentMethod method) {
        if (method == null) return "Not Recorded";
        return switch (method) {
            case HO_HAND -> "HO-Hand";
            case BANK_FT -> "Bank FT";
            case ITD_HAND -> "ITD-Hand";
            case BANK_TRANSFER -> "Bank Transfer";
            case BANK_DEPOSIT -> "Bank Deposit";
            case CASH -> "Cash";
            case OTHER -> "Other";
            case UNSPECIFIED -> "Not Recorded";
        };
    }

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 25, nullable = false)
    private PaymentMethod paymentMethod;

    @Column(name = "reference_no", length = 120)
    private String referenceNo;

    @Column(name = "notes", length = 500)
    private String notes;

    @Column(name = "reversed", nullable = false)
    private boolean reversed = false;

    @Column(name = "reversal_date")
    private LocalDate reversalDate;

    @Column(name = "reversal_reason", length = 500)
    private String reversalReason;

    @Column(name = "legacy_import", nullable = false)
    private boolean legacyImport = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
