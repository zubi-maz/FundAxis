package com.fundaxis.reporting;

import com.fundaxis.entity.*;
import com.fundaxis.service.ContributionReportingService;
import com.fundaxis.service.excel.MonthlyContributionRegisterExcelService;
import com.fundaxis.service.excel.YearlyContributionRegisterExcelService;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import jakarta.persistence.EntityManager;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Isolated database fixtures only. Never connects to or writes financial production tables. */
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:fundaxis_reporting;MODE=MSSQLServer;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
    "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=update",
    "spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:reporting-schema.sql",
    "spring.jpa.show-sql=false", "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@Transactional
class ContributionReportingIntegrationTest {
    @MockitoBean(name="initializeDefaultAccounts") CommandLineRunner accounts;
    @MockitoBean(name="initializeSystemRoles") CommandLineRunner roles;
    @Autowired EntityManager em;
    @Autowired ContributionReportingService service;
    @Autowired MonthlyContributionRegisterExcelService excel;
    @MockitoSpyBean YearlyContributionRegisterExcelService yearlyExcel;
    @MockitoSpyBean com.fundaxis.repository.ShareTransactionRepository shareTransactionRepository;
    @Autowired com.fundaxis.service.ShareService shares;
    @Autowired com.fundaxis.service.MonthlyContributionGenerationService generation;
    @Autowired com.fundaxis.service.ContributionPaymentService paymentService;
    @Autowired com.fundaxis.service.MemberCreditService memberCredits;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    Employee employee;
    Contribution january;

