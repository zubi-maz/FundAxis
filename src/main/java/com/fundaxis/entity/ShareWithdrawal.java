
package com.fundaxis.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "share_withdrawals",
        indexes = {
                @Index(
                        name = "idx_share_withdrawals_employee",
                        columnList = "employee_id"
                ),
                @Index(
                        name = "idx_share_withdrawals_status",
                        columnList = "status"
                )
        }
)
public class ShareWithdrawal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // The official employee ID is resolved through this relationship.
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "employee_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_share_withdrawals_employee"
            )
    )
    private Employee employee;

    // One withdrawal request belongs to one OLD or NEW holding.
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "share_holding_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_share_withdrawals_holding"
            )
    )
    private ShareHolding shareHolding;

    public enum WithdrawalStatus {
        PENDING,
        APPROVED,
        REJECTED,
        CANCELLED,
        DISBURSED
    }

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(
            name = "status",
            nullable = false,
            length = 20
    )
    private WithdrawalStatus status = WithdrawalStatus.PENDING;

    @NotNull
    @Min(1)
    @Column(name = "requested_quantity", nullable = false)
    private Integer requestedQuantity;

    @NotNull
    @Column(name = "request_date", nullable = false)
    private LocalDate requestDate;

    @NotNull
    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Size(max = 500)
    @Column(name = "reason", length = 500)
    private String reason;

    // Monetary fields will be populated only when valuation
    // is confirmed from the approved bank rules.
    @Column(
            name = "gross_amount",
            precision = 20,
            scale = 2
    )
    private BigDecimal grossAmount;

    @Column(
            name = "deduction_amount",
            precision = 20,
            scale = 2
    )
    private BigDecimal deductionAmount;

    @Column(
            name = "net_amount",
            precision = 20,
            scale = 2
    )
    private BigDecimal netAmount;

    // Approval audit
    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    // Records the time of share-balance posting.
    // Approval does not automatically imply that shares
    // have already been deducted.
    @Column(name = "shares_posted_at")
    private LocalDateTime sharesPostedAt;

    // Payment tracking
    @Column(name = "disbursed_at")
    private LocalDateTime disbursedAt;

    @Column(name = "payment_reference", length = 150)
    private String paymentReference;

    // Additive workflow audit. Legacy requests may have unknown actors/tokens.
    @Column(name = "requested_by", length = 100)
    private String requestedBy;

    @Column(name = "request_token", length = 36, unique = true)
    private String requestToken;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "share_transaction_id", unique = true,
            foreignKey = @ForeignKey(name = "fk_withdrawal_share_transaction"))
    private ShareTransaction shareTransaction;

    @Column(name = "shares_posted_by", length = 100)
    private String sharesPostedBy;

    @Column(name = "disbursed_by", length = 100)
    private String disbursedBy;

    @Column(name = "resolved_by", length = 100)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    // Audit timestamps
    @CreationTimestamp
    @Column(
            name = "created_at",
            updatable = false
    )
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Optimistic versioning for conflicting updates.
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    // Read-only convenience value.
    @Transient
    public String getEmployeeId() {
        return employee == null
                ? null
                : employee.getEmployeeId();
    }

    @Transient
    public ShareHolding.ShareCategory getShareCategory() {
        return shareHolding == null
                ? null
                : shareHolding.getShareCategory();
    }
}
