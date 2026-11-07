package com.fundaxis.controller.admin;

// ---------- Our DTOs (request/response classes) ----------
import com.fundaxis.dto.*;
// ---------- Our entities ----------
import com.fundaxis.entity.Contribution;
import com.fundaxis.entity.ContributionPayment;
import com.fundaxis.entity.Employee;
import com.fundaxis.entity.MemberCredit;
import com.fundaxis.entity.ShareHolding;
import com.fundaxis.entity.ShareTransaction;
// ---------- Our repositories (database access) ----------
import com.fundaxis.repository.ContributionPaymentRepository;
import com.fundaxis.repository.ContributionRepository;
import com.fundaxis.repository.EmployeeRepository;
import com.fundaxis.repository.ShareHoldingRepository;
// ---------- Our services (business logic) ----------
import com.fundaxis.service.ContributionPaymentService;
import com.fundaxis.service.ContributionReportingService;
import com.fundaxis.service.ContributionService;
import com.fundaxis.service.MemberCreditService;
import com.fundaxis.service.MonthlyContributionGenerationService;
import com.fundaxis.service.ShareService;
import com.fundaxis.service.ShareWithdrawalService;
// Builds the .xlsx file for the single month report
import com.fundaxis.service.excel.SingleMonthContributionExcelService;
import com.fundaxis.service.excel.MonthlyContributionRegisterExcelService;
import com.fundaxis.service.excel.YearlyContributionRegisterExcelService;
// ---------- Spring classes ----------
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
// A simple Model that can be created by hand (used by the Excel export)
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
// ---------- Java classes ----------
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ============================================================================
 * FUNDAXIS ENTERPRISE WORKSPACE CONTROLLER
 * National Bank PLC - Share & Contribution Management
 * ============================================================================
 * Handles administrative operations in one page with five primary tabs:
 *  - Tab 1: Overview Dashboard & Analytics
 *  - Tab 2: Employee Share Holdings, Adjustments & Rate Changes
 *  - Tab 3: Monthly Dues Generation & Review
 *  - Tab 4: Payments Ledger & Member Credit (Additional Collections)
 *  - Tab 5: Monthly Records (single-month report + multi-month register)
 *  - Tab 6: Yearly Records (Full Calendar Year Register & Totals)
 *  - Tab 7: Annual / Custom Contribution Statements
 * ============================================================================
 * HOW THIS FILE IS ORGANIZED:
 *  1. Constants, dependencies, constructor, data records
 *  2. workspace()            -> the only GET page, fills the model for the selected tab
 *  3. Monthly records helpers -> used by Tab 5
 *     (processSingleMonthRecords is also reused by the Excel export)
 *  4. POST actions           -> shares, generation, payments, member credits
 *     + one GET download    -> single month Excel export
 *  5. Validation utilities
 * ============================================================================
 */

// @Controller returns template names (HTML pages), not JSON
@Controller
// Only ADMIN can open any page or action in this class
@PreAuthorize("hasRole('ADMIN')")
// Every URL in this class starts with /admin/contribution-workspace
@RequestMapping("/admin/contribution-workspace")
public class AdminContributionWorkspaceController {

    ///==================== Constants & Configuration ====================///

    ///---------- Bank Time Zone ----------
    // "Today" is calculated in Bangladesh time, not the server's time
    private static final ZoneId BANK_ZONE = ZoneId.of("Asia/Dhaka");

    ///---------- Allowed Tab Names ----------
    // Any other ?tab= value falls back to "overview"
    private static final Set<String> VALID_TABS = Set.of("overview", "member-operations", "monthly-records", "yearly-records", "statement");

    ///---------- Allowed Monthly Records Views ----------
    // Any other ?view= value falls back to "single"
    private static final Set<String> VALID_MONTHLY_VIEWS = Set.of("single", "register", "history");

    ///---------- Page And Redirect Targets ----------
    // Template: templates/admin/contribution-workspace.html
    private static final String WORKSPACE_VIEW = "admin/contribution-workspace";
    // After a POST action, go back to this page (the tab is added as ?tab=...)
    private static final String WORKSPACE_REDIRECT = "redirect:/admin/contribution-workspace";


    ///==================== Injected Dependencies ====================///

    // Contribution (monthly dues) logic
    private final ContributionService contributionService;
    // Share holding logic (allocate, adjust, rate change)
    private final ShareService shareService;
    // Member credit (additional collection) logic
    private final MemberCreditService memberCreditService;
    // Generates the monthly dues
    private final MonthlyContributionGenerationService generationService;
    // Payment receive/reverse logic
    private final ContributionPaymentService paymentService;
    // Reports (dashboard, monthly, member statement)
    private final ContributionReportingService reportingService;
    // Direct database access for contributions
    private final ContributionRepository contributionRepository;
    // Direct database access for payments
    private final ContributionPaymentRepository paymentRepository;
    // Direct database access for share holdings
    private final ShareHoldingRepository shareHoldingRepository;
    // Same service type as reportingService (see the note after the code)
    private final ContributionReportingService contributionReportingService;
    // Direct database access for employees
    private final EmployeeRepository employeeRepository;
    // Builds the Excel file of the single month report
    private final SingleMonthContributionExcelService singleMonthExcelService;
    private final MonthlyContributionRegisterExcelService monthlyRegisterExcelService;
    private final YearlyContributionRegisterExcelService yearlyRegisterExcelService;
    private final ShareWithdrawalService shareWithdrawalService;

    ///---------- Constructor Injection ----------
    // Spring passes every dependency in automatically
    public AdminContributionWorkspaceController(ContributionService contributionService, ShareService shareService, MonthlyContributionGenerationService generationService, ContributionPaymentService paymentService, ContributionReportingService reportingService, MemberCreditService memberCreditService, ContributionRepository contributionRepository, ContributionPaymentRepository paymentRepository, ShareHoldingRepository shareHoldingRepository, ContributionReportingService contributionReportingService, EmployeeRepository employeeRepository, SingleMonthContributionExcelService singleMonthExcelService, MonthlyContributionRegisterExcelService monthlyRegisterExcelService,
                                                YearlyContributionRegisterExcelService yearlyRegisterExcelService,
                                                ShareWithdrawalService shareWithdrawalService) {

        // Save each dependency in its field
        this.contributionService = contributionService;
        this.shareService = shareService;
        this.generationService = generationService;
        this.paymentService = paymentService;
        this.reportingService = reportingService;
        this.memberCreditService = memberCreditService;
        this.contributionRepository = contributionRepository;
        this.paymentRepository = paymentRepository;
        this.shareHoldingRepository = shareHoldingRepository;
        this.contributionReportingService = contributionReportingService;
        this.employeeRepository = employeeRepository;
        this.singleMonthExcelService = singleMonthExcelService;
        this.monthlyRegisterExcelService = monthlyRegisterExcelService;
        this.yearlyRegisterExcelService = yearlyRegisterExcelService;
        this.shareWithdrawalService = shareWithdrawalService;

    }


    ///==================== Monthly Report Data Models ====================///

    ///---------- Single Month Row ----------
    // One table row of the single-month report (compatible with the current Thymeleaf template)
    public record SingleMonthRecord(
            int slNo,                          // Col 1: Serial Number
            Long contributionId,               // Reference for action modals
            String employeeId,                 // Col 2: Employee ID
            String employeeFullName,           // Col 3: Employee Full Name
            String designation,                // Col 4: Bank Designation
            int oldShares,                     // Col 5: OLD Shares
            int newShares,                     // Col 6: NEW Shares
            int totalShares,                   // Col 7: Total Shares
            BigDecimal monthlyRatePerShare,    // Monthly rate per share (BDT)
            BigDecimal totalShareValue,        // Total Share Value (BDT)
            BigDecimal monthlyDue,             // Col 9: Monthly Due (BDT)
            BigDecimal cashReceived,           // Col 10: Cash Received (BDT)
            BigDecimal memberCreditApplied,    // Col 11: Member Credit Applied (BDT)
            BigDecimal totalPaid,              // Col 12: Total Contribution Paid (BDT)
            BigDecimal remainingDue,           // Col 13: Remaining Due (BDT)
            String paymentStatus,              // Col 14: Payment Status (PAID / PENDING / CANCELED)
            String paymentMethod,              // Active direct payment methods only
            String remarks,                    // Plain-words settlement note for Action Details
            BigDecimal contributionShareValue,  // Optional Share Value recorded on this contribution
            String contributionRemarks         // Optional administrator-entered contribution remark
    ) {}

    ///---------- Single Month Summary ----------
    // Overall monthly totals across all members in the month
    public record SingleMonthSummary(
            int totalMembers,          // Number of contributions in the month
            BigDecimal totalDue,       // Sum of dues
            BigDecimal totalCash,      // Sum of cash received
            BigDecimal totalCredit,    // Sum of member credit applied
            BigDecimal totalSettled,   // Cash + credit
            BigDecimal totalRemaining, // Still unpaid
            long paidCount,            // How many are fully paid
            long pendingCount,         // How many are not fully paid
            long canceledCount         // How many are canceled
    ) {}

    ///---------- Register Member Row ----------
    // One row of the multi-month matrix (horizontal register view)
    public record RegisterMemberRow(
            int slNo,                               // Serial number
            String employeeId,                      // Employee ID
            String employeeName,                    // Employee name
            String designation,                     // Designation
            int oldShares,                          // OLD shares
            int newShares,                          // NEW shares
            Map<YearMonth, BigDecimal> monthlySettled, // Amount settled in each month
            BigDecimal totalSettled,                // Total settled
            BigDecimal totalDue,                    // Total due
            BigDecimal remainingDue                 // Still unpaid
    ) {}