    @BeforeEach void fixtures() {
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        employee=employee("EMP18","Amina Rahman");
        january=due(employee,1,"100",Contribution.ContributionStatus.PENDING);
        payment(january,"30",LocalDate.of(2026,1,20),false,"JAN-1");
        payment(january,"20",LocalDate.of(2027,1,2),false,"LATE-2027");
        payment(january,"10",LocalDate.of(2026,1,15),true,"REVERSED-1");
        Contribution feb=due(employee,2,"100",Contribution.ContributionStatus.PAID);
        payment(feb,"100",LocalDate.of(2026,2,28),false,"FEB-1");
        due(employee,3,"500",Contribution.ContributionStatus.CANCELED);
        due(employee,12,"100",Contribution.ContributionStatus.PENDING);
        MemberCredit credit=new MemberCredit(); credit.setEmployee(employee);
        credit.setOriginalAmount(new BigDecimal("25"));credit.setAvailableAmount(new BigDecimal("5"));
        credit.setCollectionDate(LocalDate.of(2025,12,31));credit.setPaymentMethod(ContributionPayment.PaymentMethod.CASH);
        em.persist(credit);
        allocation(credit,"20",false); allocation(credit,"5",true);
        employee("EMP19","No Contributions");
        em.flush(); em.clear();
    }
    Employee employee(String id,String name) {
        Employee e=new Employee(); e.setEmployeeId(id);e.setFullName(name);
        e.setEmployeeDesignation(Employee.EmployeeDesignation.EO);e.setEmployeeStatus(Employee.EmployeeStatus.ACTIVE);
        e.setFundJoinDate(LocalDate.of(2025,1,1));em.persist(e);return e;
    }
    Contribution due(Employee e,int month,String amount,Contribution.ContributionStatus status) {
        Contribution c=new Contribution();c.setEmployee(e);c.setContributionMonth(LocalDate.of(2026,month,1));
        c.setContributionAmount(new BigDecimal(amount));c.setContributionStatus(status);em.persist(c);return c;
    }
    void payment(Contribution c,String amount,LocalDate date,boolean reversed,String ref) {
        ContributionPayment p=new ContributionPayment();p.setContribution(c);p.setPaymentAmount(new BigDecimal(amount));
        p.setPaymentDate(date);p.setPaymentMethod(ContributionPayment.PaymentMethod.BANK_TRANSFER);p.setReferenceNo(ref);
        p.setReversed(reversed);if(reversed)p.setReversalDate(LocalDate.of(2027,2,1));em.persist(p);
    }
    void allocation(MemberCredit credit,String amount,boolean reversed) {
        MemberCreditAllocation a=new MemberCreditAllocation();a.setCredit(credit);a.setContribution(january);
        a.setAmount(new BigDecimal(amount));a.setAppliedDate(LocalDate.of(2026,1,20));a.setReversed(reversed);
        if(reversed){a.setReversalDate(LocalDate.of(2027,2,1));a.setReversalReason("Test reversal");}em.persist(a);
    }
    void money(String expected,BigDecimal actual) {assertEquals(0,new BigDecimal(expected).compareTo(actual));}
    ContributionReportingService.AnnualReport annual(String id,String name,String status,int page,int size,String sort,String dir) {
        return service.annual(2026,id,name,status,page,size,sort,dir);
    }
    // Locate financial data by headers/business IDs: presentation banners and SL columns may evolve.
    int exportColumn(org.apache.poi.ss.usermodel.Sheet sheet,String heading) {
        for (var row:sheet) for (var cell:row)
            if (cell.getCellType()==CellType.STRING && heading.equalsIgnoreCase(cell.getStringCellValue().trim())) return cell.getColumnIndex();
        throw new AssertionError("Missing export heading: "+heading);
    }
    org.apache.poi.ss.usermodel.Row exportRow(org.apache.poi.ss.usermodel.Sheet sheet,String text) {
        for (var row:sheet) for (var cell:row)
            if (cell.getCellType()==CellType.STRING && text.equals(cell.getStringCellValue())) return row;
        throw new AssertionError("Missing export row: "+text);
    }
    long exportedDataRows(org.apache.poi.ss.usermodel.Sheet sheet) {
        int id=exportColumn(sheet,"Employee ID"),amount=exportColumn(sheet,"Expected (BDT)");long count=0;
        for (var row:sheet) {
            var key=row.getCell(id);var value=row.getCell(amount);
            if (key!=null && value!=null && key.getCellType()==CellType.STRING && !key.getStringCellValue().isBlank()
                    && !"TOTAL".equals(key.getStringCellValue()) && value.getCellType()==CellType.NUMERIC) count++;
        }
        return count;
    }
    boolean exportContains(org.apache.poi.ss.usermodel.Sheet sheet,String text) {
        for(var row:sheet)for(var cell:row)if(cell.getCellType()==CellType.STRING && cell.getStringCellValue().replaceAll("\\s+"," ").contains(text))return true;
        return false;
    }
    @Test void reconcilesAnnualStatementAndMonthlyWithLatePaymentsReversalsAndCredit() {
        var report=annual("EMP18","","ALL",0,10,"employeeId","asc");
        money("300",report.expected());money("150",report.collected());money("20",report.creditApplied());
        money("130",report.outstanding());money("50",report.rate());assertEquals(1,report.activeContributors());
        var statement=service.statement("EMP18","ANNUAL",2026,null,null);
        money("300",statement.summary().expected());money("150",statement.summary().collected());
        money("130",statement.summary().outstanding());assertEquals(3,statement.paymentCount());assertEquals(1,statement.reversalCount());
        assertEquals(2,statement.pendingCount());assertEquals(12,statement.months().size());
        assertEquals("CANCELED",statement.months().get(2).status());assertEquals("NOT CREATED",statement.months().get(3).status());
        money("300",report.months().stream().map(ContributionReportingService.ReportMonth::expected).reduce(BigDecimal.ZERO,BigDecimal::add));
        money("150",report.months().stream().map(ContributionReportingService.ReportMonth::collected).reduce(BigDecimal.ZERO,BigDecimal::add));
        var month=service.monthly(2026,1);money("50",month.collected());money("20",month.creditApplied());money("30",month.outstanding());
        assertTrue(statement.payments().stream().anyMatch(p -> "LATE-2027".equals(p.reference())));
        assertEquals(statement.payments().size(),statement.payments().stream().map(ContributionReportingService.StatementPayment::paymentId).distinct().count());
        assertEquals(2,statement.allocations().size());assertEquals(1,statement.allocations().stream().filter(ContributionReportingService.StatementCreditAllocation::reversed).count());
        assertEquals("Test reversal",statement.allocations().stream().filter(ContributionReportingService.StatementCreditAllocation::reversed).findFirst().orElseThrow().reversalReason());
    }
    @Test void filtersCombineAndStatusesDistinguishNoRecord() {
        assertEquals(1,annual("EMP18","amina","PARTIAL",0,10,"fullName","desc").page().getTotalElements());
        assertEquals(0,annual("EMP18","wrong","ALL",0,10,"employeeId","asc").page().getTotalElements());
        assertEquals("EMP19",annual("","","NO_RECORDS",0,10,"employeeId","asc").page().getContent().get(0).employeeId());
        assertEquals(0,annual("EMP18","","PAID",0,10,"employeeId","asc").page().getTotalElements());
        assertEquals(0,annual("EMP18","","PENDING",0,10,"employeeId","asc").page().getTotalElements());
        money("0",annual("EMP19","","ALL",0,10,"expected","asc").rate());
        assertEquals(0,annual("EMP%","","ALL",0,10,"employeeId","asc").page().getTotalElements());
    }
    @Test void paginationUsesDatabaseAndSummariesCoverAllPages() throws Exception {
        for(int i=0;i<24;i++) employee("PAGE"+String.format("%02d",i),"Page "+i);
        em.flush();em.clear();
        var first=annual("","","ALL",0,10,"employeeId","asc");
        var second=annual("","","ALL",1,10,"employeeId","asc");
        assertEquals(26,first.page().getTotalElements());assertEquals(10,second.page().getContent().size());
        assertEquals(26,second.allRows().size());money("300",second.expected());
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(yearlyExcel.export(2026,"","","ALL",second,service.annualDetails(2026,"","","ALL"))))) {
            assertEquals(26,exportedDataRows(book.getSheet("Employee Annual Register")));
            assertEquals(312,exportedDataRows(book.getSheet("Monthly Breakdown")));
        }
        assertNotEquals(first.page().getContent().get(0).employeeId(),second.page().getContent().get(0).employeeId());
        for(String field:List.of("employeeId","fullName","expected","collected","outstanding"))
            assertEquals(26,annual("","","ALL",0,10,field,"desc").page().getTotalElements());
    }
    @Test void validatesPeriodsAndPaginationWithoutSilentlyChangingParameters() {
        assertThrows(IllegalArgumentException.class,()->service.statement("EMP18","CUSTOM",2026,LocalDate.of(2026,1,2),LocalDate.of(2026,2,28)));
        assertThrows(IllegalArgumentException.class,()->service.statement("EMP18","CUSTOM",2026,LocalDate.of(2026,3,1),LocalDate.of(2026,2,28)));
        assertThrows(IllegalArgumentException.class,()->service.statement("EMP18","INVALID",2026,null,null));
        assertThrows(IllegalArgumentException.class,()->service.statement("","ANNUAL",2026,null,null));
        assertThrows(com.fundaxis.exception.EmployeeNotFoundException.class,()->service.statement("UNKNOWN","ANNUAL",2026,null,null));
        assertThrows(IllegalArgumentException.class,()->annual("","","ALL",-1,10,"employeeId","asc"));
        assertThrows(IllegalArgumentException.class,()->annual("","","ALL",0,11,"employeeId","asc"));
        assertThrows(IllegalArgumentException.class,()->annual("","","ALL",0,10,"DROP TABLE","asc"));
        assertThrows(IllegalArgumentException.class,()->annual("","","INVALID",0,10,"employeeId","asc"));
    }
    @Test void customBoundariesAndNoHistoryAreExplicit() {
        var r=service.statement("EMP18","CUSTOM",2026,LocalDate.of(2026,1,1),LocalDate.of(2026,2,28));
        assertEquals(2,r.months().size());money("200",r.summary().expected());money("150",r.summary().collected());
        var empty=service.statement("EMP19","ANNUAL",2026,null,null);
        assertEquals(12,empty.months().size());assertTrue(empty.months().stream().allMatch(m -> "NOT CREATED".equals(m.status())));
        money("0",empty.summary().expected());assertEquals(0,empty.paymentCount());
    }
    @Test void monthlyStatementSupportsNumericEmployeeSuffixAndReferenceSettlement() throws Exception {
        Employee e=employee("EMP-36","Statement Reference Member");
        Contribution november=due(e,11,"4500.00",Contribution.ContributionStatus.PAID);
        payment(november,"3499.97",LocalDate.of(2026,11,29),false,"EMP36-CASH");
        MemberCredit credit=new MemberCredit();credit.setEmployee(e);credit.setOriginalAmount(new BigDecimal("1000.03"));
        credit.setAvailableAmount(BigDecimal.ZERO.setScale(2));credit.setStatus(MemberCredit.CreditStatus.FULLY_APPLIED);
        credit.setCollectionDate(LocalDate.of(2026,11,28));credit.setPaymentMethod(ContributionPayment.PaymentMethod.BANK_TRANSFER);
        credit.setReferenceNo("EMP36-CREDIT");em.persist(credit);
        MemberCreditAllocation allocation=new MemberCreditAllocation();allocation.setCredit(credit);allocation.setContribution(november);
        allocation.setAmount(new BigDecimal("1000.03"));allocation.setAppliedDate(LocalDate.of(2026,11,30));em.persist(allocation);
        em.flush();
        long contributionCount=em.createQuery("select count(c) from Contribution c where c.employee.employeeId='EMP-36'",Long.class).getSingleResult();

        var monthly=service.statement("36","MONTHLY",2026,11,null,null);
        money("4500.00",monthly.summary().expected());money("3499.97",monthly.summary().collected());
        money("1000.03",monthly.summary().creditApplied());money("4500.00",monthly.summary().totalSettled());
        money("0.00",monthly.summary().outstanding());assertEquals("PAID",monthly.months().get(0).status());
        assertEquals(1,monthly.paidContributionMonths());assertEquals(0,monthly.pendingCount());
        assertEquals("EMP-36",monthly.employee().getEmployeeId());assertEquals(1,monthly.allocations().size());
        assertEquals(contributionCount,em.createQuery("select count(c) from Contribution c where c.employee.employeeId='EMP-36'",Long.class).getSingleResult());
        var annualReport=service.statement("36","ANNUAL",2026,null,null);
        var custom=service.statement("EMP-36","CUSTOM",2026,LocalDate.of(2026,11,1),LocalDate.of(2026,11,30));
        assertEquals(12,annualReport.months().size());money("4500",annualReport.summary().expected());
        assertEquals(1,custom.months().size());money("4500",custom.summary().expected());
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(excel.exportStatement(monthly)))) {
            assertEquals(3,book.getNumberOfSheets());
            assertEquals(CellType.NUMERIC,book.getSheet("Employee Statement").getRow(15).getCell(1).getCellType());
            assertEquals(4500d,book.getSheet("Employee Statement").getRow(15).getCell(1).getNumericCellValue());
            assertEquals("EMP36-CREDIT",book.getSheet("Credit Allocations").getRow(5).getCell(0).getStringCellValue());
        }
    }
    @Test void pendingOnlyNeverCountsAsCash() {
        Employee e=employee("PENDING1","Pending Only");due(e,1,"75",Contribution.ContributionStatus.PENDING);em.flush();
        var r=annual("PENDING1","","PENDING",0,10,"employeeId","asc");
        money("0",r.collected());money("75",r.outstanding());assertEquals("PENDING",r.allRows().get(0).status());
    }
    @Test void fractionalAmountsReconcileWithoutLosingCents() {
        Employee e=employee("CENTS1","Fractional Amounts");
        Contribution c=due(e,1,"100.03",Contribution.ContributionStatus.PENDING);
        payment(c,"40.01",LocalDate.of(2026,1,31),false,"CENTS");em.flush();
        var annual=annual("CENTS1","","PARTIAL",0,10,"outstanding","asc");
        money("100.03",annual.expected());money("40.01",annual.collected());money("60.02",annual.outstanding());
        money("60.02",annual.months().get(0).outstanding());
        money("60.02",service.statement("CENTS1","ANNUAL",2026,null,null).summary().outstanding());
    }
    @Test void reconciliationRejectsCanceledSettlementsAndOverpayments() {
        Employee e=employee("BAD1","Invalid Settlement");
        var c=due(e,1,"100",Contribution.ContributionStatus.CANCELED);payment(c,"1",LocalDate.of(2026,1,1),false,"BAD");em.flush();
        assertThrows(IllegalStateException.class,()->annual("BAD1","","ALL",0,10,"employeeId","asc"));
    }
    @Test void annualAndStatementWorkbooksContainNumericMoneyAndCompleteFilteredData() throws Exception {
        var r=annual("","","ALL",0,10,"employeeId","asc");
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(yearlyExcel.export(2026,"","","ALL",r,service.annualDetails(2026,"","","ALL"))))) {
            assertEquals(3,book.getNumberOfSheets());
            var register=book.getSheet("Employee Annual Register");assertEquals(CellType.NUMERIC,exportRow(register,"EMP18").getCell(exportColumn(register,"Expected (BDT)")).getCellType());
            assertEquals(300d,exportRow(register,"EMP18").getCell(exportColumn(register,"Expected (BDT)")).getNumericCellValue());
            assertEquals(24,exportedDataRows(book.getSheet("Monthly Breakdown"))); // 24 months, starts at row 5
        }
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(excel.exportStatement(service.statement("EMP18","ANNUAL",2026,null,null))))) {
            assertEquals(3,book.getNumberOfSheets());
            assertEquals(CellType.NUMERIC,book.getSheetAt(0).getRow(15).getCell(1).getCellType());
            assertEquals("A15:G27",book.getSheetAt(0).getCTWorksheet().getAutoFilter().getRef());
            assertEquals(150d,book.getSheet("Payment History").getRow(9).getCell(3).getNumericCellValue());
        }
    }
    @Test void rendersBothTabsModalAndValidationErrors() throws Exception {
        String annual=mvc.perform(get("/admin/contribution-workspace").param("tab","yearly-records").param("year","2026")
                .with(user("report-admin").roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(annual.contains("Yearly Contribution Records"));assertTrue(annual.contains("Amina Rahman"));
        var monthlyResult=mvc.perform(get("/admin/contribution-workspace").param("tab","monthly-records").param("year","2026").param("month","1")
                .with(user("report-admin").roles("ADMIN"))).andExpect(status().isOk()).andReturn();
        var monthlySummary=(com.fundaxis.controller.admin.AdminContributionWorkspaceController.SingleMonthSummary)
                monthlyResult.getModelAndView().getModel().get("monthlySummary");
        money("100",monthlySummary.totalDue());money("50",monthlySummary.totalCash());
        money("20",monthlySummary.totalCredit());money("30",monthlySummary.totalRemaining());
        String statement=mvc.perform(get("/admin/contribution-workspace").param("tab","statement").param("year","2026").param("employeeId","EMP18")
                .with(user("report-admin").roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(statement.contains("EMPLOYEE CONTRIBUTION STATEMENT"));assertTrue(statement.contains("LATE-2027"));
        mvc.perform(get("/admin/contribution-workspace/yearly-records/details").param("year","2026").param("employeeId","EMP18")
                .with(user("report-admin").roles("ADMIN"))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("December")));
        mvc.perform(get("/admin/contribution-workspace").param("tab","statement").param("year","2026").param("employeeId","UNKNOWN")
                .with(user("report-admin").roles("ADMIN"))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Employee not found")));
        mvc.perform(get("/admin/contribution-workspace").param("tab","yearly-records").param("year","1899")
                .with(user("report-admin").roles("ADMIN"))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Invalid reporting year")));
    }

    @Test void memberOperationsResolvesNumericEmployeeSuffixAndShowsAmbiguityInsteadOfGuessing() throws Exception {
        Employee first = employee("EMP-1476", "Numeric Search Member");
        first.setEmployeeStatus(Employee.EmployeeStatus.ACTIVE);
        em.flush();

        String unique = mvc.perform(get("/admin/contribution-workspace").param("tab", "member-operations")
                        .param("employeeId", " 1476 ").param("year", "2026").param("month", "11")
                        .with(user("report-admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(unique.contains("EMP-1476"));
        assertTrue(unique.contains("Expected Monthly Contribution"));
        assertTrue(unique.contains("mfo-employee-status-active"));
        assertTrue(unique.contains("Contribution Status: NOT CREATED"));
        assertTrue(unique.contains("Create Contribution"));
        assertTrue(unique.contains("No Contribution Record Available"));
        assertTrue(unique.contains("data-bs-target=\"#mfo-payment\">Manage Payment</button>"));
        assertTrue(unique.contains("Allocate Shares"));
        assertEquals(1, unique.split("AVAILABLE MEMBER CREDIT", -1).length - 1);
        assertEquals(1, unique.split("Recent Collections", -1).length - 1);
        assertEquals(1, unique.split("View Credit Ledger", -1).length - 1);

        var prefilledForm = mvc.perform(get("/admin/contributions/new").param("employeeId", "EMP-1476")
                        .param("year", "2026").param("month", "11")
                        .with(user("report-admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn();
        assertEquals("EMP-1476", prefilledForm.getModelAndView().getModel().get("prefillEmployeeId"));
        assertEquals(11, prefilledForm.getModelAndView().getModel().get("prefillMonth"));

        Employee inactive = employee("INACTIVE1", "Inactive Search Member");
        inactive.setEmployeeStatus(Employee.EmployeeStatus.INACTIVE);
        em.flush();
        String inactiveView = mvc.perform(get("/admin/contribution-workspace").param("tab", "member-operations")
                        .param("employeeId", "INACTIVE1").param("year", "2026").param("month", "11")
                        .with(user("report-admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(inactiveView.contains("mfo-employee-status-inactive"));
        assertTrue(inactiveView.contains("INACTIVE"));

        employee("BANK1476", "Another Suffix Match");
        em.flush();
        String ambiguous = mvc.perform(get("/admin/contribution-workspace").param("tab", "member-operations")
                        .param("employeeId", "1476").param("year", "2026").param("month", "11")
                        .with(user("report-admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(ambiguous.contains("More than one Employee ID ends with 1476"));
        assertTrue(ambiguous.contains("BANK1476"));
        assertTrue(ambiguous.contains("EMP-1476"));
        assertFalse(ambiguous.contains("aria-label=\"Selected employee\""));

        Contribution settled = due(employee,10,"100",Contribution.ContributionStatus.PAID);
        payment(settled,"100",LocalDate.of(2026,10,30),false,"SETTLED-DRAWER");
        em.flush();
        String paidView = mvc.perform(get("/admin/contribution-workspace").param("tab","member-operations")
                        .param("employeeId","EMP18").param("year","2026").param("month","10")
                        .with(user("report-admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(paidView.contains("No additional payment is available for this obligation."));
        assertTrue(paidView.contains("View Payment History"));
        assertTrue(paidView.contains("Create Contribution"), "Fully paid obligation must not hide the creation action");
        assertEquals(1, java.util.regex.Pattern.compile("Create Contribution").matcher(paidView).results().count());
    }

    @Test void allocationEffectiveDateAppliesToItsWholeMonthAndFutureAllocationStaysScheduled() throws Exception {
        var today = LocalDate.now(java.time.ZoneId.of("Asia/Dhaka"));
        var pastMonth = java.time.YearMonth.from(today).minusMonths(2);
        Employee midMonth = employee("MIDMONTH1", "Mid-month Allocation");
        midMonth.setFundJoinDate(pastMonth.atDay(3));
        Contribution storedDue = new Contribution();
        storedDue.setEmployee(midMonth);
        storedDue.setContributionMonth(pastMonth.atDay(1));
        storedDue.setContributionAmount(new BigDecimal("275.00"));
        storedDue.setContributionStatus(Contribution.ContributionStatus.PENDING);
        em.persist(storedDue);
        em.flush();

        shares.allocateShares(midMonth.getEmployeeId(), ShareHolding.ShareCategory.NEW, 3,
                new BigDecimal("1000.00"), pastMonth.atDay(3));
        money("3000.00", shares.calculateMonthlyContribution(midMonth.getEmployeeId(), pastMonth.atDay(1)).expectedAmount());
        assertEquals(0, new BigDecimal("275.00").compareTo(storedDue.getContributionAmount()));
        var existing = generation.generateOne(midMonth.getEmployeeId(), pastMonth.atDay(1));
        assertFalse(existing.created());
        assertEquals("ALREADY_EXISTS", existing.status());
        money("275.00", existing.expectedAmount());

        Employee scheduled = employee("SCHEDULED1", "Future Allocation");
        var futureMonth = java.time.YearMonth.from(today).plusMonths(1);
        shares.allocateShares(scheduled.getEmployeeId(), ShareHolding.ShareCategory.NEW, 3,
                new BigDecimal("1000.00"), futureMonth.atDay(1));
        var scheduledHolding = shares.getEmployeeShares(scheduled.getEmployeeId()).get(0);
        assertEquals(0, scheduledHolding.getShareQuantity());
        assertEquals(0, shares.getCurrentShareQuantity(scheduledHolding.getId()));
        money("0", shares.calculateMonthlyContribution(scheduled.getEmployeeId(), today).expectedAmount());
        money("3000.00", shares.calculateMonthlyContribution(scheduled.getEmployeeId(), futureMonth.atDay(1)).expectedAmount());
        String scheduledView = mvc.perform(get("/admin/contribution-workspace").param("tab", "member-operations")
                        .param("employeeId", scheduled.getEmployeeId()).param("year", String.valueOf(futureMonth.getYear()))
                        .param("month", String.valueOf(futureMonth.getMonthValue()))
                        .with(user("report-admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(scheduledView.contains("SCHEDULED"));

        Employee current = employee("PRESENT1", "Current Allocation");
        current.setFundJoinDate(today);
        shares.allocateShares(current.getEmployeeId(), ShareHolding.ShareCategory.NEW, 2,
                new BigDecimal("250.00"), today);
        money("500.00", shares.calculateMonthlyContribution(current.getEmployeeId(), today).expectedAmount());
    }

    @Test void finalRateInCalendarMonthIsExpectedAndSameDateCorrectionKeepsAuditRows() throws Exception {
        long contributionCountBefore = em.createQuery("select count(c) from Contribution c", Long.class).getSingleResult();
        long paymentCountBefore = em.createQuery("select count(p) from ContributionPayment p", Long.class).getSingleResult();
        em.createNativeQuery("ALTER TABLE share_holdings ALTER COLUMN id RESTART WITH 20010").executeUpdate();
        Employee member = employee("RATEAUDIT1", "Rate Audit Member");
        shares.allocateShares(member.getEmployeeId(), ShareHolding.ShareCategory.NEW, 3,
                new BigDecimal("1000.00"), LocalDate.of(2026, 11, 4));
        ShareHolding holding = shares.getEmployeeShares(member.getEmployeeId()).get(0);
        assertEquals(20010L, holding.getId());
        assertEquals(3, holding.getShareQuantity());
        money("1000.00", holding.getMonthlyRatePerShare());
        shares.changeMonthlyRate(holding.getId(), new BigDecimal("1500.00"),
                LocalDate.of(2026, 11, 4), "Approved same-day November rate update");

        money("4500.00", shares.calculateMonthlyContribution(member.getEmployeeId(),
                LocalDate.of(2026, 11, 1)).expectedAmount());
        String noDueView = mvc.perform(get("/admin/contribution-workspace").param("tab", "member-operations")
                        .param("employeeId", member.getEmployeeId()).param("year", "2026").param("month", "11")
                        .with(user("report-admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(noDueView.contains("Expected Monthly Contribution"));
        assertTrue(noDueView.contains("4,500.00"));
        assertTrue(noDueView.contains("Recorded Monthly Due"));
        assertTrue(noDueView.contains("Contribution Status: NOT CREATED"));
        assertTrue(noDueView.contains("Create Contribution"));
        assertFalse(noDueView.contains("Add Contribution"));
        assertTrue(noDueView.indexOf("Create Contribution") < noDueView.indexOf("mfo-payment-context"));
        assertTrue(noDueView.contains("/admin/contributions/new?employeeId=RATEAUDIT1&amp;year=2026&amp;month=11"));
        assertEquals(1, java.util.regex.Pattern.compile("Create Contribution").matcher(noDueView).results().count());
        assertFalse(noDueView.contains("No contribution record is available for the selected employee and month."));

        shares.changeMonthlyRate(holding.getId(), new BigDecimal("1600.00"),
                LocalDate.of(2026, 11, 4), "Audited same-day correction");
        money("4800.00", shares.calculateMonthlyContribution(member.getEmployeeId(),
                LocalDate.of(2026, 11, 1)).expectedAmount());
        List<ShareRateHistory> rateHistory = shares.getRateHistoryForEmployee(member.getEmployeeId());
        assertEquals(3, rateHistory.size());
        assertEquals(3, rateHistory.stream().filter(rate -> rate.getEffectiveDate().equals(LocalDate.of(2026,11,4))).count());
        assertTrue(rateHistory.stream().anyMatch(rate -> rate.getMonthlyRatePerShare().compareTo(new BigDecimal("1500.00")) == 0));
        List<ShareRateHistory> sameDate = rateHistory.stream()
                .filter(rate -> rate.getEffectiveDate().equals(LocalDate.of(2026, 11, 4)))
                .sorted(java.util.Comparator.comparing(ShareRateHistory::getId))
                .toList();
        assertEquals(3, sameDate.size());
        assertTrue(sameDate.get(0).getId() < sameDate.get(1).getId());
        assertTrue(sameDate.get(1).getId() < sameDate.get(2).getId());
        money("1000.00", sameDate.get(0).getMonthlyRatePerShare());
        money("1500.00", sameDate.get(1).getMonthlyRatePerShare());
        money("1600.00", sameDate.get(2).getMonthlyRatePerShare());
        assertEquals(3, shares.getEmployeeShares(member.getEmployeeId()).get(0).getShareQuantity());
        assertEquals(contributionCountBefore,
                em.createQuery("select count(c) from Contribution c", Long.class).getSingleResult());
        assertEquals(paymentCountBefore,
                em.createQuery("select count(p) from ContributionPayment p", Long.class).getSingleResult());
    }

    @Test void backdatedQuantityCorrectionRecalculatesEffectiveBalancesWithoutRewritingHistory() {
        Employee member = employee("QTYBACKDATE1", "Backdated Quantity Member");
        shares.allocateShares(member.getEmployeeId(), ShareHolding.ShareCategory.NEW, 10,
                new BigDecimal("10.00"), LocalDate.of(2026, 1, 1));
        ShareHolding holding = shares.getEmployeeShares(member.getEmployeeId()).get(0);
        shares.adjustShares(holding.getId(), 2, LocalDate.of(2026, 3, 1), "Recorded March increase");
        List<ShareTransaction> beforeCorrection = shares.getTransactionHistoryForEmployee(member.getEmployeeId());

        shares.adjustShares(holding.getId(), -3, LocalDate.of(2026, 2, 1), "Audited February correction");

        money("70.00", shares.calculateMonthlyContribution(member.getEmployeeId(), LocalDate.of(2026, 2, 1)).expectedAmount());
        money("90.00", shares.calculateMonthlyContribution(member.getEmployeeId(), LocalDate.of(2026, 3, 1)).expectedAmount());
        assertEquals(9, shares.getCurrentShareQuantity(holding.getId()));
        List<ShareTransaction> history = shares.getTransactionHistoryForEmployee(member.getEmployeeId());
        assertEquals(beforeCorrection.size() + 1, history.size());
        ShareTransaction originalIncrease = history.stream()
                .filter(tx -> tx.getTransactionType() == ShareTransaction.ShareTransactionType.INCREASE).findFirst().orElseThrow();
        assertEquals(12, originalIncrease.getQuantityAfter());
        assertTrue(history.stream().anyMatch(tx -> "Audited February correction".equals(tx.getReason())));
    }

    @Test void futureShareAdjustmentsAndRatesStayScheduledUntilTheirEffectiveDates() {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Dhaka"));
        Employee employee = employee("SCHEDULEDCHANGES", "Scheduled Share Changes");
        employee.setFundJoinDate(today.minusMonths(1));
        shares.allocateShares(employee.getEmployeeId(), ShareHolding.ShareCategory.NEW, 2,
                new BigDecimal("10.00"), today);

        LocalDate quantityDate = today.plusDays(5);
        LocalDate rateDate = today.plusDays(10);
        ShareHolding holding = shares.getEmployeeShares(employee.getEmployeeId()).get(0);
        shares.adjustShares(holding.getId(), 1, quantityDate, "Approved future quantity schedule");
        shares.changeMonthlyRate(holding.getId(), new BigDecimal("20.00"), rateDate,
                "Approved future rate schedule");

        ShareHolding current = shares.getEmployeeShares(employee.getEmployeeId()).get(0);
        assertEquals(2, current.getShareQuantity());
        money("10.00", current.getMonthlyRatePerShare());
        money("60.00", shares.calculateMonthlyContribution(employee.getEmployeeId(), today).expectedAmount());

        LocalDate futureMonth = java.time.YearMonth.from(rateDate).plusMonths(1).atDay(1);
        money("60.00", shares.calculateMonthlyContribution(employee.getEmployeeId(), futureMonth).expectedAmount());
    }

    @Test void bulkContributionPostCreatesOnePendingDueWithoutPaymentAndRejectsRetry() throws Exception {
        Employee batchEmployee = employee("BULKPOST1", "Batch Route Member");
        em.flush();
        em.clear();

        var first = mvc.perform(post("/admin/contributions/bulk")
                        .with(user("report-admin").roles("ADMIN"))
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("contributionMonth", "2026-11-01")
                        .param("entries[0].employeeId", batchEmployee.getEmployeeId())
                        .param("entries[0].contributionAmount", "3000.00"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse();

        assertTrue(first.getRedirectedUrl().contains("tab=member-operations"));
        assertTrue(first.getRedirectedUrl().contains("month=11"));
        assertEquals(1L, em.createQuery("select count(c) from Contribution c "
                        + "where c.employee.employeeId = :employeeId and c.contributionMonth = :month", Long.class)
                .setParameter("employeeId", batchEmployee.getEmployeeId())
                .setParameter("month", LocalDate.of(2026, 11, 1))
                .getSingleResult());
        Contribution saved = em.createQuery("select c from Contribution c "
                        + "where c.employee.employeeId = :employeeId and c.contributionMonth = :month", Contribution.class)
                .setParameter("employeeId", batchEmployee.getEmployeeId())
                .setParameter("month", LocalDate.of(2026, 11, 1))
                .getSingleResult();
        assertEquals(Contribution.ContributionStatus.PENDING, saved.getContributionStatus());
        money("3000.00", saved.getContributionAmount());
        assertEquals(0L, em.createQuery("select count(p) from ContributionPayment p "
                        + "where p.contribution.id = :contributionId", Long.class)
                .setParameter("contributionId", saved.getId()).getSingleResult());

        var nextMonth = mvc.perform(post("/admin/contributions/bulk")
                        .with(user("report-admin").roles("ADMIN"))
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("contributionMonth", "2026-12-01")
                        .param("entries[0].employeeId", batchEmployee.getEmployeeId())
                        .param("entries[0].contributionAmount", "3000.00"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse();
        assertTrue(nextMonth.getRedirectedUrl().contains("month=12"));
        assertEquals(1L, em.createQuery("select count(c) from Contribution c "
                        + "where c.employee.employeeId = :employeeId and c.contributionMonth = :month", Long.class)
                .setParameter("employeeId", batchEmployee.getEmployeeId())
                .setParameter("month", LocalDate.of(2026, 12, 1)).getSingleResult());

        mvc.perform(post("/admin/contributions/bulk")
                        .with(user("report-admin").roles("ADMIN"))
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("contributionMonth", "2026-11-01")
                        .param("entries[0].employeeId", batchEmployee.getEmployeeId())
                        .param("entries[0].contributionAmount", "3000.00"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Contribution already exists")));
        assertEquals(1L, em.createQuery("select count(c) from Contribution c "
                        + "where c.employee.employeeId = :employeeId and c.contributionMonth = :month", Long.class)
                .setParameter("employeeId", batchEmployee.getEmployeeId())
                .setParameter("month", LocalDate.of(2026, 11, 1))
                .getSingleResult());
    }
    @Test void downloadsExportsAndProtectsAllReportingRoutes() throws Exception {
        for(String route:List.of("/admin/contribution-workspace/yearly-records/export","/admin/contribution-workspace/statement/export","/admin/contribution-workspace/yearly-records/details")) {
            mvc.perform(get(route).param("year","2026").param("employeeId","EMP18")).andExpect(status().is3xxRedirection());
            mvc.perform(get(route).param("year","2026").param("employeeId","EMP18").with(user("ordinary").roles("USER"))).andExpect(status().isForbidden());
        }
        for(String route:List.of("/admin/contribution-workspace/yearly-records/export","/admin/contribution-workspace/statement/export")) {
            var response=mvc.perform(get(route).param("year","2026").param("employeeId","EMP18").with(user("report-admin").roles("ADMIN")))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                    .andReturn().getResponse();
            try(var book=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {assertTrue(book.getNumberOfSheets()>=2);}
        }
    }
    @Test void historicalShareChangesApplyForTheirEffectiveMonthAndPreserveStoredObligations() {
        Employee e=employee("HIST1","Historical Member");
        ShareHolding holding=new ShareHolding();holding.setEmployee(e);holding.setShareCategory(ShareHolding.ShareCategory.OLD);
        holding.setShareQuantity(99);holding.setMonthlyRatePerShare(new BigDecimal("99"));holding.setAllocationDate(LocalDate.of(2026,1,15));em.persist(holding);
        for(int i=0;i<2;i++) {
            ShareRateHistory rate=new ShareRateHistory();rate.setShareHolding(holding);
            rate.setEffectiveDate(LocalDate.of(2026,i==0?1:4,i==0?15:1));rate.setMonthlyRatePerShare(new BigDecimal(i==0?"10":"20"));
            rate.setReason("Isolated historical reporting fixture");em.persist(rate);
            ShareTransaction t=new ShareTransaction();t.setShareHolding(holding);
            t.setTransactionType(i==0?ShareTransaction.ShareTransactionType.INITIAL_ALLOCATION:ShareTransaction.ShareTransactionType.INCREASE);
            t.setQuantityBefore(i==0?0:2);t.setQuantityChange(i==0?2:3);t.setQuantityAfter(i==0?2:5);
            t.setEffectiveDate(LocalDate.of(2026,i==0?1:4,15));t.setRatePerShareSnapshot(new BigDecimal(i==0?"10":"20"));em.persist(t);
        }
        em.flush();
        money("20",shares.calculateMonthlyContribution("HIST1",LocalDate.of(2026,1,31)).expectedAmount());
        money("100",shares.calculateMonthlyContribution("HIST1",LocalDate.of(2026,4,30)).expectedAmount());
        money("100",shares.calculateMonthlyContribution("HIST1",LocalDate.of(2026,5,1)).expectedAmount());
        for(int month:List.of(1,4,5))generation.generateOne("HIST1",LocalDate.of(2026,month,20));
        holding.setShareQuantity(999);holding.setMonthlyRatePerShare(new BigDecimal("999"));
        e.setEmployeeStatus(Employee.EmployeeStatus.INACTIVE);em.flush();
        var annual=annual("HIST1","","PENDING",0,10,"employeeId","asc");
        money("220",annual.expected());assertEquals(0,annual.activeContributors());
        money("220",service.statement("HIST1","ANNUAL",2026,null,null).summary().expected());
        assertFalse(generation.generateOne("HIST1",LocalDate.of(2026,1,1)).created());
        e.setFundJoinDate(LocalDate.of(2026,1,15));e.setFundExitDate(LocalDate.of(2026,4,20));em.flush();
        money("20",shares.calculateMonthlyContribution("HIST1",LocalDate.of(2026,1,1)).expectedAmount());
        money("0",shares.calculateMonthlyContribution("HIST1",LocalDate.of(2026,5,1)).expectedAmount());
        money("220",annual("HIST1","","ALL",0,10,"employeeId","asc").expected());
    }
    @Test void actualReversalReconcilesMonthlyAnnualStatementApiAndExcel() throws Exception {
        var original=em.createQuery("select p from ContributionPayment p where p.referenceNo='JAN-1'",ContributionPayment.class).getSingleResult();
        paymentService.reverse(january.getId(),original.getId(),new com.fundaxis.dto.ContributionPaymentReversalRequest(
                LocalDate.now(java.time.ZoneId.of("Asia/Dhaka")),"Isolated reporting reconciliation test"));
        var r=annual("EMP18","","ALL",0,10,"employeeId","asc");
        money("120",r.collected());money("160",r.outstanding());money("20",r.creditApplied());
        money("60",service.monthly(2026,1).outstanding());
        var statement=service.statement("EMP18","ANNUAL",2026,null,null);
        money("120",statement.summary().collected());assertEquals(2,statement.reversalCount());assertEquals(2,statement.paymentCount());
        assertEquals(4,statement.payments().size());
        mvc.perform(get("/api/contributions/reports/member/EMP18").param("fromYear","2026").param("fromMonth","1").param("toYear","2026").param("toMonth","12")
                .with(user("report-admin").roles("ADMIN"))).andExpect(status().isOk()).andExpect(jsonPath("$.collected").value(120.0));
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(yearlyExcel.export(2026,"EMP18","","ALL",r,service.annualDetails(2026,"EMP18","","ALL"))))) {
            assertEquals(120d,exportRow(book.getSheet("Employee Annual Register"),"EMP18").getCell(exportColumn(book.getSheet("Employee Annual Register"),"Cash received (BDT)")).getNumericCellValue());
            assertEquals(160d,exportRow(book.getSheet("Employee Annual Register"),"EMP18").getCell(exportColumn(book.getSheet("Employee Annual Register"),"Outstanding (BDT)")).getNumericCellValue());
        }
    }
    @Test void paymentValidationRejectsNegativeAndExcessAmountsWithoutAddingHistory() {
        long before=em.createQuery("select count(p) from ContributionPayment p",Long.class).getSingleResult();
        for(String amount:List.of("-1","0","0.001","31")) {
            assertThrows(IllegalArgumentException.class,()->paymentService.receive(january.getId(),new com.fundaxis.dto.RecordContributionPaymentRequest(
                    new BigDecimal(amount),LocalDate.of(2026,1,20),ContributionPayment.PaymentMethod.CASH,"INVALID-TEST",null)));
        }
        assertEquals(before,em.createQuery("select count(p) from ContributionPayment p",Long.class).getSingleResult());
    }

    @Test void actualPaymentsPersistTheirOwnCollectionMethodIndependently() {
        var first = paymentService.receive(january.getId(), new com.fundaxis.dto.RecordContributionPaymentRequest(
                new BigDecimal("10.00"), LocalDate.now(java.time.ZoneId.of("Asia/Dhaka")),
                ContributionPayment.PaymentMethod.HO_HAND, "METHOD-HO", null));
        var second = paymentService.receive(january.getId(), new com.fundaxis.dto.RecordContributionPaymentRequest(
                new BigDecimal("5.00"), LocalDate.now(java.time.ZoneId.of("Asia/Dhaka")),
                ContributionPayment.PaymentMethod.BANK_FT, "METHOD-BANK", null));

        var firstPayment = em.createQuery("select p from ContributionPayment p where p.referenceNo = :ref", ContributionPayment.class)
                .setParameter("ref", "METHOD-HO").getSingleResult();
        var secondPayment = em.createQuery("select p from ContributionPayment p where p.referenceNo = :ref", ContributionPayment.class)
                .setParameter("ref", "METHOD-BANK").getSingleResult();
        assertEquals(ContributionPayment.PaymentMethod.HO_HAND, firstPayment.getPaymentMethod());
        assertEquals(ContributionPayment.PaymentMethod.BANK_FT, secondPayment.getPaymentMethod());
        assertNotEquals(firstPayment.getId(), secondPayment.getId());
        assertTrue(first.payments().stream().anyMatch(p -> p.referenceNo().equals("METHOD-HO")
                && p.paymentMethod() == ContributionPayment.PaymentMethod.HO_HAND));
        assertTrue(second.payments().stream().anyMatch(p -> p.referenceNo().equals("METHOD-BANK")
                && p.paymentMethod() == ContributionPayment.PaymentMethod.BANK_FT));
    }

    @Test void batchFormPersistsIntendedMethodWithoutCreatingPayment() throws Exception {
        mvc.perform(get("/admin/contributions/new").with(user("report-admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Not Specified")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"HO_HAND\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"BANK_FT\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"ITD_HAND\"")));

        mvc.perform(post("/admin/contributions/bulk")
                        .param("contributionMonth", "2026-11-01")
                        .param("entries[0].employeeId", "EMP18")
                        .param("entries[0].contributionAmount", "1250.00")
                        .param("entries[0].intendedCollectionMethod", "ITD_HAND")
                        .with(user("report-admin").roles("ADMIN")).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection());

        Contribution created = em.createQuery("select c from Contribution c where c.employee.employeeId='EMP18' and c.contributionMonth=:month", Contribution.class)
                .setParameter("month", LocalDate.of(2026, 11, 1)).getSingleResult();
        assertEquals(ContributionPayment.PaymentMethod.ITD_HAND, created.getIntendedCollectionMethod());
        assertEquals(Contribution.ContributionStatus.PENDING, created.getContributionStatus());
        assertTrue(em.createQuery("select count(p) from ContributionPayment p where p.contribution.id=:id", Long.class)
                .setParameter("id", created.getId()).getSingleResult() == 0L);
    }
    @Test void paymentEligibilityAllowsFormerMembersHistoricalDueAndRejectsPreMembershipPeriod() {
        Employee former=employee("FORMER-PAY","Former Member");
        former.setFundExitDate(LocalDate.of(2026,1,31));
        Contribution formerDue=due(former,1,"100",Contribution.ContributionStatus.PENDING);
        em.flush();
        var recorded=paymentService.receive(formerDue.getId(),new com.fundaxis.dto.RecordContributionPaymentRequest(
                new BigDecimal("100"),LocalDate.now(java.time.ZoneId.of("Asia/Dhaka")),ContributionPayment.PaymentMethod.CASH,"FORMER-HISTORIC",null));
        money("0",recorded.outstandingAmount());

        Employee future=employee("FUTURE-PAY","Future Member");
        future.setFundJoinDate(LocalDate.of(2026,2,1));
        Contribution preMembershipDue=due(future,1,"100",Contribution.ContributionStatus.PENDING);
        em.flush();
        long before=em.createQuery("select count(p) from ContributionPayment p",Long.class).getSingleResult();
        assertThrows(IllegalStateException.class,()->paymentService.receive(preMembershipDue.getId(),
                new com.fundaxis.dto.RecordContributionPaymentRequest(new BigDecimal("100"),
                        LocalDate.now(java.time.ZoneId.of("Asia/Dhaka")),ContributionPayment.PaymentMethod.CASH,"FUTURE-INVALID",null)));
        assertEquals(before,em.createQuery("select count(p) from ContributionPayment p",Long.class).getSingleResult());
    }
    @Test void employeeLookupReturnsOnlyVerifiedBusinessInformationAndRequiresAdmin() throws Exception {
        mvc.perform(get("/admin/employees/lookup").param("employeeId","EMP18").with(user("report-admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.employeeId").value("EMP18"))
                .andExpect(jsonPath("$.fullName").value("Amina Rahman")).andExpect(jsonPath("$.employeeStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.fundJoinDate").value("2025-01-01")).andExpect(jsonPath("$.id").doesNotExist());
        mvc.perform(get("/admin/employees/lookup").param("employeeId","EMP18").with(user("ordinary").roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/employees/lookup").param("employeeId","x".repeat(51)).with(user("report-admin").roles("ADMIN"))).andExpect(status().isBadRequest());
    }
    @Test void malformedReportingParametersReturnSafeValidationErrors() throws Exception {
        for(String route:List.of("/admin/contribution-workspace","/admin/contribution-workspace/statement/export"))
            mvc.perform(get(route).param("tab","yearly-records").param("year","not-a-year").param("employeeId","EMP18")
                    .with(user("report-admin").roles("ADMIN"))).andExpect(status().isBadRequest())
                    .andExpect(content().string("Invalid request parameter. Check the reporting year, dates and page settings."));
    }
    @Test void exportReconciliationErrorsDoNotRevealInternalRecordIdentifiers() throws Exception {
        Employee e=employee("BAD-EXPORT","Invalid Export");var c=due(e,1,"10",Contribution.ContributionStatus.PENDING);
        payment(c,"11",LocalDate.of(2026,1,20),false,"INVALID-SETTLEMENT");em.flush();
        for(String route:List.of("statement","yearly-records"))
            mvc.perform(get("/admin/contribution-workspace/"+route+"/export").param("year","2026").param("employeeId","BAD-EXPORT")
                    .with(user("report-admin").roles("ADMIN"))).andExpect(status().isConflict())
                    .andExpect(content().string("The report could not be reconciled. Please contact support."));
    }
    @Test void yearlyEndpointUsesDedicatedServiceAndHonorsYearAndCombinedFilters() throws Exception {
        org.mockito.Mockito.clearInvocations(yearlyExcel);
        var response = mvc.perform(get("/admin/contribution-workspace/yearly-records/export")
                        .param("year", "2026").param("employeeId", "EMP18")
                        .param("employeeName", "Amina").param("status", "PARTIAL")
                        .with(user("report-admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"FundAxis_Yearly_Contribution_Records_2026.xlsx\""))
                .andReturn().getResponse();
        var captured = org.mockito.ArgumentCaptor.forClass(ContributionReportingService.AnnualReport.class);
        org.mockito.Mockito.verify(yearlyExcel).export(org.mockito.ArgumentMatchers.eq(2026),
                org.mockito.ArgumentMatchers.eq("EMP18"), org.mockito.ArgumentMatchers.eq("Amina"),
                org.mockito.ArgumentMatchers.eq("PARTIAL"), captured.capture(), org.mockito.ArgumentMatchers.anyList());
        var ui = annual("EMP18", "Amina", "PARTIAL", 0, 10, "employeeId", "asc");
        assertEquals(1, captured.getValue().allRows().size());
        money("300", captured.getValue().expected());
        money("150", captured.getValue().collected());
        var file = java.nio.file.Path.of("target/yearly-service-verification/filtered-yearly-2026.xlsx");
        java.nio.file.Files.createDirectories(file.getParent());
        java.nio.file.Files.write(file, response.getContentAsByteArray());
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertEquals(List.of("Annual Summary", "Employee Annual Register", "Monthly Breakdown"),
                    java.util.stream.IntStream.range(0, workbook.getNumberOfSheets()).mapToObj(workbook::getSheetName).toList());
            var summary = workbook.getSheet("Annual Summary");
            assertTrue(exportContains(summary,"2026"));
            assertTrue(exportContains(summary,"Employee ID: EMP18 | Name: Amina | Status: PARTIAL"));
            var register = workbook.getSheet("Employee Annual Register");
            assertNotNull(exportRow(register,"EMP18"));
            assertEquals(1, exportedDataRows(register));
            assertEquals(CellType.NUMERIC, exportRow(register,"EMP18").getCell(exportColumn(register,"Cash received (BDT)")).getCellType());
            assertEquals(ui.collected().doubleValue(), exportRow(register,"EMP18").getCell(exportColumn(register,"Cash received (BDT)")).getNumericCellValue());
            assertEquals(ui.creditApplied().doubleValue(), exportRow(register,"EMP18").getCell(exportColumn(register,"Credit applied (BDT)")).getNumericCellValue());
            assertEquals(ui.outstanding().doubleValue(), exportRow(register,"EMP18").getCell(exportColumn(register,"Outstanding (BDT)")).getNumericCellValue());
            assertEquals(ui.rate().doubleValue(), exportRow(register,"EMP18").getCell(exportColumn(register,"Collection rate (%)")).getNumericCellValue());
            assertEquals(12, exportedDataRows(workbook.getSheet("Monthly Breakdown")));
        }
        var differentYear = mvc.perform(get("/admin/contribution-workspace/yearly-records/export")
                        .param("year", "2025").param("employeeId", "EMP18")
                        .with(user("report-admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"FundAxis_Yearly_Contribution_Records_2025.xlsx\""))
                .andReturn().getResponse();
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(differentYear.getContentAsByteArray()))) {
            assertTrue(exportContains(workbook.getSheetAt(0),"2025"));
            assertEquals(0d, exportRow(workbook.getSheetAt(1),"EMP18").getCell(exportColumn(workbook.getSheetAt(1),"Expected (BDT)")).getNumericCellValue());
        }
        var empty = mvc.perform(get("/admin/contribution-workspace/yearly-records/export")
                        .param("year", "2026").param("employeeName", "No match")
                        .with(user("report-admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse();
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(empty.getContentAsByteArray()))) {
            assertEquals(0, exportedDataRows(workbook.getSheetAt(1)));
            assertEquals(0d, exportRow(workbook.getSheetAt(1),"TOTAL").getCell(exportColumn(workbook.getSheetAt(1),"Expected (BDT)")).getNumericCellValue());
        }
    }

    @Test void existingMonthlyExportsAndEmployeeNavigationStillWork() throws Exception {
        for(String route:List.of("single-month","monthly-register")) {
            var response=mvc.perform(get("/admin/contribution-workspace/"+route+"/export").param("year","2026").param("month","1")
                    .param("rangeFrom","2026-01").param("rangeTo","2026-02").with(user("report-admin").roles("ADMIN")))
                    .andExpect(status().isOk()).andReturn().getResponse();
            try(var book=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {assertTrue(book.getNumberOfSheets()>0);}
        }
        mvc.perform(get("/admin/employees").with(user("report-admin").roles("ADMIN"))).andExpect(status().isOk());
    }

    @Test void snapshotsUseActualRenderedReportingData() throws Exception {
        Contribution september=due(em.find(Employee.class,employee.getId()),9,"1234.56",Contribution.ContributionStatus.PENDING);
        payment(september,"789.10",LocalDate.of(2026,9,30),false,"SEP-PAYMENT");em.flush();
        java.nio.file.Path folder=java.nio.file.Path.of(System.getProperty("fundaxis.ui.snapshotDir","target/ui-audit/after"));
        java.nio.file.Files.createDirectories(folder);
        var requests=java.util.Map.of(
            "annual.html",get("/admin/contribution-workspace").param("tab","yearly-records").param("year","2026"),
            "annual-filtered.html",get("/admin/contribution-workspace").param("tab","yearly-records").param("year","2026").param("employeeId","EMP19"),
            "statement.html",get("/admin/contribution-workspace").param("tab","statement").param("year","2026").param("employeeId","EMP18"),
            "statement-custom.html",get("/admin/contribution-workspace").param("tab","statement").param("year","2026").param("employeeId","EMP18").param("statementType","CUSTOM").param("startDate","2026-01-01").param("endDate","2026-02-28"),
            "statement-empty.html",get("/admin/contribution-workspace").param("tab","statement").param("year","2026"),
            "detail.html",get("/admin/contribution-workspace/yearly-records/details").param("year","2026").param("employeeId","EMP18"),
            "lookup.json",get("/admin/employees/lookup").param("employeeId","EMP18"));
        for(var entry:requests.entrySet()) {
            var response=mvc.perform(entry.getValue().with(user("report-admin").roles("ADMIN")))
                    .andExpect(status().isOk()).andReturn().getResponse();
            java.nio.file.Files.writeString(folder.resolve(entry.getKey()),response.getContentAsString(),java.nio.charset.StandardCharsets.UTF_8);
        }
        for(String kind:List.of("annual","statement")) {
            String route="annual".equals(kind)?"yearly-records":"statement";
            var response=mvc.perform(get("/admin/contribution-workspace/"+route+"/export").param("year","2026")
                    .param("employeeId","EMP18").with(user("report-admin").roles("ADMIN")))
                    .andExpect(status().isOk()).andReturn().getResponse();
            java.nio.file.Files.write(folder.resolve(kind+".xlsx"),response.getContentAsByteArray());
        }
        var report=annual("","","ALL",0,10,"employeeId","asc");
        assertEquals(12,report.months().size());
        assertEquals(12,report.months().stream().map(ContributionReportingService.ReportMonth::month).distinct().count());
        money("1234.56",report.chartMax());money("789.10",report.months().get(8).collected());
        money("0",annual("EMP19","","ALL",0,10,"employeeId","asc").months().get(8).expected());
    }

    @Test void existingFiveTabsStillRenderAndReportingDoesNotMutateTransactions() throws Exception {
        long count=em.createQuery("select count(p) from ContributionPayment p",Long.class).getSingleResult();
        for(String tab:List.of("overview","shares","contributions","payments","monthly-records")) {
            mvc.perform(get("/admin/contribution-workspace").param("tab",tab).param("year","2026").param("month","1")
                    .with(user("report-admin").roles("ADMIN"))).andExpect(status().isOk());
        }
        mvc.perform(get("/admin/share-withdrawal-profit").with(user("report-admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Share Withdrawal &amp; Profit Allocation")));
        annual("","","ALL",0,10,"employeeId","asc");service.statement("EMP18","ANNUAL",2026,null,null);
        assertEquals(count,em.createQuery("select count(p) from ContributionPayment p",Long.class).getSingleResult());
    }
    @Test void unifiedOperationsPreserveAliasesAndSeparateCashFromCredit() throws Exception {
        for (String tab: List.of("member-operations","shares","contributions","payments")) {
            var result=mvc.perform(get("/admin/contribution-workspace").param("tab",tab)
                    .param("year","2026").param("month","1").param("employeeId","EMP18")
                    .with(user("admin").roles("ADMIN")))
                    .andExpect(status().isOk()).andExpect(model().attribute("tab","member-operations"))
                    .andReturn();
            var values=result.getModelAndView().getModel();
            var summary=(com.fundaxis.dto.MemberContributionStatementResponse)values.get("operationSummary");
            money("100",summary.expected());money("50",summary.collected());money("20",summary.creditApplied());money("30",summary.outstanding());
            String html=result.getResponse().getContentAsString();
            assertTrue(html.contains("mfo-allocate"));assertTrue(html.contains("mfo-payment"));
            assertTrue(html.contains("Member Credit"));assertTrue(html.contains("REVERSED"));
            assertTrue(html.contains("Create Contribution"), "Existing monthly obligation must keep its create action");
            assertEquals(5,java.util.regex.Pattern.compile("class=\"cs-tab text-decoration-none(?: is-active)?\"").matcher(html).results().count());
        }
    }

    @Test void unifiedMonthlyModePaginatesAndFiltersWithoutSelectingEmployee() throws Exception {
        for (int i=0;i<12;i++) due(employee("MFO"+i,"Monthly Member "+i),1,"100",Contribution.ContributionStatus.PENDING);
        em.flush();
        var first=mvc.perform(get("/admin/contribution-workspace").param("tab","member-operations")
                .param("year","2026").param("month","1").param("size","10")
                .with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andReturn();
        var page=(org.springframework.data.domain.Page<?>)first.getModelAndView().getModel().get("operationPage");
        assertEquals(13,page.getTotalElements());assertEquals(10,page.getContent().size());
        assertNull(first.getModelAndView().getModel().get("foundEmployee"));
        var second=mvc.perform(get("/admin/contribution-workspace").param("tab","member-operations")
                .param("year","2026").param("month","1").param("page","1").param("employeeName","Monthly Member")
                .with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andReturn();
        page=(org.springframework.data.domain.Page<?>)second.getModelAndView().getModel().get("operationPage");
        assertEquals(12,page.getTotalElements());assertEquals(2,page.getContent().size());
        mvc.perform(get("/admin/contribution-workspace").param("tab","contributions").param("recordEmployeeId","EMP18")
                .param("recordStatus"," paid ").param("year","2026").param("month","1")
                .with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(model().attribute("operationRows",org.hamcrest.Matchers.empty()));
    }

    @Test void unifiedSelectionCannotCarryAnotherMembersHoldingOrPayment() throws Exception {
        mvc.perform(get("/admin/contribution-workspace").param("tab","member-operations")
                .param("year","2026").param("month","1").param("employeeId","EMP19")
                .param("contributionId",january.getId().toString()).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(model().attributeExists("operationError"))
                .andExpect(model().attributeDoesNotExist("paymentSummary"));
        mvc.perform(get("/admin/contribution-workspace").param("tab","member-operations")
                .param("employeeId","UNKNOWN").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(model().attributeExists("operationError"));
        mvc.perform(get("/admin/contribution-workspace").param("tab","member-operations").param("year","1899")
                .with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andExpect(model().attributeExists("operationError"));
        mvc.perform(get("/admin/contribution-workspace").param("tab","member-operations").param("status","INVALID")
                .with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andExpect(model().attributeExists("operationError"));
        mvc.perform(get("/admin/contribution-workspace").param("tab","member-operations")
                .with(user("staff").roles("EMPLOYEE"))).andExpect(status().isForbidden());
        var canceled=due(employee,4,"100",Contribution.ContributionStatus.CANCELED);
        payment(canceled,"5",LocalDate.of(2026,4,2),false,"MFO-INVALID-CANCELED");em.flush();
        mvc.perform(get("/admin/contribution-workspace").param("tab","member-operations").param("year","2026").param("month","4")
                .with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(model().attributeExists("operationError")).andExpect(model().attribute("operationRows",org.hamcrest.Matchers.empty()));
    }

    @Test void unifiedMvcSnapshotsUseRealIsolatedRecords() throws Exception {
        shares.allocateShares("EMP18",ShareHolding.ShareCategory.OLD,4,new BigDecimal("25"),new BigDecimal("500"),LocalDate.of(2025,1,1));
        var holding=em.createQuery("select h from ShareHolding h where h.employee.employeeId='EMP18'",ShareHolding.class).getSingleResult();
        MemberCredit extra=new MemberCredit();extra.setEmployee(employee);extra.setOriginalAmount(new BigDecimal("80"));extra.setAvailableAmount(new BigDecimal("80"));
        extra.setCollectionDate(LocalDate.of(2026,1,15));extra.setPaymentMethod(ContributionPayment.PaymentMethod.BANK_TRANSFER);extra.setReferenceNo("CREDIT-AVAILABLE");em.persist(extra);em.flush();
        var folder=java.nio.file.Path.of("target/member-operations-ui");java.nio.file.Files.createDirectories(folder);
        for (String kind:List.of("member","monthly","holding","credit")) {
            var request=get("/admin/contribution-workspace").param("tab","member-operations").param("year","2026").param("month","1");
            if (!"monthly".equals(kind)) request.param("employeeId","EMP18");
            if ("holding".equals(kind)) request.param("holdingId",holding.getId().toString());
            if ("credit".equals(kind)) request.param("mfoAction","credit");
            var response=mvc.perform(request.with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse();
            java.nio.file.Files.writeString(folder.resolve(kind+".html"),response.getContentAsString(),java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Test void memberOperationsAutomaticallyShowsOnlySelectedEmployeesHoldingHistories() throws Exception {
        Employee newOnly=employee("NEW-ONLY","New Only Member");
        shares.allocateShares(newOnly.getEmployeeId(),ShareHolding.ShareCategory.NEW,1,new BigDecimal("15"),LocalDate.of(2025,1,1));
        em.flush();
        String newOnlyHtml=mvc.perform(get("/admin/contribution-workspace").param("tab","member-operations")
                .param("employeeId",newOnly.getEmployeeId()).param("year","2026").param("month","1")
                .with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(1,java.util.regex.Pattern.compile("class=\"cs-card mfo-portfolio-management\"").matcher(newOnlyHtml).results().count());
        assertEquals(1,java.util.regex.Pattern.compile("class=\"mfo-holding-management\"").matcher(newOnlyHtml).results().count());
        assertTrue(newOnlyHtml.contains("Share Portfolio &amp; Holding Management"));
        assertTrue(newOnlyHtml.contains("NEW</span> Holding Management"));
        assertFalse(newOnlyHtml.contains("OLD</span> Holding Management"));

        shares.allocateShares("EMP18",ShareHolding.ShareCategory.NEW,2,new BigDecimal("25"),LocalDate.of(2025,1,1));
        ShareHolding newHolding=shares.getEmployeeShares("EMP18").get(0);
        shares.adjustShares(newHolding.getId(),1,LocalDate.of(2025,2,1),"Isolated history refresh check");
        shares.changeMonthlyRate(newHolding.getId(),new BigDecimal("30"),LocalDate.of(2025,3,1),"Isolated rate history check");
        shares.allocateShares("EMP18",ShareHolding.ShareCategory.OLD,1,new BigDecimal("10"),LocalDate.of(2025,1,1));
        em.flush();

        var both=mvc.perform(get("/admin/contribution-workspace").param("tab","member-operations")
                .param("employeeId","EMP18").param("year","2026").param("month","1")
                .with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andReturn();
        String bothHtml=both.getResponse().getContentAsString();
        assertEquals(1,java.util.regex.Pattern.compile("class=\"cs-card mfo-portfolio-management\"").matcher(bothHtml).results().count());
        assertEquals(2,java.util.regex.Pattern.compile("class=\"mfo-holding-management\"").matcher(bothHtml).results().count());
        assertTrue(bothHtml.contains("OLD</span> Holding Management"));
        assertTrue(bothHtml.contains("NEW</span> Holding Management"));
        assertTrue(bothHtml.contains("Adjust Quantity"));
        assertTrue(bothHtml.contains("Change Monthly Rate"));
        assertTrue(bothHtml.contains("Change Share Value"));
        assertTrue(bothHtml.contains("holdingId="+newHolding.getId()));
        assertEquals(2,java.util.regex.Pattern.compile("id=\"mfo-quantity-history-[^\"]+\"").matcher(bothHtml).results().count());
        assertEquals(2,java.util.regex.Pattern.compile("id=\"mfo-rate-history-[^\"]+\"").matcher(bothHtml).results().count());
        assertEquals(2,java.util.regex.Pattern.compile("id=\"mfo-valuation-history-[^\"]+\"").matcher(bothHtml).results().count());
        assertFalse(bothHtml.contains(">Holding actions below</a>"));
        var histories=(java.util.Map<Long,java.util.List<ShareTransaction>>)both.getModelAndView().getModel().get("shareHistoryByHolding");
        var rates=(java.util.Map<Long,java.util.List<ShareRateHistory>>)both.getModelAndView().getModel().get("rateHistoryByHolding");
        assertEquals(2,histories.get(newHolding.getId()).size());
        assertEquals(2,rates.get(newHolding.getId()).size());
        Long oldHoldingId=shares.getEmployeeShares("EMP18").stream()
                .filter(h -> h.getShareCategory()==ShareHolding.ShareCategory.OLD).findFirst().orElseThrow().getId();
        var oldAdjust=mvc.perform(get("/admin/contribution-workspace").param("tab","member-operations")
                .param("employeeId","EMP18").param("holdingId",oldHoldingId.toString()).param("mfoAction","adjust")
                .param("year","2026").param("month","1").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn();
        assertEquals(oldHoldingId,((ShareHolding)oldAdjust.getModelAndView().getModel().get("selectedHolding")).getId());
        assertTrue(oldAdjust.getResponse().getContentAsString().contains("id=\"mfo-adjust\""));
        var newValue=mvc.perform(get("/admin/contribution-workspace").param("tab","member-operations")
                .param("employeeId","EMP18").param("holdingId",newHolding.getId().toString()).param("mfoAction","value")
                .param("year","2026").param("month","1").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn();
        assertEquals(newHolding.getId(),((ShareHolding)newValue.getModelAndView().getModel().get("selectedHolding")).getId());
        assertTrue(newValue.getResponse().getContentAsString().contains("name=\"shareValuePerShare\""));
        assertTrue(newValue.getResponse().getContentAsString().contains("NEW"));

        shares.allocateShares("EMP19",ShareHolding.ShareCategory.OLD,1,new BigDecimal("12"),LocalDate.of(2025,1,1));
        em.flush();
        String oldOnly=mvc.perform(get("/admin/contribution-workspace").param("tab","member-operations")
                .param("employeeId","EMP19").param("year","2026").param("month","1")
                .with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(1,java.util.regex.Pattern.compile("class=\"cs-card mfo-portfolio-management\"").matcher(oldOnly).results().count());
        assertEquals(1,java.util.regex.Pattern.compile("class=\"mfo-holding-management\"").matcher(oldOnly).results().count());
        assertTrue(oldOnly.contains("OLD</span> Holding Management"));
        assertFalse(oldOnly.contains("NEW</span> Holding Management"));

        Employee noHolding=employee("NO-SHARES","No Share Holdings");
        em.flush();
        String noShares=mvc.perform(get("/admin/contribution-workspace").param("tab","member-operations")
                .param("employeeId",noHolding.getEmployeeId()).param("year","2026").param("month","1")
                .with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(noShares.contains("No share holdings found for this employee."));
        assertFalse(noShares.contains("mfo-holding-management\""));
        assertFalse(noShares.contains("Isolated history refresh check"));
    }

    @Test void shareValueChangeIsAuditedAndDoesNotChangeMonthlyContributionOrRate() throws Exception {
        Employee valued=employee("VALUE-AUDIT","Valuation Audit Member");
        ShareHolding holding=shares.allocateShares(valued.getEmployeeId(),ShareHolding.ShareCategory.NEW,3,
                new BigDecimal("100.00"),new BigDecimal("439.00"),LocalDate.of(2025,1,1));
        money("300.00",shares.calculateMonthlyContribution(valued.getEmployeeId(),LocalDate.of(2026,1,1)).expectedAmount());

        shares.changeShareValue(holding.getId(),new BigDecimal("500.00"),LocalDate.of(2025,2,1),
                "Approved internal valuation update");

        ShareHolding updated=shares.getEmployeeShares(valued.getEmployeeId()).get(0);
        money("100.00",updated.getMonthlyRatePerShare());
        money("1500.00",updated.getTotalShareValue());
        money("300.00",shares.calculateMonthlyContribution(valued.getEmployeeId(),LocalDate.of(2026,1,1)).expectedAmount());
        var audit=shares.getTransactionHistoryForEmployee(valued.getEmployeeId()).stream()
                .filter(t -> t.getTransactionType()==ShareTransaction.ShareTransactionType.VALUATION_CHANGE).findFirst().orElseThrow();
        money("439.00",audit.getShareValueBefore());money("500.00",audit.getShareValueAfter());
        assertEquals(0,audit.getQuantityChange());assertEquals(3,audit.getQuantityBefore());assertEquals(3,audit.getQuantityAfter());
        assertEquals("Approved internal valuation update",audit.getReason());
        assertEquals(LocalDate.of(2025,2,1),audit.getEffectiveDate());
        assertNotNull(audit.getRecordedBy());assertFalse(audit.getRecordedBy().isBlank());
        var valuationHistory=shares.getTransactionHistoryForEmployee(valued.getEmployeeId()).stream()
                .filter(t -> t.getShareValueAfter()!=null).toList();
        assertEquals(2,valuationHistory.size());

        shares.changeShareValue(holding.getId(),new BigDecimal("450.00"),LocalDate.of(2025,2,15),
                "Approved internal valuation decrease");
        ShareHolding afterDecrease=shares.getEmployeeShares(valued.getEmployeeId()).get(0);
        assertEquals(3,afterDecrease.getShareQuantity());
        money("100.00",afterDecrease.getMonthlyRatePerShare());
        money("1350.00",afterDecrease.getTotalShareValue());
        var decrease=shares.getTransactionHistoryForEmployee(valued.getEmployeeId()).stream()
                .filter(t -> t.getTransactionType()==ShareTransaction.ShareTransactionType.VALUATION_CHANGE)
                .filter(t -> t.getShareValueAfter().compareTo(new BigDecimal("450.00"))==0).findFirst().orElseThrow();
        money("500.00",decrease.getShareValueBefore());
        assertEquals(0,decrease.getQuantityChange());assertEquals(3,decrease.getQuantityBefore());assertEquals(3,decrease.getQuantityAfter());

        mvc.perform(post("/admin/contribution-workspace/shares/"+holding.getId()+"/value")
                .param("employeeId",valued.getEmployeeId()).param("shareValuePerShare","600.00")
                .param("effectiveDate","2025-03-01").param("reason","Controller valuation workflow test")
                .param("workspaceContext","member-operations").param("contextEmployeeId",valued.getEmployeeId())
                .param("contextYear","2026").param("contextMonth","1").param("contextStatus","ALL")
                .with(user("admin").roles("ADMIN")).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("successMessage"));
        money("600.00",shares.getEmployeeShares(valued.getEmployeeId()).get(0).getShareValuePerShare());
        money("300.00",shares.calculateMonthlyContribution(valued.getEmployeeId(),LocalDate.of(2026,1,1)).expectedAmount());
        String valuationView=mvc.perform(get("/admin/contribution-workspace").param("tab","member-operations")
                .param("employeeId",valued.getEmployeeId()).param("year","2026").param("month","1")
                .with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(valuationView.contains("Share Valuation History"));
        assertTrue(valuationView.contains("439.00"));assertTrue(valuationView.contains("500.00"));assertTrue(valuationView.contains("600.00"));
    }

    @Test void futureShareValuationIsAuditedButNotAppliedToCurrentHolding() {
        Employee valued=employee("VALUE-FUTURE","Future Valuation Member");
        ShareHolding holding=shares.allocateShares(valued.getEmployeeId(),ShareHolding.ShareCategory.NEW,3,
                new BigDecimal("100.00"),new BigDecimal("439.00"),LocalDate.of(2025,1,1));
        LocalDate firstFutureDate=LocalDate.now(java.time.ZoneId.of("Asia/Dhaka")).plusDays(10);
        LocalDate secondFutureDate=firstFutureDate.plusDays(1);

        shares.changeShareValue(holding.getId(),new BigDecimal("500.00"),firstFutureDate,"Future value schedule");
        shares.changeShareValue(holding.getId(),new BigDecimal("450.00"),secondFutureDate,"Next future value schedule");

        ShareHolding current=shares.getEmployeeShares(valued.getEmployeeId()).get(0);
        money("439.00",current.getShareValuePerShare());
        assertEquals(3,current.getShareQuantity());money("100.00",current.getMonthlyRatePerShare());
        var futureEvents=shares.getTransactionHistoryForEmployee(valued.getEmployeeId()).stream()
                .filter(t -> t.getTransactionType()==ShareTransaction.ShareTransactionType.VALUATION_CHANGE).toList();
        assertEquals(2,futureEvents.size());
        assertEquals(firstFutureDate,futureEvents.get(0).getEffectiveDate());
        assertEquals(secondFutureDate,futureEvents.get(1).getEffectiveDate());
        money("439.00",futureEvents.get(0).getShareValueBefore());
        money("500.00",futureEvents.get(1).getShareValueBefore());
    }

    @Test void failedValuationAuditInsertDoesNotUpdateHolding() {
        Employee valued=employee("VALUE-ROLLBACK","Rollback Valuation Member");
        ShareHolding holding=shares.allocateShares(valued.getEmployeeId(),ShareHolding.ShareCategory.NEW,3,
                new BigDecimal("100.00"),new BigDecimal("439.00"),LocalDate.of(2025,1,1));
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("simulated valuation audit constraint failure"))
                .when(shareTransactionRepository).save(org.mockito.ArgumentMatchers.any(ShareTransaction.class));

        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                shares.changeShareValue(holding.getId(),new BigDecimal("500.00"),LocalDate.of(2026,1,1),"Rollback check"));

        em.flush();em.clear();
        ShareHolding persisted=em.find(ShareHolding.class,holding.getId());
        money("439.00",persisted.getShareValuePerShare());
        assertEquals(3,persisted.getShareQuantity());money("100.00",persisted.getMonthlyRatePerShare());
        assertTrue(shares.getTransactionHistoryForEmployee(valued.getEmployeeId()).stream()
                .noneMatch(t -> t.getTransactionType()==ShareTransaction.ShareTransactionType.VALUATION_CHANGE));
    }

    @Test void unifiedFormsRetainContextAndUseExistingFinancialServices() throws Exception {
        var allocate=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/admin/contribution-workspace/shares/allocate")
                .param("employeeId","EMP18").param("shareCategory","OLD").param("quantity","4")
                .param("monthlyRatePerShare","25").param("effectiveDate","2025-01-01")
                .param("workspaceContext","member-operations").param("contextEmployeeId","EMP18")
                .param("contextYear","2026").param("contextMonth","1").param("contextStatus","ALL");
        mvc.perform(allocate.with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(allocate.with(user("admin").roles("ADMIN")).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("successMessage"))
                .andExpect(redirectedUrl("/admin/contribution-workspace?tab=member-operations&employeeId=EMP18&year=2026&month=1&status=ALL"));
        mvc.perform(allocate.with(user("admin").roles("ADMIN")).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("errorMessage"));
        var holding=em.createQuery("select h from ShareHolding h where h.employee.employeeId='EMP18'",ShareHolding.class).getSingleResult();
        for(String action:List.of("adjust","rate")) {
            var change=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/admin/contribution-workspace/shares/"+holding.getId()+"/"+action)
                    .param("employeeId","EMP18").param("quantityChange","2").param("monthlyRatePerShare","30")
                    .param("effectiveDate","2025-02-01").param("reason","Isolated form test")
                    .param("workspaceContext","member-operations").param("contextEmployeeId","EMP18")
                    .param("contextYear","2026").param("contextMonth","1");
            mvc.perform(change.with(user("admin").roles("ADMIN")).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                    .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("successMessage"));
        }
        em.flush();em.refresh(holding);assertEquals(6,holding.getShareQuantity());money("30",holding.getMonthlyRatePerShare());
        var receive=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/admin/contribution-workspace/payments/"+january.getId()+"/receive")
                .param("amount","10").param("paymentDate","2026-01-25").param("paymentMethod","CASH").param("referenceNo","MFO-FORM")
                .param("workspaceContext","member-operations").param("contextEmployeeId","EMP18")
                .param("contextYear","2026").param("contextMonth","1");
        mvc.perform(receive.with(user("admin").roles("ADMIN")).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("successMessage"));
        money("60",paymentService.getSummary(january.getId()).collectedAmount());money("20",paymentService.getSummary(january.getId()).outstandingAmount());
        var posted=em.createQuery("select p from ContributionPayment p where p.referenceNo='MFO-FORM'",ContributionPayment.class).getSingleResult();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/admin/contribution-workspace/payments/"+january.getId()+"/"+posted.getId()+"/reverse")
                .param("reversalDate","2026-01-26").param("reason","Isolated reversal")
                .param("workspaceContext","member-operations").param("contextEmployeeId","EMP18")
                .param("contextYear","2026").param("contextMonth","1").with(user("admin").roles("ADMIN"))
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("successMessage"));
        money("50",paymentService.getSummary(january.getId()).collectedAmount());money("30",paymentService.getSummary(january.getId()).outstandingAmount());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/admin/contribution-workspace/payments/"+january.getId()+"/receive").param("amount","10").param("paymentDate","2026-01-25").param("paymentMethod","CASH").param("workspaceContext","member-operations").param("contextYear","2026").param("contextMonth","1").param("contextEmployeeId","EMP19").with(user("admin").roles("ADMIN"))
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isBadRequest());
    }

    @Test void unifiedMemberCreditFormsPreserveSeparateLedgerAndReversalRules() throws Exception {
        java.util.function.Function<String,org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> creditPost = route ->
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/admin/contribution-workspace"+route)
                .param("employeeId","EMP18").param("workspaceContext","member-operations").param("contextEmployeeId","EMP18")
                .param("contextYear","2026").param("contextMonth","1").param("contextStatus","ALL")
                .with(user("admin").roles("ADMIN")).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf());
        mvc.perform(creditPost.apply("/credits/receive").param("amount","12.50").param("collectionDate","2026-01-20")
                .param("paymentMethod","CASH").param("referenceNo","MFO-CREDIT"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("successMessage"));
        var credit=em.createQuery("select c from MemberCredit c where c.referenceNo='MFO-CREDIT'",MemberCredit.class).getSingleResult();
        money("12.50",credit.getAvailableAmount());money("50",paymentService.getSummary(january.getId()).collectedAmount());
        mvc.perform(creditPost.apply("/credits/"+credit.getId()+"/apply").param("contributionId",january.getId().toString())
                .param("amount","5").param("appliedDate","2026-01-27"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("successMessage"));
        em.flush();em.refresh(credit);money("7.50",credit.getAvailableAmount());
        money("50",paymentService.getSummary(january.getId()).collectedAmount());money("25",paymentService.getSummary(january.getId()).outstandingAmount());
        var allocation=em.createQuery("select a from MemberCreditAllocation a where a.credit.id=:credit",MemberCreditAllocation.class)
                .setParameter("credit",credit.getId()).getSingleResult();
        mvc.perform(creditPost.apply("/credits/allocations/"+allocation.getId()+"/reverse").param("reversalDate","2026-01-28").param("reason","Isolated allocation reversal"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("successMessage"));
        em.flush();em.refresh(credit);money("12.50",credit.getAvailableAmount());
        money("30",paymentService.getSummary(january.getId()).outstandingAmount());
        mvc.perform(creditPost.apply("/credits/"+credit.getId()+"/reverse").param("reversalDate","2026-01-29").param("reason","Isolated collection reversal"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("successMessage"));
        em.flush();em.refresh(credit);assertEquals(MemberCredit.CreditStatus.REVERSED,credit.getStatus());
        assertTrue(em.find(MemberCreditAllocation.class,allocation.getId()).isReversed());
        money("50",paymentService.getSummary(january.getId()).collectedAmount());money("30",paymentService.getSummary(january.getId()).outstandingAmount());
    }

    private void assertSingleMonthlyRegister(String html, boolean hasRows) {
        for(String id:List.of("mfo-title","mfo-monthly","mfo-monthly-title","mfo-monthly-description")) {
            assertEquals(1,java.util.regex.Pattern.compile("id=\""+id+"\"").matcher(html).results().count(),"Duplicate rendered component: "+id);
        }
        assertEquals(1,java.util.regex.Pattern.compile("Monthly Contributions &amp; Payments").matcher(html).results().count());
        assertEquals(hasRows?1:0,java.util.regex.Pattern.compile("id=\"mfo-contribution-register\"").matcher(html).results().count());
    }

    @Test void unifiedRefinementRendersOneRegisterAcrossEmployeeFilteredAndEmptyStates() throws Exception {
        shares.allocateShares("EMP18",ShareHolding.ShareCategory.OLD,4,new BigDecimal("25"),new BigDecimal("500"),LocalDate.of(2025,1,1));
        shares.allocateShares("EMP18",ShareHolding.ShareCategory.NEW,2,new BigDecimal("15"),null,LocalDate.of(2025,1,1));
        Long holdingId=em.createQuery("select h.id from ShareHolding h where h.employee.employeeId='EMP18' and h.shareCategory=:category",Long.class)
                .setParameter("category",ShareHolding.ShareCategory.OLD).getSingleResult();
        for(String state:List.of("all","selected","filtered","empty","unknown")) {
            var request=get("/admin/contribution-workspace").param("tab","member-operations").param("year","2026")
                    .param("month","empty".equals(state)?"11":"1").param("contextApplied","1");
            if("selected".equals(state)) request.param("employeeId","EMP18").param("holdingId",holdingId.toString());
            if("filtered".equals(state)) request.param("status","PAID");
            if("unknown".equals(state)) request.param("employeeId","UNKNOWN");
            String html=mvc.perform(request.with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertSingleMonthlyRegister(html,List.of("all","selected").contains(state));
            assertEquals("selected".equals(state),html.contains("class=\"cs-card mfo-member\""));
            if("selected".equals(state)) {
                for(String text:List.of("Share Portfolio","Allocate Shares","Adjust Quantity","Change Monthly Rate","Quantity Transactions","Historical Rates","Share Valuation")) assertTrue(html.contains(text),text);
                assertTrue(html.contains("/shares/"+holdingId+"/adjust"));
                assertTrue(html.contains("/shares/"+holdingId+"/rate"));
                mvc.perform(get("/admin/contributions/"+january.getId()).with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
                mvc.perform(get("/admin/contributions/"+january.getId()+"/edit").with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
            }
            if(!"unknown".equals(state)) assertTrue(html.contains("id=\"mfo-context-feedback\""));
            if(List.of("empty","filtered").contains(state)) {
                var folder=java.nio.file.Path.of("target/member-operations-ui");java.nio.file.Files.createDirectories(folder);
                java.nio.file.Files.writeString(folder.resolve(state+".html"),html,java.nio.charset.StandardCharsets.UTF_8);
            }
        }
    }

    @Test void unifiedRefinementPaginates205MembersAndRetainsAllFilters() throws Exception {
        for(int i=0;i<205;i++) due(employee(String.format("SCALE%03d",i),"Scale Test Member "+i),1,"100",Contribution.ContributionStatus.PENDING);
        em.flush();
        for(int pageNumber:List.of(0,10,20)) {
            var response=mvc.perform(get("/admin/contribution-workspace").param("tab","member-operations")
                    .param("year","2026").param("month","1").param("employeeName","Scale Test")
                    .param("status","PENDING").param("size","10").param("page",String.valueOf(pageNumber))
                    .param("contextApplied","1").with(user("admin").roles("ADMIN")))
                    .andExpect(status().isOk()).andReturn();
            var page=(org.springframework.data.domain.Page<?>)response.getModelAndView().getModel().get("operationPage");
            assertEquals(205,page.getTotalElements());assertEquals(21,page.getTotalPages());
            assertEquals(pageNumber==20?5:10,page.getNumberOfElements());
            String html=response.getResponse().getContentAsString();assertSingleMonthlyRegister(html,true);
            assertTrue(html.contains("employeeName=Scale%20Test"));assertTrue(html.contains("status=PENDING"));
            assertTrue(html.contains("size=10"));assertTrue(html.contains("year=2026"));assertTrue(html.contains("month=1"));
            assertTrue(html.contains("aria-current=\"page\""));
            if(pageNumber!=10) {
                var folder=java.nio.file.Path.of("target/member-operations-ui");java.nio.file.Files.createDirectories(folder);
                java.nio.file.Files.writeString(folder.resolve(pageNumber==0?"paged.html":"last-page.html"),html,java.nio.charset.StandardCharsets.UTF_8);
            }
        }
    }

    @Test void unifiedPaymentDrawerShowsAuthoritativeBalancesAndPostingRechecksThem() throws Exception {
        var page=mvc.perform(get("/admin/contribution-workspace").param("tab","member-operations").param("employeeId","EMP18")
                .param("year","2026").param("month","1").with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andReturn();
        money("20",(BigDecimal)page.getModelAndView().getModel().get("paymentAppliedCredit"));
        money("5",(BigDecimal)page.getModelAndView().getModel().get("creditBalance"));
        String html=page.getResponse().getContentAsString();
        for(String id:List.of("mfo-payment-due","mfo-payment-cash","mfo-payment-credit","mfo-payment-outstanding","mfo-collection")) assertTrue(html.contains("id=\""+id+"\""),id);
        var amountField=java.util.regex.Pattern.compile("(?s)<input(?=[^>]*id=\"payAmount\")(?=[^>]*value=\"([^\"]+)\")[^>]*>").matcher(html);
        assertTrue(amountField.find());money("30",new BigDecimal(amountField.group(1)));
        assertFalse(html.contains("id=\"mfo-pay-full\""));
        assertTrue(html.contains("max=\"30.00\""));
        assertTrue(html.contains("value=\""+LocalDate.now(java.time.ZoneId.of("Asia/Dhaka"))+"\""));
        java.util.function.BiFunction<String,String,org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> paymentPost=(amount,date)->
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/admin/contribution-workspace/payments/"+january.getId()+"/receive")
                .param("amount",amount).param("paymentDate",date).param("paymentMethod","CASH")
                .param("workspaceContext","member-operations").param("contextEmployeeId","EMP18").param("contextYear","2026").param("contextMonth","1")
                .with(user("admin").roles("ADMIN")).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf());
        for(String amount:List.of("0","31")) mvc.perform(paymentPost.apply(amount,"2026-01-25"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("errorMessage"));
        mvc.perform(paymentPost.apply("1",LocalDate.now(java.time.ZoneId.of("Asia/Dhaka")).plusDays(1).toString()))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("errorMessage"));
        money("50",paymentService.getSummary(january.getId()).collectedAmount());
        mvc.perform(paymentPost.apply("5","2026-01-25")).andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("successMessage"));
        money("25",paymentService.getSummary(january.getId()).outstandingAmount());
        // An autofilled amount from the old 30.00 view must not overcollect after another receipt.
        mvc.perform(paymentPost.apply("30","2026-01-25")).andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("errorMessage"));
        mvc.perform(paymentPost.apply("25","2026-01-25")).andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("successMessage"));
        var summary=paymentService.getSummary(january.getId());money("80",summary.collectedAmount());money("0",summary.outstandingAmount());assertEquals(Contribution.ContributionStatus.PAID,summary.status());
    }

    @Test void unifiedCreditOverviewUsesEligibleBalancesAndLimitsRecentCollections() throws Exception {
        for(var state:List.of(MemberCredit.CreditStatus.PARTIALLY_APPLIED,MemberCredit.CreditStatus.FULLY_APPLIED,MemberCredit.CreditStatus.REVERSED)) {
            var c=new MemberCredit();c.setEmployee(employee);c.setOriginalAmount(new BigDecimal("50"));c.setAvailableAmount(new BigDecimal("20"));
            c.setCollectionDate(LocalDate.of(2026,1,10));c.setPaymentMethod(ContributionPayment.PaymentMethod.CASH);c.setStatus(state);em.persist(c);
        }
        em.flush();money("25",memberCredits.getAvailableBalance("EMP18"));money("0",memberCredits.getAvailableBalance("EMP19"));
        assertEquals(3,memberCredits.getRecentCollections("EMP18").size());
        var fullOutstandingCredit=new MemberCredit();fullOutstandingCredit.setEmployee(employee);
        fullOutstandingCredit.setOriginalAmount(new BigDecimal("40"));fullOutstandingCredit.setAvailableAmount(new BigDecimal("40"));
        fullOutstandingCredit.setCollectionDate(LocalDate.of(2026,1,5));fullOutstandingCredit.setPaymentMethod(ContributionPayment.PaymentMethod.CASH);
        fullOutstandingCredit.setStatus(MemberCredit.CreditStatus.UNALLOCATED);em.persist(fullOutstandingCredit);em.flush();
        var page=mvc.perform(get("/admin/contribution-workspace").param("tab","member-operations").param("employeeId","EMP18")
                .param("year","2026").param("month","1").param("mfoAction","credit").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn();
        String html=page.getResponse().getContentAsString();
        assertTrue(html.contains("mfo-credit-overview"));assertTrue(html.contains("data-mfo-bound-form=\"apply-credit\""));
        assertTrue(html.contains("Available credit: BDT 40.00"));assertTrue(html.contains("Outstanding contribution: BDT 30.00"));
        assertTrue(html.contains("Maximum applicable: BDT 30.00"));assertTrue(html.contains("data-full-outstanding=\"30.00\""));
        assertTrue(html.contains("Apply Full Outstanding"));
        assertTrue(html.contains("mfo-credit-allocation-history"));assertTrue(html.contains("REVERSED-1"));
        MemberCredit existing=em.createQuery("select c from MemberCredit c where c.employee.employeeId='EMP18' and c.originalAmount=25",MemberCredit.class).getSingleResult();
        int before=memberCredits.getAllocationHistory("EMP18").size();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/admin/contribution-workspace/credits/"+existing.getId()+"/apply")
                .param("employeeId","EMP18").param("contributionId",january.getId().toString()).param("amount","6").param("appliedDate","2026-01-25")
                .with(user("admin").roles("ADMIN")).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("errorMessage"));
        assertEquals(before,memberCredits.getAllocationHistory("EMP18").size());money("30",paymentService.getSummary(january.getId()).outstandingAmount());
    }

    private org.springframework.transaction.support.TransactionTemplate isolatedPaymentTransaction() {
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);return tx;
    }
    private Long committedPaymentFixture(String businessId) {
        return isolatedPaymentTransaction().execute(status->{
            var member=employee(businessId,"Isolated Payment Member");
            var contribution=due(member,1,"100",Contribution.ContributionStatus.PENDING);em.flush();return contribution.getId();
        });
    }
    private void deleteCommittedPaymentFixture(Long dueId,String businessId) {
        isolatedPaymentTransaction().executeWithoutResult(status->{
            em.createQuery("delete from ContributionPayment p where p.contribution.id=:id").setParameter("id",dueId).executeUpdate();
            em.createQuery("delete from Contribution c where c.id=:id").setParameter("id",dueId).executeUpdate();
            em.createQuery("delete from Employee e where e.employeeId=:member").setParameter("member",businessId).executeUpdate();
        });
    }
    @Test void paymentConcurrentReceiptsCannotOvercollect() throws Exception {
        Long id=committedPaymentFixture("PAY-CONCURRENT-UI");
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var ready=new java.util.concurrent.CountDownLatch(2);var start=new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Boolean> attempt=()->{
                ready.countDown();if(!start.await(30,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Test start timeout");
                try { isolatedPaymentTransaction().execute(status->paymentService.receive(id,new com.fundaxis.dto.RecordContributionPaymentRequest(new BigDecimal("80"),LocalDate.of(2026,1,25),ContributionPayment.PaymentMethod.CASH,"CONCURRENT-UI",null)));return true; }
                catch(IllegalArgumentException|IllegalStateException expected) {return false;}
            };
            var first=pool.submit(attempt);var second=pool.submit(attempt);assertTrue(ready.await(30,java.util.concurrent.TimeUnit.SECONDS));start.countDown();
            assertEquals(1,(first.get(30,java.util.concurrent.TimeUnit.SECONDS)?1:0)+(second.get(30,java.util.concurrent.TimeUnit.SECONDS)?1:0));
            var after=isolatedPaymentTransaction().execute(status->paymentService.getSummary(id));money("80",after.collectedAmount());money("20",after.outstandingAmount());
            var settled=isolatedPaymentTransaction().execute(status->paymentService.receive(id,new com.fundaxis.dto.RecordContributionPaymentRequest(new BigDecimal("20"),LocalDate.of(2026,1,25),ContributionPayment.PaymentMethod.CASH,"CONCURRENT-REMAINDER",null)));
            assertEquals(Contribution.ContributionStatus.PAID,settled.status());money("0",settled.outstandingAmount());
        } finally {start.countDown();pool.shutdownNow();deleteCommittedPaymentFixture(id,"PAY-CONCURRENT-UI");}
    }
    @Test void paymentRejectsCanceledEligibilityEvenWhenContextWasPreloaded() {
        Long id=committedPaymentFixture("PAY-CANCELED-UI");
        try {
            // The payment service's expected business rejection marks its joining transaction
            // rollback-only. Assert both the service exception and that transaction rollback.
            assertThrows(org.springframework.transaction.UnexpectedRollbackException.class,
                    () -> isolatedPaymentTransaction().executeWithoutResult(status->{
                        assertEquals(Contribution.ContributionStatus.PENDING,em.find(Contribution.class,id).getContributionStatus());
                        isolatedPaymentTransaction().executeWithoutResult(cancel->{em.find(Contribution.class,id).setContributionStatus(Contribution.ContributionStatus.CANCELED);em.flush();});
                        assertThrows(IllegalStateException.class,()->paymentService.receive(id,new com.fundaxis.dto.RecordContributionPaymentRequest(new BigDecimal("10"),LocalDate.of(2026,1,25),ContributionPayment.PaymentMethod.CASH,"STALE-UI",null)));
                    }));
        } finally {deleteCommittedPaymentFixture(id,"PAY-CANCELED-UI");}
    }

}
