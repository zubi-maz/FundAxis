package com.fundaxis.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

///========== ShareHolding Entity ==========///
// Represents one share-holding record for an employee.
//
// Rules:
// - One employee can have at most one row per share category (OLD / NEW).
// - Unique constraint: (employee_id, share_category)
// - Expected monthly contribution is calculated, not stored.

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "share_holdings",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_share_holdings_employee_category",
                columnNames = {"employee_id", "share_category"}
        )
)
public class ShareHolding {

    ///========== Primary Key ==========///

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    ///========== Relationships ==========///

    // Link to the stable Employee database primary key.
    // LAZY fetch keeps the entity lightweight when we only need holding data.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "employee_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_share_holdings_employee")
    )
    private Employee employee;

    ///========== Share Category (OLD / NEW) ==========///

    public enum ShareCategory {
        OLD,
        NEW
    }

    // Stored as STRING so the DB value stays readable (OLD / NEW).
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(
            name = "share_category",
            nullable = false,
            length = 20
    )
    private ShareCategory shareCategory;

    ///========== Holding Details ==========///

    // Number of shares held. Defaults to 0; never negative.
    @Min(0)
    @Column(name = "share_quantity", nullable = false)
    private Integer shareQuantity = 0;


    // Approved monetary valuation of one share.
    // Optional: null means valuation is not available.
    @DecimalMin("0.01")
    @Column(
            name = "share_value_per_share",
            nullable = true,
            precision = 20,
            scale = 2
    )
    private BigDecimal shareValuePerShare;


    @Transient
    public BigDecimal getTotalShareValue() {

        if (shareQuantity == null || shareValuePerShare == null) {
            return null;
        }

        return shareValuePerShare.multiply(
                BigDecimal.valueOf(shareQuantity)
        );
    }

    // Monthly contribution rate charged per share.
    // Must be greater than zero.
    @NotNull
    @DecimalMin("0.01")
    @Column(
            name = "monthly_rate_per_share",
            nullable = false,
            precision = 20,
            scale = 2
    )
    private BigDecimal monthlyRatePerShare;

    // Date when these shares were allocated to the employee.
    @NotNull
    @Column(name = "allocation_date", nullable = false)
    private LocalDate allocationDate;

    ///========== Audit Timestamps ==========///

    // Set once when the row is first inserted.
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    // Updated automatically on every change.
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    ///========== Calculated Fields (not persisted) ==========///

    // Current expected contribution for this holding:
    // monthlyRatePerShare × shareQuantity
    @Transient
    public BigDecimal getExpectedMonthlyContribution() {
        if (shareQuantity == null || monthlyRatePerShare == null) {
            return BigDecimal.ZERO;
        }
        return monthlyRatePerShare.multiply(BigDecimal.valueOf(shareQuantity));
    }
}