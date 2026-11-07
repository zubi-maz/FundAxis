
package com.fundaxis.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "member_credits",
        indexes = {
                @Index(
                        name = "ix_member_credits_employee",
                        columnList = "employee_id"
                ),
                @Index(
                        name = "ix_member_credits_status",
                        columnList = "status"
                )
        }
)
public class MemberCredit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Employee ID is the main business lookup identifier.
    // The database uses Employee's existing primary key as FK.
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "employee_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_member_credits_employee"
            )
    )
    private Employee employee;

    public enum CreditStatus {
        UNALLOCATED,
        PARTIALLY_APPLIED,
        FULLY_APPLIED,
        REVERSED
    }

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(
            name = "status",
            nullable = false,
            length = 25
    )
    private CreditStatus status = CreditStatus.UNALLOCATED;

    // Original amount received from the employee.
    @NotNull
    @DecimalMin("0.01")
    @Column(
            name = "original_amount",
            nullable = false,
            precision = 20,
            scale = 2
    )
    private BigDecimal originalAmount;

    // Remaining amount that has not been allocated.
    @NotNull
    @DecimalMin("0.00")
    @Column(
            name = "available_amount",
            nullable = false,
            precision = 20,
            scale = 2
    )
    private BigDecimal availableAmount;

    @NotNull
    @Column(name = "collection_date", nullable = false)
    private LocalDate collectionDate;

    // Reuse the existing payment method categories.
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(
            name = "payment_method",
            nullable = false,
            length = 25
    )
    private ContributionPayment.PaymentMethod paymentMethod;

    @Size(max = 120)
    @Column(name = "reference_no", length = 120)
    private String referenceNo;

    @Size(max = 500)
    @Column(name = "notes", length = 500)
    private String notes;

    // Reversal information must remain available for audit.
    @Column(name = "reversal_date")
    private LocalDate reversalDate;

    @Size(max = 500)
    @Column(name = "reversal_reason", length = 500)
    private String reversalReason;

    @CreationTimestamp
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private LocalDateTime createdAt;

    // Prevent conflicting changes to the same credit record.
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    // Convenience getter for UI / service usage.
    @Transient
    public String getEmployeeId() {
        return employee == null
                ? null
                : employee.getEmployeeId();
    }
}