    ///==================== Main Workspace Page (GET) ====================///

    /** Unified forms carry their visible context; reject stale or substituted member selections. */
    @ModelAttribute
    public void validateOperationFormContext(jakarta.servlet.http.HttpServletRequest request) {
        if (!"POST".equals(request.getMethod()) || !"member-operations".equals(request.getParameter("workspaceContext"))) return;
        String member=request.getParameter("contextEmployeeId");
        String submitted=request.getParameter("employeeId");
        if (member == null || member.isBlank() || (submitted != null && !member.equals(submitted)))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Employee context changed. Reload the workspace.");
        var matcher=java.util.regex.Pattern.compile("/payments/(\\d+)/").matcher(request.getRequestURI());
        if (matcher.find()) {
            var due=contributionRepository.findById(Long.valueOf(matcher.group(1))).orElse(null);
            if (due == null || !due.getEmployee().getEmployeeId().equals(member)
                    || !String.valueOf(due.getContributionMonth().getYear()).equals(request.getParameter("contextYear"))
                    || !String.valueOf(due.getContributionMonth().getMonthValue()).equals(request.getParameter("contextMonth")))
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Payment context changed. Reload the workspace.");
        }
    }

    ///---------- Show The Workspace Page ----------
    // Opens at /admin/contribution-workspace?tab=...
    // Every parameter comes from the URL query string (@RequestParam)
    // The method only fills the Model for the selected tab, then returns the template name
    @GetMapping
    public String workspace(
            // Navigation tabs and sub-tabs
            @RequestParam(defaultValue = "overview") String tab,
            @RequestParam(name = "view", defaultValue = "single") String monthlyView,

            // Filter identifiers (which employee, contribution, or share holding is selected)
            @RequestParam(required = false) String employeeId,
            @RequestParam(required = false) Long contributionId,
            @RequestParam(required = false) Long holdingId,

            // Period selectors (default is the current month)
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,

            // Single-month table: search text, status filter, paging
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "ALL") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,

            // Sorting
            @RequestParam(name = "sortBy", defaultValue = "employeeId") String sortBy,
            @RequestParam(name = "sortDir", defaultValue = "asc") String sortDir,

            // Register view: month range (format yyyy-MM)
            @RequestParam(name = "rangeFrom", required = false) String rangeFrom,
            @RequestParam(name = "rangeTo", required = false) String rangeTo,

            // Contributions tab filters
            @RequestParam(required = false) String recordEmployeeId,
            @RequestParam(defaultValue = "ALL") String recordStatus,

            // Payments tab search filters
            @RequestParam(required = false) String payEmployeeId,
            @RequestParam(required = false) String payEmployeeName,
            @RequestParam(defaultValue = "ALL") String payStatus,

