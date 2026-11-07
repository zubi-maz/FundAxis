
package com.fundaxis.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record MemberContributionStatementResponse(
        String employeeId,
        String fullName,
        LocalDate fromMonth,
        LocalDate toMonth,
        BigDecimal expected,
        BigDecimal collected,
        BigDecimal creditApplied,
        BigDecimal totalSettled,
        BigDecimal outstanding,
        int activeOldShares,
        int activeNewShares,
        List<ContributionStatementLine> contributions
) {}
