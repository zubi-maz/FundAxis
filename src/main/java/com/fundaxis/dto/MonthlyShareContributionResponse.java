package com.fundaxis.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record MonthlyShareContributionResponse(String employeeId, LocalDate contributionMonth,
                                               BigDecimal expectedAmount) {}
