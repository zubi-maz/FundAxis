package com.fundaxis.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.PastOrPresent;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "loans",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_loan_application_id",
                        columnNames = "loan_application_id"
                )
        }
)
public class Loan {

    ///---------- Database Primary Key ----------
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;


    ///---------- Loan Application ID ----------
    // Unique application number for the loan
    @NotBlank(message = "Loan application ID is required")
    @Column(name = "loan_application_id", nullable = false, length = 50, unique = true)
    private String loanApplicationId;


    ///---------- Employee ----------
    // Many Loans can belong to One Employee
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false, foreignKey = @ForeignKey(name = "fk_loan_employee"))
    private Employee employee;

    /** Optional internal employee guarantor; null for legacy/external or absent guarantors. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "guarantor_employee_id", foreignKey = @ForeignKey(name = "fk_loan_guarantor_employee"))
    private Employee guarantorEmployee;

    /** Preserves a historical guarantor name when no verified employee match exists. */
    @Column(name = "legacy_guarantor_name", length = 200)
    private String legacyGuarantorName;

    @Column(name = "created_by", length = 100)
    private String createdBy;


    ///---------- Loan Type ----------
    public enum LoanType {
        PERSONAL,
        EMERGENCY,
        HOME,
        EDUCATION,
        MEDICAL,
        OTHER
    }

    @NotNull(message = "Loan type is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "loan_type", nullable = false, length = 30)
    private LoanType loanType;


    ///---------- Requested Amount ----------
    // Amount requested by the employee
    @NotNull(message = "Requested amount is required")
    @DecimalMin(value = "0.01", message = "Requested amount must be greater than zero")
    @Column(name = "requested_amount", nullable = false, precision = 20, scale = 2)
    private BigDecimal requestedAmount;

    ///---------- Approved Amount ----------
    // Amount approved by Admin
    @DecimalMin(value = "0.01", message = "Approved amount must be greater than zero")
    @Column(name = "approved_amount", precision = 20, scale = 2)
    private BigDecimal approvedAmount;


    ///---------- Loan Tenure ----------
    // Requested/approved repayment period in months
    // Example: 6 months
    @NotNull(message = "Loan tenure is required")
    @Min(value = 1, message = "Loan tenure must be at least 1 month")
    @Column(name = "tenure_months", nullable = false)
    private Integer tenureMonths;

    @Min(value = 1, message = "Approved tenure must be at least 1 month")
    @Column(name = "approved_tenure_months")
    private Integer approvedTenureMonths;


    ///---------- Interest Rate ----------
    // Example: 5.50%
    @DecimalMin(value = "0.00", message = "Interest rate cannot be negative")
    @Column(name = "interest_rate", precision = 5, scale = 2)
    private BigDecimal interestRate;

    ///---------- Loan Purpose ----------
    @NotBlank(message = "Loan purpose is required")
    @Column(name = "loan_purpose", nullable = false, length = 500)
    private String loanPurpose;


    ///---------- Application Date ----------
    @NotNull(message = "Application date is required")
    @PastOrPresent(message = "Application date cannot be in the future")
    @Column(name = "application_date", nullable = false)
    private LocalDate applicationDate;

    ///---------- Approval Date ----------
    // Null until the loan is approved
    @Column(name = "approval_date")
    private LocalDate approvalDate;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "rejected_by", length = 100)
    private String rejectedBy;

    @Column(name = "rejected_at")
    private LocalDateTime rejectedAt;


    ///---------- Loan Status ----------
    public enum LoanStatus {
        DRAFT,
        SUBMITTED,
        UNDER_REVIEW,
        APPROVED,
        REJECTED,
        CANCELLED,
        DISBURSED,
        ACTIVE,
        COMPLETED,
        APPLIED,
        CLOSED
    }

    @NotNull(message = "Loan status is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "loan_status", nullable = false, length = 30)
    private LoanStatus loanStatus = LoanStatus.DRAFT;


    ///---------- Eligibility Status ----------
    public enum EligibilityStatus {
        PENDING,
        ELIGIBLE,
        NOT_ELIGIBLE
    }

    @NotNull(message = "Eligibility status is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "eligibility_status", nullable = false, length = 20)
    private EligibilityStatus eligibilityStatus = EligibilityStatus.PENDING;


    ///---------- General Remarks ----------
    @Column(name = "remarks", length = 1000)
    private String remarks;


    ///---------- Rejection Reason ----------
    // Used when loan is rejected
    @Column(name = "rejection_reason", length = 1000)
    private String rejectionReason;


    ///---------- Audit Information ----------
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @JsonFormat(pattern = "dd/MM/yyyy HH:mm:ss")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    @JsonFormat(pattern = "dd/MM/yyyy HH:mm:ss")
    private LocalDateTime updatedAt;



}
