package com.fundaxis.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "contributions",
        uniqueConstraints = {

                // One employee can have only one contribution per month
                // The combination of employee_id + contribution_month must be unique (One employee + one month = one contribution)
                @UniqueConstraint(
                        name = "uk_contribution_employee_month",
                        columnNames = {"employee_id", "contribution_month"}
                )
        }
)
public class Contribution {

    // Database primary key
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /// ---------- connecting Employee and Contribution entities ----------
    /// Employee is the parent. Contribution is the child. The contributions.employee_id foreign key connects each Contribution to its Employee

    // One Employee can have MANY Contributions; ONE Employee <--- MANY Contributions; Many Contribution records can be connected to one Employee // Many Contributions belong to ONE Employee
    @ManyToOne(fetch = FetchType.LAZY, optional = false)

    // database connection
    // Store the Employee's ID in the contributions table
    @JoinColumn(name = "employee_id", nullable = false, foreignKey = @ForeignKey(name = "fk_contribution_employee"))

    // Every Contribution Java object has an Employee associated with it
    private Employee employee;
    /// --------------------------------------------------------------------


    // Contribution amount
    @NotNull(message = "Contribution amount is required")
    @DecimalMin(value = "0.01", message = "Contribution amount must be greater than zero")
    @Column(name = "contribution_amount", nullable = false, precision = 20, scale = 2)
    private BigDecimal contributionAmount;

    /** Optional informational value recorded for this monthly contribution row. */
    @Column(name = "share_value", precision = 20, scale = 2)
    private BigDecimal shareValue;

    /** Optional administrator remark; distinct from payment method and receipt notes. */
    @Column(name = "remarks", length = 500)
    private String remarks;

    // Contribution Month
    // Example: 01/09/2026 represents September 2026
    // One employee can have only one contribution per month
    @NotNull(message = "Contribution month is required")
    @Column(name = "contribution_month", nullable = false)
    @JsonFormat(pattern = "dd/MM/yyyy")
    private LocalDate contributionMonth;


    // Actual Payment Date
    // Null while contribution is PENDING
    @Column(name = "payment_date")
    @JsonFormat(pattern = "dd/MM/yyyy")
    private LocalDate paymentDate;

    /// ---------- Contribution Status ----------

    public enum ContributionStatus {
        PENDING,
        PAID,
        CANCELED
    }

    @Enumerated(EnumType.STRING)
    @Column(name = "contribution_status", nullable = false, length = 20)
    private ContributionStatus contributionStatus = ContributionStatus.PENDING;

    /** Optional planned collection channel; never evidence of a received payment. */
    @Enumerated(EnumType.STRING)
    @Column(name = "intended_collection_method", length = 25)
    private ContributionPayment.PaymentMethod intendedCollectionMethod;

    /// ---------- Audit Information ----------

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @JsonFormat(pattern = "dd/MM/yyyy HH:mm:ss")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    @JsonFormat(pattern = "dd/MM/yyyy HH:mm:ss")
    private LocalDateTime updatedAt;

}
