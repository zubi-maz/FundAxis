package com.fundaxis.controller.api;

import com.fundaxis.dto.*;
import com.fundaxis.service.ContributionReportingService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/contributions/reports")
@PreAuthorize("hasAnyRole('ADMIN','IT_SUPPORT')")
public class ContributionReportingController {
    private final ContributionReportingService service;
    public ContributionReportingController(ContributionReportingService service) { this.service = service; }

    @GetMapping("/member/{employeeId}")
    public MemberContributionStatementResponse member(@PathVariable String employeeId,
                                                      @RequestParam int fromYear, @RequestParam int fromMonth,
                                                      @RequestParam int toYear, @RequestParam int toMonth) {
        return service.member(employeeId, fromYear, fromMonth, toYear, toMonth);
    }

    @GetMapping("/monthly/{year}/{month}")
    public MonthlyContributionSummaryResponse monthly(@PathVariable int year, @PathVariable int month) {
        return service.monthly(year, month);
    }

    @GetMapping("/dashboard")
    public ContributionDashboardResponse dashboard(@RequestParam int fromYear,
                                                   @RequestParam int fromMonth, @RequestParam int toYear, @RequestParam int toMonth) {
        return service.dashboard(fromYear, fromMonth, toYear, toMonth);
    }
}