            @RequestParam(required = false) String employeeName,
            @RequestParam(defaultValue = "") String mfoAction,
            @RequestParam(defaultValue = "ANNUAL") String statementType,
            @RequestParam(required = false) Integer statementMonth,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            // Carries data from Java to the HTML template
            Model model
    ) {

        // Compatibility aliases retain the business employee and reporting period.
        if (Set.of("shares", "contributions", "payments", "member-operations").contains(tab)) {
            if (employeeId == null || employeeId.isBlank()) {
                employeeId = "contributions".equals(tab) ? recordEmployeeId : payEmployeeId;
            }
            if (employeeName == null || employeeName.isBlank()) employeeName = payEmployeeName;
            if ("ALL".equals(status)) status = "contributions".equals(tab) ? recordStatus : payStatus;
            status = status.trim().toUpperCase(Locale.ROOT);
            tab = "member-operations";
            if (contributionId != null) {
                var selected = contributionRepository.findById(contributionId).orElse(null);
                if (selected == null) {
                    model.addAttribute("operationError", "The selected contribution was not found.");
                    contributionId = null;
                } else if (employeeId != null && !employeeId.isBlank()
                        && !employeeId.trim().equals(selected.getEmployee().getEmployeeId())) {
                    model.addAttribute("operationError", "The selected contribution belongs to another employee. Select a record again.");
                    contributionId = null;
                } else if ((year != null && year != selected.getContributionMonth().getYear())
                        || (month != null && month != selected.getContributionMonth().getMonthValue())) {
                    model.addAttribute("operationError", "The selected contribution is outside the reporting month.");
                    contributionId = null;
                } else {
                    employeeId = selected.getEmployee().getEmployeeId();
                    year = selected.getContributionMonth().getYear();
                    month = selected.getContributionMonth().getMonthValue();
                }
            }
        }

        // Today's date in bank time
        LocalDate today = LocalDate.now(BANK_ZONE);

        // Use the selected year, or the current year when none is given
        int selectedYear = (year == null) ? today.getYear() : year;
        // Use the selected month, or the current month when none is given
        int selectedMonthNumber = (month == null) ? today.getMonthValue() : month;
        int selectedStatementMonth = statementMonth == null ? selectedMonthNumber : statementMonth;

        // Stop with an error if the year or month is outside the allowed range
        if (("yearly-records".equals(tab) || "statement".equals(tab) || "member-operations".equals(tab))
                && (selectedYear < 1900 || selectedYear > 9999 || selectedMonthNumber < 1 || selectedMonthNumber > 12)) {
            model.addAttribute("tab", tab);
            model.addAttribute("year", selectedYear);
            model.addAttribute("month", selectedMonthNumber);
            model.addAttribute("employeeId", employeeId == null ? "" : employeeId);
            model.addAttribute("statementType", statementType);
            model.addAttribute("annualStatus", status);
            model.addAttribute("annualEmployeeName", employeeName == null ? "" : employeeName);
            model.addAttribute("annualSize", size);
            model.addAttribute("sortBy", sortBy);
            model.addAttribute("sortDir", sortDir);
            model.addAttribute("reportError", "Invalid reporting year or month.");
            model.addAttribute("operationError", "Invalid reporting year or month.");
            model.addAttribute("operationStatus", status);
            model.addAttribute("employeeName", employeeName == null ? "" : employeeName);
            return WORKSPACE_VIEW;
        }
        validateYearMonth(selectedYear, selectedMonthNumber);

        // Unknown tab name: fall back to the overview tab
        if (!VALID_TABS.contains(tab)) {

            tab = "overview";

        }

        // Unknown view name: fall back to the single-month view
        if (!VALID_MONTHLY_VIEWS.contains(monthlyView)) {

            monthlyView = "single";

        }

        // First day of the selected month (all monthly data is keyed by this date)
        LocalDate selectedMonth = LocalDate.of(selectedYear, selectedMonthNumber, 1);

        // ====================================================================
        // Common model attributes (sent for every tab)
        // ====================================================================

        // Selected tab and sub-view
        model.addAttribute("tab", tab);
        model.addAttribute("monthlyView", monthlyView);
        // Selected period
        model.addAttribute("year", selectedYear);
        model.addAttribute("month", selectedMonthNumber);
        model.addAttribute("selectedMonth", selectedMonth);
        // Selected employee (empty text when none)
        model.addAttribute("employeeId", employeeId == null ? "" : employeeId.trim());
        // Selected contribution and share holding
        model.addAttribute("contributionId", contributionId);
        model.addAttribute("holdingId", holdingId);

        // Sorting and filter values go back to the page so the form keeps them
        model.addAttribute("sortBy", sortBy);
        model.addAttribute("sortDir", sortDir);
        model.addAttribute("rangeFrom", rangeFrom == null ? "" : rangeFrom);
        model.addAttribute("rangeTo", rangeTo == null ? "" : rangeTo);

        // Empty defaults, so Thymeleaf never fails on a missing attribute
        model.addAttribute("dashboard", null);
        model.addAttribute("monthly", null);
        model.addAttribute("holdings", null);
        model.addAttribute("shareHistory", null);
        model.addAttribute("rateHistory", null);
        model.addAttribute("shareHistoryByHolding", Map.of());
        model.addAttribute("rateHistoryByHolding", Map.of());
        model.addAttribute("valuationHistoryByHolding", Map.of());
        model.addAttribute("selectedHolding", null);
        model.addAttribute("contributions", null);
        model.addAttribute("paymentSummary", null);
        model.addAttribute("statement", null);
        model.addAttribute("monthlyRecords", List.of());
        model.addAttribute("monthlySummary", null);
        model.addAttribute("registerRecords", List.of());
        model.addAttribute("registerMonths", List.of());

        // ====================================================================
        // TAB 1: OVERVIEW DASHBOARD
        // ====================================================================
        if ("overview".equals(tab)) {

            // Dashboard period: the 12 months ending at the selected month
            LocalDate fromMonth = selectedMonth.minusMonths(11);

            // Dashboard data for the 12-month period
            model.addAttribute("dashboard", reportingService.dashboard(fromMonth.getYear(), fromMonth.getMonthValue(), selectedYear, selectedMonthNumber));

            // Report for the selected month only
            model.addAttribute("monthly", reportingService.monthly(selectedYear, selectedMonthNumber));

        }


        // ====================================================================
        // TAB 2: SHARES
        // ====================================================================

        // Empty defaults for the shares tab
        model.addAttribute("shareEmployee", null);
        model.addAttribute("shareEmployeeError", null);
        // Today's date, used by the share forms
        model.addAttribute("today", today);

        if ("member-operations".equals(tab)) {
            try {
                loadMemberOperations(employeeId, employeeName, selectedMonth, status, page, size,
                        holdingId, contributionId, mfoAction, model);
            } catch (IllegalStateException ex) {
                model.addAttribute("operationError", "Financial records require reconciliation. Please ask support to review the settlement history.");
            } catch (org.springframework.dao.DataAccessException ex) {
                model.addAttribute("operationError", "Records could not be loaded. Please retry or contact support.");
            }
        }

        // ====================================================================
        // TAB 5: MONTHLY RECORDS (routing only)
        //   A. Single month records: processSingleMonthRecords(...)
        //   B. Multi-month register: processMonthlyRegister(...)
        //   C. History: existing placeholder (no change)
        // ====================================================================
        if ("monthly-records".equals(tab)) {

            // Pick the right handler for the selected view
            loadMonthlyRecordsTab(monthlyView, selectedMonth, rangeFrom, rangeTo, search, status, page, size, sortBy, sortDir, model);

        }

        // ====================================================================
        // TAB 6: YEARLY RECORDS
        // ====================================================================
        if ("yearly-records".equals(tab)) {

            model.addAttribute("annualStatus", status);
            model.addAttribute("annualEmployeeName", employeeName == null ? "" : employeeName);
            model.addAttribute("annualSize", size);
            try {
                model.addAttribute("annualReport", reportingService.annual(selectedYear,employeeId,employeeName,status,page,size,sortBy,sortDir));
            } catch (IllegalArgumentException ex) {
                model.addAttribute("reportError", ex.getMessage());
            } catch (IllegalStateException ex) {
                model.addAttribute("reportError", "The report could not be reconciled. Please ask support to review the settlement records.");
            }
        }
        if ("statement".equals(tab)) {
            model.addAttribute("statementType", statementType);
            model.addAttribute("statementMonth", selectedStatementMonth);
            model.addAttribute("startDate", startDate);
            model.addAttribute("endDate", endDate);
            try {
                reportingService.statementPeriod(statementType,selectedYear,selectedStatementMonth,startDate,endDate);
                if (employeeId != null && !employeeId.isBlank()) {
                    model.addAttribute("statementReport", reportingService.statement(employeeId,statementType,selectedYear,selectedStatementMonth,startDate,endDate));
                }
            } catch (IllegalArgumentException | com.fundaxis.exception.EmployeeNotFoundException ex) {
                model.addAttribute("reportError", ex.getMessage());
            } catch (IllegalStateException ex) {
                model.addAttribute("reportError", "The statement could not be reconciled. Please ask support to review the settlement records.");
            }
        }

        // Show templates/admin/contribution-workspace.html
        return WORKSPACE_VIEW;

    }


    private void verifyHoldingEmployee(Long holdingId, String employeeId) {
        var holding = shareHoldingRepository.findById(holdingId)
                .orElseThrow(() -> new IllegalArgumentException("Share holding not found."));
        if (employeeId == null || !holding.getEmployee().getEmployeeId().equals(employeeId.trim()))
            throw new IllegalArgumentException("The selected holding does not belong to this employee.");
    }

    private void preserveOperationContext(jakarta.servlet.http.HttpServletRequest request, RedirectAttributes redirect) {
        if (!"member-operations".equals(request.getParameter("workspaceContext"))) return;
        redirect.addAttribute("tab", "member-operations");
        String member = request.getParameter("contextEmployeeId");
        if (member != null && !member.isBlank()) redirect.addAttribute("employeeId",member.trim());
        try {
            int y=Integer.parseInt(request.getParameter("contextYear"));
            int m=Integer.parseInt(request.getParameter("contextMonth"));
            validateYearMonth(y,m);
            redirect.addAttribute("year",y); redirect.addAttribute("month",m);
        } catch (IllegalArgumentException ignored) { /* Existing route defaults remain available. */ }
        if (request.getRequestURI().contains("/credits/")) redirect.addAttribute("mfoAction","credit");
        String status=request.getParameter("contextStatus");
        if (status != null && Set.of("ALL","PENDING","PAID","CANCELED").contains(status)) redirect.addAttribute("status",status);
    }

    private void loadMemberOperations(String employeeId, String employeeName, LocalDate period,
            String status, int page, int size, Long holdingId, Long contributionId, String action, Model model) {
        String id = employeeId == null ? "" : employeeId.trim();
        String name = employeeName == null ? "" : employeeName.trim();
        model.addAttribute("employeeName", name);
        model.addAttribute("operationStatus", status);
        model.addAttribute("operationCandidates", List.of());
        model.addAttribute("creditLoaded", "credit".equals(action));
        model.addAttribute("creditHistory", List.of());
        model.addAttribute("allocationHistory", List.of());
        model.addAttribute("creditBalance", null);
        model.addAttribute("foundEmployee", null);
        model.addAttribute("operationLines", Map.of());
        model.addAttribute("operationRows", List.of());
        model.addAttribute("contributionId", null);
        model.addAttribute("holdingId", null);
        if (page < 0 || !Set.of(10,25,50,100).contains(size)
                || !Set.of("ALL","PENDING","PAID","CANCELED").contains(status)) {
            model.addAttribute("operationError", "Invalid status, page or page size.");
            return;
        }
        Employee employee = null;
        if (!id.isBlank()) {
            String requestedId = id;
            employee = employeeRepository.findByEmployeeId(requestedId)
                    .or(() -> employeeRepository.findByEmployeeIdIgnoreCase(requestedId)).orElse(null);
            if (employee == null && id.chars().allMatch(Character::isDigit)) {
                var matches = employeeRepository.findByEmployeeIdEndingWithIgnoreCase(id,
                        org.springframework.data.domain.PageRequest.of(0, 11,
                                org.springframework.data.domain.Sort.by("employeeId"))).getContent();
                if (matches.size() == 1) {
                    employee = matches.get(0);
                } else if (!matches.isEmpty()) {
                    model.addAttribute("operationCandidates", matches);
                    model.addAttribute("operationError", "More than one Employee ID ends with " + id
                            + ". Select the correct employee below.");
                    return;
                }
            }
            if (employee == null) {
                model.addAttribute("operationError", "No employee found for Employee ID: " + id);
                return;
            }
            id = employee.getEmployeeId();
            model.addAttribute("employeeId", id);
            model.addAttribute("foundEmployee", employee);
            model.addAttribute("today", LocalDate.now(BANK_ZONE));
            try {
                model.addAttribute("withdrawalEnabled", shareWithdrawalService.isEnabled());
                model.addAttribute("withdrawalApprovalEnabled", shareWithdrawalService.isApprovalEnabled());
                model.addAttribute("withdrawalPostingEnabled", shareWithdrawalService.isPostingEnabled());
                model.addAttribute("withdrawalCheckerAuthorized", shareWithdrawalService.isCheckerAuthorized());
                model.addAttribute("withdrawalActor", shareWithdrawalService.currentActorName());
                model.addAttribute("withdrawalRequestGateMessage", shareWithdrawalService.requestConfigurationMessage());
                model.addAttribute("withdrawalApprovalGateMessage", shareWithdrawalService.approvalConfigurationMessage());
                model.addAttribute("withdrawalPostingGateMessage", shareWithdrawalService.postingConfigurationMessage());
                if (shareWithdrawalService.isEnabled()) {
                    var withdrawalPortfolio = shareWithdrawalService.portfolio(id);
                    model.addAttribute("withdrawalPortfolio", withdrawalPortfolio);
                    model.addAttribute("withdrawalRecent", shareWithdrawalService.history(id, null, null, "", "", 0, 10, "requestDate")
                            .getContent().stream().limit(5).toList());
                    model.addAttribute("withdrawalHistory", shareWithdrawalService.employeeHistory(id));
                    model.addAttribute("withdrawalRequestToken", UUID.randomUUID().toString());
                }
            } catch (org.springframework.dao.DataAccessException ex) {
                model.addAttribute("withdrawalEnabled", false);
                model.addAttribute("withdrawalStorageUnavailable", true);
                model.addAttribute("withdrawalError", "Withdrawal storage is unavailable. Verify the share_withdrawals schema setup before accepting requests.");
            } catch (IllegalStateException | IllegalArgumentException ex) {
                model.addAttribute("withdrawalEnabled", false);
                model.addAttribute("withdrawalError", ex.getMessage());
            }
            var memberHoldings = shareService.getEmployeeShares(id);
            model.addAttribute("holdings", memberHoldings);
            var memberTransactions = shareService.getTransactionHistoryForEmployee(id);
            model.addAttribute("shareHistoryByHolding", memberTransactions.stream()
                    .filter(t -> t.getTransactionType() != ShareTransaction.ShareTransactionType.VALUATION_CHANGE)
                    .collect(Collectors.groupingBy(t -> t.getShareHolding().getId())));
            model.addAttribute("valuationHistoryByHolding", memberTransactions.stream()
                    .filter(t -> t.getShareValueAfter() != null)
                    .collect(Collectors.groupingBy(t -> t.getShareHolding().getId())));
            model.addAttribute("rateHistoryByHolding", shareService.getRateHistoryForEmployee(id).stream()
                    .collect(Collectors.groupingBy(r -> r.getShareHolding().getId())));
            model.addAttribute("holdingCategories", memberHoldings.stream().map(h -> h.getShareCategory().name()).toList());
            model.addAttribute("currentHoldingRateTotal", memberHoldings.stream()
                    .map(ShareHolding::getExpectedMonthlyContribution).reduce(BigDecimal.ZERO,BigDecimal::add));
            model.addAttribute("operationSummary", reportingService.member(id, period.getYear(), period.getMonthValue(),
                    period.getYear(), period.getMonthValue()));
            try {
                model.addAttribute("historicalShares", shareService.getHistoricalShareBreakdown(id,period));
                model.addAttribute("historicalExpected", shareService.calculateMonthlyContribution(id,period).expectedAmount());
            } catch (IllegalStateException ex) {
                model.addAttribute("historicalShares", null);
                model.addAttribute("historicalSharesError", "Historical shares or rates require review. Stored obligations remain authoritative.");
            }
            if (holdingId != null) {
                var selected = memberHoldings.stream().filter(h -> h.getId().equals(holdingId)).findFirst().orElse(null);
                if (selected == null) {
                    model.addAttribute("operationError", "The selected holding does not belong to this employee.");
                } else {
                    model.addAttribute("holdingId", holdingId);
                    model.addAttribute("selectedHolding", selected);
                }
            }
            // Histories are loaded only after a member is selected.
            model.addAttribute("creditBalance", memberCreditService.getAvailableBalance(id));
            if ("credit".equals(action)) {
                var history = memberCreditService.getHistory(id);
                model.addAttribute("creditHistory", history);
                model.addAttribute("recentCredits", history.stream().limit(3).toList());
                model.addAttribute("allocationHistory", memberCreditService.getAllocationHistory(id));
            } else {
                model.addAttribute("recentCredits", memberCreditService.getRecentCollections(id));
            }
        } else if (!name.isBlank()) {
            model.addAttribute("operationCandidates", employeeRepository.findByFullNameContainingIgnoreCase(name,
                    org.springframework.data.domain.PageRequest.of(0,10,org.springframework.data.domain.Sort.by("employeeId"))).getContent());
        }
        String pattern = "%" + name.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_") + "%";
        var records = contributionRepository.findOperations(period, id, pattern,
                "ALL".equals(status) ? null : Contribution.ContributionStatus.valueOf(status),
                org.springframework.data.domain.PageRequest.of(page,size,org.springframework.data.domain.Sort.by("employee.employeeId").and(org.springframework.data.domain.Sort.by("id"))));
        // Publish the table only after all page rows reconcile, avoiding a partial unsafe view.
        var lines = reportingService.operationLines(records.getContent());
        model.addAttribute("operationPage", records);
        model.addAttribute("operationRows", records.getContent());
        model.addAttribute("operationLines", lines);
        // Select the member's one monthly obligation automatically, never create one by viewing.
        if (employee != null && contributionId == null && model.getAttribute("operationError") == null) {
            var memberDues = contributionRepository.findByEmployeeEmployeeIdAndContributionMonth(id,period);
            if (memberDues.size() == 1) contributionId = memberDues.get(0).getId();
        }
        if (employee != null && contributionId != null) {
            model.addAttribute("contributionId", contributionId);
            model.addAttribute("paymentSummary", paymentService.getSummary(contributionId));
            model.addAttribute("paymentAppliedCredit", memberCreditService.getAppliedTotal(contributionId));
        }
    }

    ///==================== Monthly Records Router (Tab 5) ====================///

    ///---------- Choose The Handler For The Selected View ----------
    // Dedicated handlers keep Single Month and Register logic separate
    private void loadMonthlyRecordsTab(String monthlyView, LocalDate selectedMonth, String rangeFrom, String rangeTo, String search, String status, int page, int size, String sortBy, String sortDir, Model model) {

        // View "single": one month, table with paging
        if ("single".equals(monthlyView)) {

            processSingleMonthRecords(selectedMonth, search, status, page, size, sortBy, sortDir, model);

            // View "register": several months side by side
        } else if ("register".equals(monthlyView)) {

            processMonthlyRegister(selectedMonth, rangeFrom, rangeTo, search, model);

            // View "history": placeholder, not built yet
        } else if ("history".equals(monthlyView)) {

            // Existing placeholder: history UI is not implemented yet
            model.addAttribute("historyRecords", List.of());

        }

    }

    ///==================== Tab: Monthly Records -> Tab: Single Month Records ====================///
    ///==================== Monthly Records: Single Month ====================///

    ///---------- Build The Monthly Record Rows, Totals, And Paging ----------
    // Data models: SingleMonthRecord / SingleMonthSummary (defined at the top)
    // Existing calculations are intentionally preserved, no business rules changed
    private void processSingleMonthRecords(
            LocalDate targetMonth,
            String search,
            String statusFilter,
            int page,
            int size,
            String sortBy,
            String sortDir,
            Model model
    ) {

        // 1. Load all dues of the selected contribution month
        List<Contribution> dues = contributionRepository.findByContributionMonth(targetMonth);

        // Collect the IDs of these dues
        List<Long> dueIds = dues.stream().map(Contribution::getId).toList();
        // Cash received per contribution ID (filled below)
        Map<Long, BigDecimal> cashMap = new HashMap<>();
        Map<Long, List<ContributionPayment>> activePaymentsMap = new HashMap<>();

        // Load payments only when there are dues
        if (!dueIds.isEmpty()) {

            // All payments of these dues, loaded with ONE query
            List<ContributionPayment> payments = paymentRepository.findByContributionIdIn(dueIds);

            // Add up the cash of each contribution
            for (ContributionPayment p : payments) {

                // Reversed payments are not counted
                if (!p.isReversed()) {

                    // Add this payment to the contribution's cash total
                    cashMap.merge(p.getContribution().getId(), p.getPaymentAmount(), BigDecimal::add);
                    Long contributionId = p.getContribution().getId();
                    activePaymentsMap.computeIfAbsent(contributionId, ignored -> new ArrayList<>()).add(p);

                }

            }

        }

        // All rows before filtering and paging
        List<SingleMonthRecord> rawRows = new ArrayList<>();

        // Running totals for the summary box
        BigDecimal totalMonthDue = BigDecimal.ZERO;
        BigDecimal totalMonthCash = BigDecimal.ZERO;
        BigDecimal totalMonthCredit = BigDecimal.ZERO;
        BigDecimal totalMonthSettled = BigDecimal.ZERO;
        BigDecimal totalMonthRemaining = BigDecimal.ZERO;

        // Counters per status
        long paidCount = 0;
        long pendingCount = 0;
        long canceledCount = 0;

        // Build one row per due
        for (Contribution due : dues) {

            // Employee details (empty text when the employee is missing)
            Employee emp = due.getEmployee();
            String empId = (emp != null) ? emp.getEmployeeId() : "";
            String empName = (emp != null) ? emp.getFullName() : "";
            String designation = (emp != null && emp.getEmployeeDesignation() != null) ? emp.getEmployeeDesignation().name() : "";

            // ----- Historical share information -----

            // OLD / NEW share counts of this employee AS OF the selected month (not today's holdings)
            ShareService.HistoricalShareBreakdown shareInfo = shareService.getHistoricalShareBreakdown(empId, targetMonth);

            // OLD shares in that month
            int oldShares = shareInfo.oldShares();
            // NEW shares in that month
            int newShares = shareInfo.newShares();
            // Total shares in that month
            int totalShares = shareInfo.totalShares();

            // ----- Settlement calculations -----

            // A canceled contribution has no due amount
            boolean isCanceled = (due.getContributionStatus() == Contribution.ContributionStatus.CANCELED);
            BigDecimal dueAmount = isCanceled ? BigDecimal.ZERO : due.getContributionAmount();
            // Cash received for this due (zero when none)
            BigDecimal cash = cashMap.getOrDefault(due.getId(), BigDecimal.ZERO);
            // Member credit applied to this due
            BigDecimal credit = memberCreditService.getAppliedTotal(due.getId());
            // Total paid = cash + credit
            BigDecimal totalPaid = cash.add(credit);
            // Remaining = due - paid, never below zero
            BigDecimal remaining = isCanceled ? BigDecimal.ZERO : dueAmount.subtract(totalPaid).max(BigDecimal.ZERO);

            // Status: CANCELED, PAID (nothing remaining), or PENDING
            String statusStr = isCanceled ? "CANCELED" : (remaining.signum() == 0 ? "PAID" : "PENDING");
            String paymentMethod = summarizeActivePaymentMethods(
                    activePaymentsMap.getOrDefault(due.getId(), List.of()));

            // Count this row in its status counter
            switch (statusStr) {

                case "PAID" -> paidCount++;
                case "PENDING" -> pendingCount++;
                default -> canceledCount++;

            }

            // ----- Financial summary totals (canceled rows are not counted) -----
            if (!isCanceled) {

                totalMonthDue = totalMonthDue.add(dueAmount);
                totalMonthCash = totalMonthCash.add(cash);
                totalMonthCredit = totalMonthCredit.add(credit);
                totalMonthSettled = totalMonthSettled.add(totalPaid);
                totalMonthRemaining = totalMonthRemaining.add(remaining);

            }

            // ----- Plain-words remark for the Action Details modal -----
            String remarks;

            if (isCanceled) {

                remarks = "Contribution canceled";

            } else if (remaining.signum() == 0) {

                // Fully paid: say how (cash, credit, or both)
                remarks = credit.signum() > 0 ? (cash.signum() > 0 ? "Paid by cash + credit" : "Paid from credit") : "Paid in full";

            } else {

                // Not fully paid: partly paid or nothing paid
                remarks = (cash.signum() > 0 || credit.signum() > 0) ? "Partially paid" : "Unpaid";

            }

            // TODO (next development step): resolve historical rate and valuation
            // from effective-dated share information before displaying/exporting them
            // Keep these values null until the rate-history logic is confirmed; do not
            // substitute a current rate or derive share value from monthlyDue
            BigDecimal monthlyRatePerShare = null;
            BigDecimal totalShareValue = null;

            // Add the row (slNo is 0 for now, the real serial number is set after paging)
            rawRows.add(new SingleMonthRecord(0, due.getId(), empId, empName, designation, oldShares, newShares,
                    totalShares, monthlyRatePerShare, totalShareValue, dueAmount, cash, credit, totalPaid, remaining,
                    statusStr, paymentMethod, remarks, due.getShareValue(), due.getRemarks()));

        }

        // Build the summary object for the page
        SingleMonthSummary summary = new SingleMonthSummary(dues.size(), totalMonthDue, totalMonthCash, totalMonthCredit, totalMonthSettled, totalMonthRemaining, paidCount, pendingCount, canceledCount);

        // All rows of the month, BEFORE search, status filter, and paging
        // The Excel export reads this, so the file always contains every record
        model.addAttribute("monthlyExportRecords", List.copyOf(rawRows));

        // ----- Filter by search text (Employee ID or name) and by status -----
        String query = (search != null) ? search.trim().toLowerCase() : "";
        String activeStatus = (statusFilter != null) ? statusFilter.trim().toUpperCase() : "ALL";

        // Keep only the rows that match both filters
        List<SingleMonthRecord> filtered = rawRows.stream()
                // Search text: empty keeps all, otherwise the ID or name must contain it
                .filter(r -> query.isEmpty() || r.employeeId().toLowerCase().contains(query) || r.employeeFullName().toLowerCase().contains(query))
                // Status: "ALL" keeps all, otherwise the status must match
                .filter(r -> "ALL".equals(activeStatus) || r.paymentStatus().equalsIgnoreCase(activeStatus))
                // Mutable list, because it is sorted next
                .collect(Collectors.toList());

        // Sort the rows by the selected column and direction
        filtered.sort(resolveComparator(sortBy, sortDir));

        // ----- Paging (page numbers start at 0) -----

        // Number of rows after filtering
        int totalElements = filtered.size();
        // Single Month Records must display exactly 10 records per page (the "size" parameter is ignored)
        int safeSize = 10;
        // Number of pages (at least 1)
        int totalPages = Math.max(1, (int) Math.ceil((double) totalElements / safeSize));
        // Keep the requested page between the first and the last page
        int safePage = Math.min(Math.max(0, page), totalPages - 1);

        // Position of the first and last row of this page
        int fromIndex = safePage * safeSize;
        int toIndex = Math.min(fromIndex + safeSize, totalElements);

        // Rows of the current page (with real serial numbers)
        List<SingleMonthRecord> pagedRows = new ArrayList<>();

        // Only build the page when it has rows
        if (fromIndex < totalElements) {

            // The rows of this page
            List<SingleMonthRecord> subList = filtered.subList(fromIndex, toIndex);

            // Copy each row with its continuous serial number
            for (int i = 0; i < subList.size(); i++) {

                SingleMonthRecord raw = subList.get(i);

                // First argument is the serial number: continues across pages (page 2 starts at 11)
                pagedRows.add(new SingleMonthRecord(fromIndex + i + 1, raw.contributionId(), raw.employeeId(),
                        raw.employeeFullName(), raw.designation(), raw.oldShares(), raw.newShares(), raw.totalShares(),
                        raw.monthlyRatePerShare(), raw.totalShareValue(), raw.monthlyDue(), raw.cashReceived(),
                        raw.memberCreditApplied(), raw.totalPaid(), raw.remainingDue(), raw.paymentStatus(),
                        raw.paymentMethod(), raw.remarks(), raw.contributionShareValue(), raw.contributionRemarks()));

            }

        }

        // ----- Send everything to the page -----
        model.addAttribute("monthlyRecords", pagedRows);
        model.addAttribute("monthlySummary", summary);
        model.addAttribute("search", search == null ? "" : search.trim());
        model.addAttribute("status", activeStatus);
        model.addAttribute("currentPage", safePage);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("pageSize", safeSize);
        model.addAttribute("totalElements", totalElements);

    }

    /** Human-readable active direct payment methods; reversals and member credit are excluded. */
    static String summarizeActivePaymentMethods(List<ContributionPayment> payments) {
        if (payments == null || payments.isEmpty()) return "—";
        SortedSet<String> methods = new TreeSet<>();
        boolean methodNotRecorded = false;
        for (ContributionPayment payment : payments) {
            if (payment == null || payment.isReversed()) continue;
            if (payment.getPaymentMethod() != null
                    && payment.getPaymentMethod() != ContributionPayment.PaymentMethod.UNSPECIFIED) {
                methods.add(ContributionPayment.displayPaymentMethod(payment.getPaymentMethod()));
            } else {
                methodNotRecorded = true;
            }
        }
        if (payments.stream().noneMatch(payment -> payment != null && !payment.isReversed())) return "—";
        if (methodNotRecorded) methods.add("Not Recorded");
        return String.join(", ", methods);
    }


    ///==================== Tab: Monthly Records -> Tab: Monthly Contribution Register ====================///
    ///==================== Monthly Records: Multi-Month Register ====================///

    ///---------- Build The Multi-Month Register And Its Grand Totals ----------
    // Separate from the single month view
    private void processMonthlyRegister(
            LocalDate selectedMonth,
            String rangeFrom,
            String rangeTo,
            String search,
            Model model
    ) {

        // 1. Default range: the 12 months ending at the selected month
        YearMonth endMonth = YearMonth.from(selectedMonth);
        YearMonth startMonth = endMonth.minusMonths(11);

        // 2. Use the start month typed by the admin (format yyyy-MM), if provided
        if (rangeFrom != null && !rangeFrom.isBlank()) {

            try {

                startMonth = YearMonth.parse(rangeFrom.trim());

            } catch (Exception ignored) {

                // Keep the default start month if the input is invalid

            }

        }

        // 3. Use the end month typed by the admin (format yyyy-MM), if provided
        if (rangeTo != null && !rangeTo.isBlank()) {

            try {

                endMonth = YearMonth.parse(rangeTo.trim());

            } catch (Exception ignored) {

                // Keep the default end month if the input is invalid

            }

        }

        // 4. The start month must not be after the end month
        if (startMonth.isAfter(endMonth)) {

            startMonth = endMonth.minusMonths(11);

        }

        // 5. Build the list of months (the table columns)
        List<YearMonth> registerMonths = new ArrayList<>();

        // Go from the start month to the end month, one month at a time
        for (YearMonth month = startMonth; !month.isAfter(endMonth); month = month.plusMonths(1)) {

            registerMonths.add(month);

        }

        // 6. Load the employee contribution data for the period
        List<ContributionReportingService.MonthlyRegisterRow> registerRecords = contributionReportingService.getMonthlyContributionRegister(startMonth.getYear(), startMonth.getMonthValue(), endMonth.getYear(), endMonth.getMonthValue(), search);

        // 7. Calculate the register grand totals
        int registerTotalOldShares = 0;
        int registerTotalNewShares = 0;

        BigDecimal registerTotalPaid = BigDecimal.ZERO;
        BigDecimal registerTotalDue = BigDecimal.ZERO;
        BigDecimal registerTotalOutstanding = BigDecimal.ZERO;

        // Total per month (LinkedHashMap keeps the months in order)
        Map<YearMonth, BigDecimal> registerMonthTotals = new LinkedHashMap<>();

        // Start every month total at zero
        for (YearMonth m : registerMonths) {

            registerMonthTotals.put(m, BigDecimal.ZERO);

        }

        // Add up all displayed employees
        for (ContributionReportingService.MonthlyRegisterRow r : registerRecords) {

            // Share totals
            registerTotalOldShares += r.oldShares();
            registerTotalNewShares += r.newShares();

            // Money totals
            registerTotalPaid = registerTotalPaid.add(r.totalSettled());
            registerTotalDue = registerTotalDue.add(r.totalDue());
            registerTotalOutstanding = registerTotalOutstanding.add(r.remainingDue());

            // Add this employee's amount to each month's total
            for (YearMonth m : registerMonths) {

                // Status of this employee in this month
                String status = r.monthlyStatuses().get(m);

                // Skip months with no record or a canceled contribution
                if ("NO_RECORD".equals(status) || "CANCELED".equals(status)) {

                    continue;

                }

                // Amount settled in this month (zero when none)
                BigDecimal amount = r.monthlySettled().getOrDefault(m, BigDecimal.ZERO);

                // Add it to the month total
                registerMonthTotals.merge(m, amount, BigDecimal::add);

            }

        }

        // 8. Send the register records and totals to the page
        model.addAttribute("registerRecords", registerRecords);
        model.addAttribute("registerMonths", registerMonths);
        // First day of the first month and last day of the last month
        model.addAttribute("registerStartMonth", startMonth.atDay(1));
        model.addAttribute("registerEndMonth", endMonth.atEndOfMonth());

        // Grand total values
        model.addAttribute("registerTotalMembers", registerRecords.size());
        model.addAttribute("registerTotalOldShares", registerTotalOldShares);
        model.addAttribute("registerTotalNewShares", registerTotalNewShares);
        model.addAttribute("registerTotalShares", registerTotalOldShares + registerTotalNewShares);

        model.addAttribute("registerMonthTotals", registerMonthTotals);
        model.addAttribute("registerTotalPaid", registerTotalPaid);
        model.addAttribute("registerTotalDue", registerTotalDue);
        model.addAttribute("registerTotalOutstanding", registerTotalOutstanding);

    }


    ///==================== Tab: Monthly Records -> Monthly Contribution Register [Excel Export] ====================///
    /// Export Report allows users to download reports or financial data from the FundAxis System in Excel format for further analysis and record-keeping

    @GetMapping("/monthly-register/export")
    public ResponseEntity<byte[]> exportMonthlyRegister(
            @RequestParam(required = false) String rangeFrom,
            @RequestParam(required = false) String rangeTo,
            @RequestParam(required = false) String search
    ) {
        LocalDate selectedMonth = LocalDate.now(BANK_ZONE).withDayOfMonth(1);
        ExtendedModelMap exportModel = new ExtendedModelMap();
        processMonthlyRegister(selectedMonth, rangeFrom, rangeTo, search, exportModel);

        @SuppressWarnings("unchecked")
        List<ContributionReportingService.MonthlyRegisterRow> records =
                (List<ContributionReportingService.MonthlyRegisterRow>) exportModel.getAttribute("registerRecords");

        @SuppressWarnings("unchecked")
        List<YearMonth> months = (List<YearMonth>) exportModel.getAttribute("registerMonths");
        byte[] contents = monthlyRegisterExcelService.export(
                months, records,
                (BigDecimal) exportModel.getAttribute("registerTotalDue"),
                (BigDecimal) exportModel.getAttribute("registerTotalPaid"),
                (BigDecimal) exportModel.getAttribute("registerTotalOutstanding"),
                (Integer) exportModel.getAttribute("registerTotalOldShares"),
                (Integer) exportModel.getAttribute("registerTotalNewShares"));
        String filename = "FundAxis_Monthly_Register_" + months.get(0) + "_to_" + months.get(months.size() - 1) + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(contents.length)
                .body(contents);
    }

    ///==================== Monthly Records: Sorting Helper ====================///

    ///---------- Choose How To Sort The Single Month Rows ----------
    // sortBy: name, amount, outstanding (anything else sorts by Employee ID)
    // sortDir: "desc" reverses the order
    private Comparator<SingleMonthRecord> resolveComparator(String sortBy, String sortDir) {

        // Pick the sort rule from the column name
        Comparator<SingleMonthRecord> comp = switch (sortBy.toLowerCase()) {

            // By employee name (ignoring upper/lower case)
            case "name" -> Comparator.comparing(SingleMonthRecord::employeeFullName, String.CASE_INSENSITIVE_ORDER);
            // By monthly due amount
            case "amount" -> Comparator.comparing(SingleMonthRecord::monthlyDue);
            // By remaining (unpaid) amount
            case "outstanding" -> Comparator.comparing(SingleMonthRecord::remainingDue);
            // Default: by Employee ID
            default -> Comparator.comparing(SingleMonthRecord::employeeId, String.CASE_INSENSITIVE_ORDER);

        };

        // Descending order reverses the rule
        return "desc".equalsIgnoreCase(sortDir) ? comp.reversed() : comp;

    }


    ///==================== Share Management Actions ====================///

    @PostMapping("/withdrawals")
    public String createWithdrawal(@RequestParam String employeeId,
            @RequestParam ShareHolding.ShareCategory category, @RequestParam Integer quantity,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveDate,
            @RequestParam String reason, @RequestParam String requestToken,
            @RequestParam Integer year, @RequestParam Integer month,
            @RequestParam(defaultValue = "ALL") String contextStatus,
            RedirectAttributes redirect) {
        redirect.addAttribute("tab", "member-operations");
        redirect.addAttribute("employeeId", employeeId);
        redirect.addAttribute("year", year);
        redirect.addAttribute("month", month);
        redirect.addAttribute("status", contextStatus);
        try {
            shareWithdrawalService.request(employeeId, category, quantity, effectiveDate, reason, requestToken);
            redirect.addFlashAttribute("withdrawalSuccess", "Withdrawal request recorded. Shares and money have not been deducted.");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            redirect.addFlashAttribute("withdrawalError", ex.getMessage());
            redirect.addAttribute("mfoAction", "withdrawal");
            redirect.addFlashAttribute("withdrawalCategory", category);
            redirect.addFlashAttribute("withdrawalQuantity", quantity);
            redirect.addFlashAttribute("withdrawalEffectiveDate", effectiveDate);
            redirect.addFlashAttribute("withdrawalReason", reason);
        } catch (org.springframework.dao.DataAccessException ex) {
            redirect.addFlashAttribute("withdrawalError", "The request could not be saved. Refresh and check for an existing request before retrying.");
            redirect.addAttribute("mfoAction", "withdrawal");
            redirect.addFlashAttribute("withdrawalCategory", category);
            redirect.addFlashAttribute("withdrawalQuantity", quantity);
            redirect.addFlashAttribute("withdrawalEffectiveDate", effectiveDate);
            redirect.addFlashAttribute("withdrawalReason", reason);
        }
        return WORKSPACE_REDIRECT;
    }

    @PostMapping(value="/withdrawals/{id}/valuation-preview", produces=MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ShareWithdrawalService.ValuationPreview previewWithdrawalValuation(@PathVariable Long id,
            @RequestParam Long version, @RequestParam BigDecimal proposedValuePerShare,
            @RequestParam BigDecimal proposedDeduction,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate valuationDate,
            @RequestParam String valuationReason) {
        return shareWithdrawalService.previewValuation(id,version,proposedValuePerShare,proposedDeduction,
                valuationDate,valuationReason);
    }

    @PostMapping("/withdrawals/{id}/{action}")
    public String withdrawalTransition(@PathVariable Long id, @PathVariable String action,
            @RequestParam Long version, @RequestParam(defaultValue = "") String reason,
            @RequestParam(defaultValue = "") String paymentReference,
            @RequestParam Integer year, @RequestParam Integer month, @RequestParam String employeeId,
            @RequestParam(defaultValue = "ALL") String contextStatus,
            RedirectAttributes redirect) {
        redirect.addAttribute("tab", "member-operations");
        redirect.addAttribute("employeeId", employeeId);
        redirect.addAttribute("year", year);
        redirect.addAttribute("month", month);
        redirect.addAttribute("status", contextStatus);
        try {
            switch (action) {
                case "approve" -> shareWithdrawalService.approve(id, version);
                case "reject" -> shareWithdrawalService.reject(id, version, reason);
                case "cancel" -> shareWithdrawalService.cancel(id, version, reason);
                case "post-shares" -> shareWithdrawalService.postShares(id, version);
                case "disburse" -> shareWithdrawalService.confirmDisbursement(id, version, paymentReference);
                default -> throw new IllegalArgumentException("Unsupported withdrawal action.");
            }
            redirect.addFlashAttribute("withdrawalSuccess", "Withdrawal status saved.");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            redirect.addFlashAttribute("withdrawalError", ex.getMessage());
            if ("cancel".equals(action)) {
                redirect.addFlashAttribute("withdrawalCancelRequestId", id);
                redirect.addFlashAttribute("withdrawalCancelReason", reason);
            }
            if ("reject".equals(action)) {
                redirect.addFlashAttribute("withdrawalRejectRequestId", id);
                redirect.addFlashAttribute("withdrawalRejectReason", reason);
            }
        } catch (org.springframework.dao.DataAccessException ex) {
            redirect.addFlashAttribute("withdrawalError", "A concurrent change or storage issue prevented this action. Refresh before retrying.");
            if ("cancel".equals(action)) {
                redirect.addFlashAttribute("withdrawalCancelRequestId", id);
                redirect.addFlashAttribute("withdrawalCancelReason", reason);
            }
            if ("reject".equals(action)) {
                redirect.addFlashAttribute("withdrawalRejectRequestId", id);
                redirect.addFlashAttribute("withdrawalRejectReason", reason);
            }
        }
        return WORKSPACE_REDIRECT;
    }

    ///---------- Allocate Shares To An Employee ----------
    // Form submit (POST) to /admin/contribution-workspace/shares/allocate
    @PostMapping("/shares/allocate")
    public String allocateShares(
            // Employee that receives the shares
            @RequestParam String employeeId,
            // OLD or NEW share category
            @RequestParam ShareHolding.ShareCategory shareCategory,
            // Number of shares
            @RequestParam Integer quantity,
            // Monthly contribution per share
            @RequestParam BigDecimal monthlyRatePerShare,
            // Optional: admin may enter the share valuation manually
            @RequestParam(required = false) BigDecimal shareValuePerShare,
            // Date from which the allocation counts (yyyy-MM-dd)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveDate,
            // Spring MVC provides this object automatically
            jakarta.servlet.http.HttpServletRequest operationRequest,
            RedirectAttributes redirectAttributes
    ) {

        try {

            // The service checks the rules and saves the allocation
            shareService.allocateShares(employeeId.trim(), shareCategory, quantity, monthlyRatePerShare, shareValuePerShare, effectiveDate);

            // Success message for the next page, it disappears after one redirect
            redirectAttributes.addFlashAttribute("successMessage", "Shares allocated successfully.");

        } catch (IllegalArgumentException | IllegalStateException | org.springframework.web.server.ResponseStatusException exception) {

            // The service refused: show its message on the next page
            redirectAttributes.addFlashAttribute("errorMessage", exception instanceof org.springframework.web.server.ResponseStatusException response ? response.getReason() : exception.getMessage());

        }

        // Open the Shares tab after the redirect
        redirectAttributes.addAttribute("tab", "shares");
        // Keep the same employee selected
        redirectAttributes.addAttribute("employeeId", employeeId);

        // Go back to the workspace page
        preserveOperationContext(operationRequest, redirectAttributes);
        return WORKSPACE_REDIRECT;

    }


    ///---------- Adjust The Share Quantity Of A Holding ----------
    // Form submit (POST) to /admin/contribution-workspace/shares/{holdingId}/adjust
    @PostMapping("/shares/{holdingId}/adjust")
    public String adjustShares(@PathVariable Long holdingId, @RequestParam String employeeId, @RequestParam Integer quantityChange, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveDate, @RequestParam String reason, jakarta.servlet.http.HttpServletRequest operationRequest, RedirectAttributes redirectAttributes) {

        try {

            // quantityChange is the amount to add (positive) or remove (negative)
            verifyHoldingEmployee(holdingId, employeeId);
            shareService.adjustShares(holdingId, quantityChange, effectiveDate, reason);

            // Success message for the next page
            redirectAttributes.addFlashAttribute("successMessage", "Share quantity updated successfully.");

        } catch (IllegalArgumentException | IllegalStateException | org.springframework.web.server.ResponseStatusException exception) {

            // The service refused: show its message on the next page
            redirectAttributes.addFlashAttribute("errorMessage", exception instanceof org.springframework.web.server.ResponseStatusException response ? response.getReason() : exception.getMessage());

        }

        // Open the Shares tab after the redirect
        redirectAttributes.addAttribute("tab", "shares");
        // Keep the same employee and holding selected
        redirectAttributes.addAttribute("employeeId", employeeId);
        redirectAttributes.addAttribute("holdingId", holdingId);

        // Go back to the workspace page
        preserveOperationContext(operationRequest, redirectAttributes);
        return WORKSPACE_REDIRECT;

    }


    ///---------- Change The Monthly Rate Of A Holding ----------
    // Form submit (POST) to /admin/contribution-workspace/shares/{holdingId}/rate
    @PostMapping("/shares/{holdingId}/rate")
    public String changeShareRate(@PathVariable Long holdingId, @RequestParam String employeeId, @RequestParam BigDecimal monthlyRatePerShare, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveDate, @RequestParam String reason, jakarta.servlet.http.HttpServletRequest operationRequest, RedirectAttributes redirectAttributes) {

        try {

            // The new rate counts from the effective date
            verifyHoldingEmployee(holdingId, employeeId);
            shareService.changeMonthlyRate(holdingId, monthlyRatePerShare, effectiveDate, reason);

            // Success message for the next page
            redirectAttributes.addFlashAttribute("successMessage", "Share monthly rate updated successfully.");

        } catch (IllegalArgumentException | IllegalStateException | org.springframework.web.server.ResponseStatusException exception) {

            // The service refused: show its message on the next page
            redirectAttributes.addFlashAttribute("errorMessage", exception instanceof org.springframework.web.server.ResponseStatusException response ? response.getReason() : exception.getMessage());

        } catch (org.springframework.dao.DataIntegrityViolationException exception) {

            redirectAttributes.addFlashAttribute("errorMessage",
                    "The database still prevents multiple rate-history entries for the same effective date. "
                            + "Apply share-rate-same-date-audit.sql to the connected fundaxis database and verify "
                            + "uk_share_rate_history_holding_date is absent before retrying. No rate history was replaced.");

        }

        // Open the Shares tab after the redirect
        redirectAttributes.addAttribute("tab", "shares");
        // Keep the same employee and holding selected
        redirectAttributes.addAttribute("employeeId", employeeId);
        redirectAttributes.addAttribute("holdingId", holdingId);

        // Go back to the workspace page
        preserveOperationContext(operationRequest, redirectAttributes);
        return WORKSPACE_REDIRECT;

    }

    @PostMapping("/shares/{holdingId}/value")
    public String changeShareValue(@PathVariable Long holdingId, @RequestParam String employeeId,
            @RequestParam BigDecimal shareValuePerShare,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveDate,
            @RequestParam String reason, jakarta.servlet.http.HttpServletRequest operationRequest,
            RedirectAttributes redirectAttributes) {
        try {
            verifyHoldingEmployee(holdingId, employeeId);
            shareService.changeShareValue(holdingId, shareValuePerShare, effectiveDate, reason);
            redirectAttributes.addFlashAttribute("successMessage", "Share value updated and recorded in holding history.");
        } catch (IllegalArgumentException | IllegalStateException | org.springframework.web.server.ResponseStatusException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception instanceof org.springframework.web.server.ResponseStatusException response
                    ? response.getReason() : exception.getMessage());
        }
        redirectAttributes.addAttribute("tab", "shares");
        redirectAttributes.addAttribute("employeeId", employeeId);
        redirectAttributes.addAttribute("holdingId", holdingId);
        preserveOperationContext(operationRequest, redirectAttributes);
        return WORKSPACE_REDIRECT;
    }


    ///==================== Contribution Generation Actions ====================///

    ///---------- Generate Monthly Contributions ----------
    // Form submit (POST) to /admin/contribution-workspace/generate
    // No Employee ID = generate for everyone, an Employee ID = generate for that one employee
    @PostMapping("/generate")
    public String generateMonthlyContributions(@RequestParam Integer year, @RequestParam Integer month, @RequestParam(required = false) String employeeId, jakarta.servlet.http.HttpServletRequest operationRequest, RedirectAttributes redirectAttributes) {

        try {

            // Stop with an error if the year or month is outside the allowed range
            validateYearMonth(year, month);

            // First day of the month to generate
            LocalDate targetMonth = LocalDate.of(year, month, 1);

            // No Employee ID typed: generate for ALL employees
            if (employeeId == null || employeeId.isBlank()) {

                // Bulk generation: the response tells how many records were created
                BulkMonthlyGenerationResponse response = generationService.generateAll(targetMonth);

                // Message with the created and skipped counts
                redirectAttributes.addFlashAttribute("successMessage", "Monthly generation completed. " + response.created() + " records created; " + response.skippedExisting() + " existing records skipped.");

                // Employee ID typed: generate for that one employee only
            } else {

                // Single generation: the response carries a ready-made message
                MonthlyContributionGenerationResponse response = generationService.generateOne(employeeId.trim(), targetMonth);

                // Show the service's message
                redirectAttributes.addFlashAttribute("successMessage", response.message());

            }

        } catch (IllegalArgumentException | IllegalStateException exception) {

            // The service refused: show its message on the next page
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());

        }

        // Open the Contributions tab after the redirect
        redirectAttributes.addAttribute("tab", "contributions");
        // Keep the same period selected
        redirectAttributes.addAttribute("year", year);
        redirectAttributes.addAttribute("month", month);

        // Go back to the workspace page
        preserveOperationContext(operationRequest, redirectAttributes);
        return WORKSPACE_REDIRECT;

    }


    ///==================== Payment Settlement & Reversal Actions ====================///

    ///---------- Receive A Payment For A Contribution ----------
    // Form submit (POST) to /admin/contribution-workspace/payments/{contributionId}/receive
    @PostMapping("/payments/{contributionId}/receive")
    public String receivePayment(@PathVariable Long contributionId, @RequestParam BigDecimal amount, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate paymentDate, @RequestParam ContributionPayment.PaymentMethod paymentMethod, @RequestParam(required = false) String referenceNo, @RequestParam(required = false) String notes, jakarta.servlet.http.HttpServletRequest operationRequest, RedirectAttributes redirectAttributes) {

        try {

            // Bundle the form values into one request object
            RecordContributionPaymentRequest request = new RecordContributionPaymentRequest(amount, paymentDate, paymentMethod, referenceNo, notes);

            // The service checks the rules and saves the payment
            paymentService.receive(contributionId, request);

            // Success message for the next page
            redirectAttributes.addFlashAttribute("successMessage", "Contribution payment recorded successfully.");

        } catch (IllegalArgumentException | IllegalStateException exception) {

            // The service refused: show its message on the next page
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());

        }

        // Open the Payments tab after the redirect
        redirectAttributes.addAttribute("tab", "payments");
        // Keep the same contribution selected
        redirectAttributes.addAttribute("contributionId", contributionId);

        // Go back to the workspace page
        preserveOperationContext(operationRequest, redirectAttributes);
        return WORKSPACE_REDIRECT;

    }


    ///---------- Reverse A Payment ----------
    // Form submit (POST) to /admin/contribution-workspace/payments/{contributionId}/{paymentId}/reverse
    // The original payment stays in the history, the reversal is recorded as a new entry
    @PostMapping("/payments/{contributionId}/{paymentId}/reverse")
    public String reversePayment(@PathVariable Long contributionId, @PathVariable Long paymentId, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reversalDate, @RequestParam String reason, jakarta.servlet.http.HttpServletRequest operationRequest, RedirectAttributes redirectAttributes) {

        try {

            // Bundle the form values into one request object
            ContributionPaymentReversalRequest request = new ContributionPaymentReversalRequest(reversalDate, reason);

            // The service checks the rules and records the reversal
            paymentService.reverse(contributionId, paymentId, request);

            // Success message for the next page
            redirectAttributes.addFlashAttribute("successMessage", "Payment reversed successfully. Original transaction history retained.");

        } catch (IllegalArgumentException | IllegalStateException exception) {

            // The service refused: show its message on the next page
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());

        }

        // Open the Payments tab after the redirect
        redirectAttributes.addAttribute("tab", "payments");
        // Keep the same contribution selected
        redirectAttributes.addAttribute("contributionId", contributionId);

        // Go back to the workspace page
        preserveOperationContext(operationRequest, redirectAttributes);
        return WORKSPACE_REDIRECT;

    }


    ///==================== Member Credit Actions ====================///
    // Collection (receive extra money), reversal, and allocation (use the credit on a due)

    ///---------- Receive An Additional Collection ----------
    // Form submit (POST) to /admin/contribution-workspace/credits/receive
    // The money becomes a credit balance for the member
    @PostMapping("/credits/receive")
    public String receiveAdditionalCollection(@RequestParam String employeeId, @RequestParam BigDecimal amount, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate collectionDate, @RequestParam ContributionPayment.PaymentMethod paymentMethod, @RequestParam(required = false) String referenceNo, @RequestParam(required = false) String notes, jakarta.servlet.http.HttpServletRequest operationRequest, RedirectAttributes redirectAttributes) {

        try {

            // The service records the collection and returns the new credit
            MemberCredit credit = memberCreditService.receive(employeeId, amount, collectionDate, paymentMethod, referenceNo, notes);

            // Success message with the new Credit ID
            redirectAttributes.addFlashAttribute("successMessage", "Additional collection recorded successfully. Credit ID: " + credit.getId());

        } catch (Exception exception) {

            // Any failure: show the error message on the next page
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());

        }

        // Open the Payments tab after the redirect
        redirectAttributes.addAttribute("tab", "payments");
        // Keep the same employee selected
        redirectAttributes.addAttribute("employeeId", employeeId);

        // Go back to the workspace page
        preserveOperationContext(operationRequest, redirectAttributes);
        return WORKSPACE_REDIRECT;

    }


    ///---------- Reverse An Additional Collection ----------
    // Form submit (POST) to /admin/contribution-workspace/credits/{creditId}/reverse
    // The original history is kept, the reversal is recorded as a new entry
    @PostMapping("/credits/{creditId}/reverse")
    public String reverseAdditionalCollection(@PathVariable Long creditId, @RequestParam String employeeId, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reversalDate, @RequestParam String reason, jakarta.servlet.http.HttpServletRequest operationRequest, RedirectAttributes redirectAttributes) {

        try {

            // The service checks the rules and records the reversal
            memberCreditService.reverse(employeeId, creditId, reversalDate, reason);

            // Success message for the next page
            redirectAttributes.addFlashAttribute("successMessage", "Additional collection reversed. Original history retained.");

        } catch (Exception exception) {

            // Any failure: show the error message on the next page
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());

        }

        // Open the Payments tab after the redirect
        redirectAttributes.addAttribute("tab", "payments");
        // Keep the same employee selected
        redirectAttributes.addAttribute("employeeId", employeeId);

        // Go back to the workspace page
        preserveOperationContext(operationRequest, redirectAttributes);
        return WORKSPACE_REDIRECT;

    }


    ///---------- Apply A Member Credit To A Contribution ----------
    // Form submit (POST) to /admin/contribution-workspace/credits/{creditId}/apply
    // Uses an existing credit balance to pay (part of) one contribution
    @PostMapping("/credits/{creditId}/apply")
    public String applyMemberCredit(@PathVariable Long creditId, @RequestParam String employeeId, @RequestParam Long contributionId, @RequestParam BigDecimal amount, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate appliedDate, jakarta.servlet.http.HttpServletRequest operationRequest, RedirectAttributes redirectAttributes) {

        try {

            // The service checks the credit balance and applies the amount
            memberCreditService.applyCredit(employeeId, creditId, contributionId, amount, appliedDate);

            // Success message for the next page
            redirectAttributes.addFlashAttribute("successMessage", "Member credit applied successfully.");

        } catch (Exception ex) {

            // Any failure: show the error message on the next page
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());

        }

        // Open the Payments tab after the redirect
        redirectAttributes.addAttribute("tab", "payments");
        // Keep the same employee and contribution selected
        redirectAttributes.addAttribute("employeeId", employeeId);
        redirectAttributes.addAttribute("contributionId", contributionId);

        // Go back to the workspace page
        preserveOperationContext(operationRequest, redirectAttributes);
        return WORKSPACE_REDIRECT;

    }


    ///---------- Reverse A Credit Allocation ----------
    // Form submit (POST) to /admin/contribution-workspace/credits/allocations/{allocationId}/reverse
    // Undoes one "apply credit" and gives the amount back to the member credit balance
    @PostMapping("/credits/allocations/{allocationId}/reverse")
    public String reverseCreditAllocation(@PathVariable Long allocationId, @RequestParam String employeeId, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reversalDate, @RequestParam String reason, jakarta.servlet.http.HttpServletRequest operationRequest, RedirectAttributes redirectAttributes) {

        try {

            // The service reverses the allocation and restores the credit balance
            memberCreditService.reverseAppliedCredit(employeeId, allocationId, reversalDate, reason);

            // Success message for the next page
            redirectAttributes.addFlashAttribute("successMessage", "Credit allocation reversed successfully. Member credit balance restored.");

        } catch (Exception exception) {

            // Any failure: show the error message on the next page
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());

        }

        // Open the Payments tab after the redirect
        redirectAttributes.addAttribute("tab", "payments");
        // Keep the same employee selected
        redirectAttributes.addAttribute("employeeId", employeeId);

        // Go back to the workspace page
        preserveOperationContext(operationRequest, redirectAttributes);
        return WORKSPACE_REDIRECT;

    }


    ///==================== Single Month Excel Export ====================///

    ///---------- Download The Single Month Report As Excel ----------
    // Opens at /admin/contribution-workspace/single-month/export?year=2026&month=10
    // Uses EXACTLY the same monthly calculation as the visible page
    @GetMapping("/single-month/export")
    public ResponseEntity<byte[]> exportSingleMonth(@RequestParam Integer year, @RequestParam Integer month) {

        // Stop with an error if the year or month is outside the allowed range
        validateYearMonth(year, month);

        // First day of the selected month
        LocalDate selectedMonth = LocalDate.of(year, month, 1);

        // A throw-away Model that only collects the results (nothing is shown on a page)
        ExtendedModelMap exportModel = new ExtendedModelMap();

        // Run the same calculation as the page, with no search and no status filter
        processSingleMonthRecords(selectedMonth, "", "ALL", 0, 10, "employeeId", "asc", exportModel);

        // All rows of the month (not only the first 10 of the page)
        @SuppressWarnings("unchecked")
        List<SingleMonthRecord> allRows = (List<SingleMonthRecord>) exportModel.getAttribute("monthlyExportRecords");

        // Totals of the month
        SingleMonthSummary summary = (SingleMonthSummary) exportModel.getAttribute("monthlySummary");

        // Build the .xlsx file as bytes
        byte[] contents = singleMonthExcelService.export(selectedMonth, allRows, summary);

        // File name, for example FundAxis_Single_Month_2026_10.xlsx
        String filename = "FundAxis_Single_Month_" + year + "_" + String.format(java.util.Locale.ROOT, "%02d", month) + ".xlsx";

        // Send the file as a download
        return ResponseEntity.ok()
                // "attachment" makes the browser download it instead of opening it
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                // Tell the browser this is an .xlsx file
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                // File size in bytes
                .contentLength(contents.length)
                // The file itself
                .body(contents);

    }




    @GetMapping("/yearly-records/details")
    public String annualEmployeeDetails(@RequestParam String employeeId, @RequestParam int year, Model model) {
        try {
            model.addAttribute("detail",reportingService.statement(employeeId,"ANNUAL",year,null,null));
        } catch (IllegalArgumentException | com.fundaxis.exception.EmployeeNotFoundException ex) {
            model.addAttribute("detailError",ex.getMessage());
        } catch (IllegalStateException ex) {
            model.addAttribute("detailError","The settlement records require reconciliation.");
        }
        return "admin/contribution-yearly-records :: annualEmployeeDetails";
    }

    @GetMapping("/yearly-records/export")
    public ResponseEntity<byte[]> exportAnnual(@RequestParam int year,
            @RequestParam(defaultValue="") String employeeId, @RequestParam(defaultValue="") String employeeName,
            @RequestParam(defaultValue="ALL") String status) {
        try {
            var report=reportingService.annual(year,employeeId,employeeName,status,0,10,"employeeId","asc");
            byte[] bytes=yearlyRegisterExcelService.export(year,employeeId,employeeName,status,report,
                    reportingService.annualDetails(year,employeeId,employeeName,status));
            return reportDownload(bytes,"FundAxis_Yearly_Contribution_Records_"+year+".xlsx");
        } catch (IllegalStateException exception) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "The report could not be reconciled. Please contact support.", exception);
        }
    }

    @GetMapping("/statement/export")
    public ResponseEntity<byte[]> exportStatement(@RequestParam String employeeId, @RequestParam int year,
            @RequestParam(defaultValue="ANNUAL") String statementType,
            @RequestParam(defaultValue="1") int statementMonth,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate) {
        try {
            var report=reportingService.statement(employeeId,statementType,year,statementMonth,startDate,endDate);
            String safeId=report.employee().getEmployeeId().replaceAll("[^A-Za-z0-9_-]","_");
            String period="ANNUAL".equals(statementType)?String.valueOf(year):"MONTHLY".equals(statementType)?year+"_"+String.format(Locale.ROOT,"%02d",statementMonth):startDate+"_to_"+endDate;
            return reportDownload(monthlyRegisterExcelService.exportStatement(report),
                    "FundAxis_Statement_"+safeId+"_"+period+".xlsx");
        } catch (IllegalStateException exception) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "The report could not be reconciled. Please contact support.", exception);
        }
    }


    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    @ResponseBody
    public ResponseEntity<String> invalidWorkspaceParameter() {
        return ResponseEntity.badRequest().body("Invalid request parameter. Check the reporting year, dates and page settings.");
    }

    private ResponseEntity<byte[]> reportDownload(byte[] bytes,String filename) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+filename+"\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(bytes.length).body(bytes);
    }

    ///==================== Validation Utilities ====================///

    ///---------- Validate Year And Month ----------
    // Throws an error when the year or month is outside the allowed range
    private void validateYearMonth(int year, int month) {

        // Year must be between 1900 and 9999
        if (year < 1900 || year > 9999) {

            // Stop with a clear message
            throw new IllegalArgumentException("Year must be between 1900 and 9999.");

        }

        // Month must be between 1 and 12
        if (month < 1 || month > 12) {

            // Stop with a clear message
            throw new IllegalArgumentException("Month must be between 1 and 12.");

        }

    }
}
