
package com.fundaxis.entity;

import jakarta.persistence.*;
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
@Table(name = "member_credit_allocations", indexes = {
        @Index(name = "ix_credit_allocations_credit",
                columnList = "credit_id"),
        @Index(name = "ix_credit_allocations_contribution",
                columnList = "contribution_id")
})
public class MemberCreditAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "credit_id", nullable = false)
    private MemberCredit credit;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contribution_id", nullable = false)
    private Contribution contribution;

    @Column(name = "amount", nullable = false,
            precision = 20, scale = 2)
    private BigDecimal amount;

    @Column(name = "applied_date", nullable = false)
    private LocalDate appliedDate;

    @Column(name = "reversed", nullable = false)
    private boolean reversed = false;

    @Column(name = "reversal_date")
    private LocalDate reversalDate;

    @Column(name = "reversal_reason", length = 500)
    private String reversalReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false,
            updatable = false)
    private LocalDateTime createdAt;
}
