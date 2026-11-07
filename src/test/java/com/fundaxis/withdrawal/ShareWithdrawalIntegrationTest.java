package com.fundaxis.withdrawal;

import com.fundaxis.entity.*;
import com.fundaxis.repository.*;
import com.fundaxis.service.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import org.springframework.web.context.WebApplicationContext;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Safe isolated fixtures only. Approval/posting policy gates are enabled in tests, never in production defaults. */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:withdrawal_test;MODE=LEGACY;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=update","spring.sql.init.mode=always","spring.sql.init.schema-locations=classpath*:reporting-schema.sql",
    "fundaxis.withdrawals.enabled=true","fundaxis.withdrawals.approval-enabled=true","fundaxis.withdrawals.posting-enabled=true"})
@Transactional
@WithMockUser(username="maker",roles="ADMIN")
class ShareWithdrawalIntegrationTest {
    @MockitoBean(name="initializeDefaultAccounts") CommandLineRunner accounts;
    @MockitoBean(name="initializeSystemRoles") CommandLineRunner roles;
    @Autowired EntityManager em;
    @Autowired ShareWithdrawalService service;
    @Autowired ShareWithdrawalRepository withdrawals;
    @Autowired ShareHoldingRepository holdings;
    @Autowired ShareService shares;
    @Autowired ContributionReportingService reporting;
    @Autowired MonthlyContributionGenerationService generation;
    @Autowired PlatformTransactionManager transactions;
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    Long oldId;
    final String businessId="WD001";
    LocalDate today() { return LocalDate.now(ZoneId.of("Asia/Dhaka")); }
    void resetMaker() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("maker","unused",List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
    void tx(Runnable body) { new TransactionTemplate(transactions).executeWithoutResult(s -> body.run()); }
    void as(String actor,Runnable body) {
        var previous=SecurityContextHolder.getContext();
        var security=SecurityContextHolder.createEmptyContext();
        var authorities=new ArrayList<SimpleGrantedAuthority>();
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        if ("checker".equals(actor)) authorities.add(new SimpleGrantedAuthority("ROLE_WITHDRAWAL_CHECKER"));
        security.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor,"unused",authorities));
        SecurityContextHolder.setContext(security);
        try { body.run(); } finally { SecurityContextHolder.setContext(previous); }
    }
    void asChecker(String actor,Runnable body) {
        var previous=SecurityContextHolder.getContext();
        var security=SecurityContextHolder.createEmptyContext();
        security.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor,"unused",List.of(
                new SimpleGrantedAuthority("ROLE_ADMIN"),new SimpleGrantedAuthority("ROLE_WITHDRAWAL_CHECKER"))));
        SecurityContextHolder.setContext(security);
        try { body.run(); } finally { SecurityContextHolder.setContext(previous); }
    }
    @BeforeEach void setup() {
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        tx(() -> {
            Employee e=new Employee();e.setEmployeeId(businessId);e.setFullName("Withdrawal Test Member");
            e.setEmployeeDesignation(Employee.EmployeeDesignation.EO);e.setEmployeeStatus(Employee.EmployeeStatus.ACTIVE);
            e.setFundJoinDate(today().minusYears(1));em.persist(e);em.flush();
            oldId=shares.allocateShares(businessId,ShareHolding.ShareCategory.OLD,10,new BigDecimal("10.00"),new BigDecimal("500.00"),today().minusMonths(3).withDayOfMonth(1)).getId();
            shares.allocateShares(businessId,ShareHolding.ShareCategory.NEW,5,new BigDecimal("20.00"),null,today().minusMonths(3).withDayOfMonth(1));
        });
    }
    @AfterEach void cleanConcurrentFixtures() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) tx(() -> {
            for(String entity:List.of("ShareWithdrawal","ShareTransaction","ShareRateHistory","ShareHolding","Employee")) em.createQuery("delete from "+entity).executeUpdate();
        });
    }
    Long request(int quantity) { return service.request(businessId,ShareHolding.ShareCategory.OLD,quantity,"Isolated request",UUID.randomUUID().toString()); }
    void approve(Long id) { as("checker",() -> service.approve(id,service.details(id).version())); }
    void money(String expected,BigDecimal actual) { assertEquals(0,new BigDecimal(expected).compareTo(actual)); }

    @Test void employeeHoldingsRequestReservationAndRetryAreAccurate() {
        var portfolio=service.portfolio(businessId);assertEquals(2,portfolio.holdings().size());
        assertTrue(portfolio.holdings().stream().anyMatch(h -> h.category()==ShareHolding.ShareCategory.NEW && h.quantity()==5 && h.recordedValue()==null));
        String token=UUID.randomUUID().toString();
        Long id=service.request(businessId,ShareHolding.ShareCategory.OLD,7,"Request reason",token);
        assertEquals(id,service.request(businessId,ShareHolding.ShareCategory.OLD,7,"Request reason",token));
        assertEquals(1,withdrawals.count());assertEquals(10,holdings.findById(oldId).orElseThrow().getShareQuantity());
        assertEquals(7,withdrawals.reserved(oldId,-1L));
        var view=service.details(id);assertEquals("maker",view.requestedBy());assertNull(view.net());assertNull(view.gross());assertNull(view.postedAt());
        assertEquals(3,view.unreserved());assertEquals(ShareWithdrawal.WithdrawalStatus.PENDING,view.status());
        assertThrows(IllegalArgumentException.class,()->service.request(businessId,ShareHolding.ShareCategory.OLD,6,"Request reason",token));
    }
    @Test void memberOperationsEmbedsWithdrawalCardAndCarriesEffectiveDateIntoRequest() throws Exception {
        var page=mvc.perform(get("/admin/contribution-workspace").param("tab","member-operations")
                .param("employeeId",businessId).param("year",String.valueOf(today().getYear()))
                .param("month",String.valueOf(today().getMonthValue())).with(user("maker").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn();
        assertTrue(Boolean.TRUE.equals(page.getModelAndView().getModel().get("withdrawalEnabled")),
                String.valueOf(page.getModelAndView().getModel().get("withdrawalError")));
        String html=page.getResponse().getContentAsString();
        assertTrue(html.contains("id=\"mfo-withdrawal\""));assertTrue(html.contains("Request Withdrawal"));
        assertTrue(html.contains("Available OLD Shares"));assertTrue(html.contains("Available NEW Shares"));
        assertTrue(html.contains("Reserved Shares"));assertTrue(html.contains("Pending Requests"));
        assertTrue(html.contains("id=\"mfo-withdrawal-request\""));
        assertTrue(html.contains("id=\"mfo-withdrawal-valuation-modal\""));
        assertTrue(html.contains("server-calculated, non-persistent preview"));
        assertTrue(html.contains("id=\"mfo-cancel-withdrawal-modal\""));
        assertTrue(html.contains("id=\"mfo-withdrawal-transition-modal\""));
        assertTrue(html.contains("id=\"mfo-withdrawal-reject-modal\""));
        assertTrue(html.contains("Cancel Withdrawal Request"));
        assertTrue(html.contains("Cancellation Reason"));
        assertTrue(html.contains("Cancelling this pending request will release its reserved shares."));
        assertFalse(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/static/js/member-fund-operations.js"))
                .contains("prompt('Enter the cancellation reason"), "Cancellation must not use the browser-native prompt.");
        String withdrawalJs=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/static/js/member-fund-operations.js"));
        assertFalse(withdrawalJs.contains("window.prompt("), "Withdrawal reasons must use accessible in-page modals.");
        assertFalse(withdrawalJs.contains("window.confirm("), "Withdrawal actions must use Bootstrap confirmation modals.");
        assertTrue(html.contains("name=\"_csrf\""), "Rendered withdrawal form must include Spring Security's CSRF parameter.");
        String token=UUID.randomUUID().toString();LocalDate effective=today().plusDays(2);
        mvc.perform(withdrawalPost(token,effective).with(user("maker").roles("ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("security token")));
        assertEquals(0,withdrawals.count(), "Missing CSRF token must not create a request.");
        mvc.perform(withdrawalPost(token,effective).param("_csrf","invalid-token").with(user("maker").roles("ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("security token")));
        assertEquals(0,withdrawals.count(), "An invalid CSRF token must not create a request.");
        mvc.perform(withdrawalPost(token,effective).with(csrf()).with(user("viewer").roles("USER")))
                .andExpect(status().isForbidden());
        assertEquals(0,withdrawals.count(), "Non-admin users must not create a request.");
        mvc.perform(withdrawalPost(token,effective).with(csrf()).with(user("maker").roles("ADMIN")))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("withdrawalSuccess"));
        mvc.perform(withdrawalPost(token,effective).with(csrf()).with(user("maker").roles("ADMIN")))
                .andExpect(status().is3xxRedirection());
        assertEquals(1,withdrawals.count(), "Retrying the same form token must not duplicate the request.");
        var saved=withdrawals.findAll().get(0);assertEquals(effective,saved.getEffectiveDate());
        assertEquals(ShareWithdrawal.WithdrawalStatus.PENDING,saved.getStatus());
        Long newHoldingId=holdings.findByEmployeeEmployeeIdAndShareCategory(businessId,ShareHolding.ShareCategory.NEW).orElseThrow().getId();
        assertEquals(5,holdings.findById(newHoldingId).orElseThrow().getShareQuantity(), "Request creation must not post share quantity.");
        assertEquals(1,withdrawals.reserved(newHoldingId,-1L));
        var refreshedPage=mvc.perform(get("/admin/contribution-workspace").param("tab","member-operations")
                        .param("employeeId",businessId).param("year",String.valueOf(today().getYear()))
                        .param("month",String.valueOf(today().getMonthValue())).with(user("maker").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn();
        assertTrue(refreshedPage.getResponse().getContentAsString().contains("NEW — 4 available"),
                "The refreshed workspace must subtract the pending reservation from available shares.");
        assertTrue(refreshedPage.getResponse().getContentAsString().contains("View Details"));
        assertTrue(refreshedPage.getResponse().getContentAsString().contains("Financial Preview"));
        assertTrue(refreshedPage.getResponse().getContentAsString().contains("Requested by"));
        assertTrue(refreshedPage.getResponse().getContentAsString().contains("maker"));
        assertTrue(refreshedPage.getResponse().getContentAsString().contains("Checker authority required"));
        assertTrue(refreshedPage.getResponse().getContentAsString().contains("/withdrawals/"+saved.getId()+"/cancel"),
                "The pending request creator can cancel their request.");
        assertFalse(refreshedPage.getResponse().getContentAsString().contains("/withdrawals/"+saved.getId()+"/approve"),
                "An ADMIN without checker authority must not see approval controls.");
        var checkerPage=mvc.perform(get("/admin/contribution-workspace").param("tab","member-operations")
                        .param("employeeId",businessId).param("year",String.valueOf(today().getYear()))
                        .param("month",String.valueOf(today().getMonthValue()))
                        .with(user("checker").roles("ADMIN","WITHDRAWAL_CHECKER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(checkerPage.contains("/withdrawals/"+saved.getId()+"/approve"),
                "A separately authorized checker may review another user's pending request when the test policy flag is enabled.");
        assertTrue(checkerPage.contains("data-mfo-open-withdrawal-reject"), "A checker must open the rejection reason modal before submitting.");
        assertTrue(checkerPage.contains("Reject Withdrawal Request"));
        assertTrue(checkerPage.contains("mfo-withdrawal-transition-modal"));
        assertFalse(checkerPage.contains("/withdrawals/"+saved.getId()+"/cancel"),
                "A different checker cannot cancel the maker's request.");
    }

    @Test void cancellationPostUsesExistingRouteAndPreservesReservationAndAudit() throws Exception {
        Long id=request(2);
        assertEquals(2,withdrawals.reserved(oldId,-1L));
        var page=mvc.perform(get("/admin/contribution-workspace").param("tab","member-operations")
                .param("employeeId",businessId).param("year",String.valueOf(today().getYear()))
                .param("month",String.valueOf(today().getMonthValue())).with(user("maker").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(page.contains("data-withdrawal-row-id=\""+id+"\""));
        assertTrue(page.contains("data-mfo-open-cancel-withdrawal"));
        mvc.perform(post("/admin/contribution-workspace/withdrawals/"+id+"/cancel")
                        .param("version",String.valueOf(withdrawals.findById(id).orElseThrow().getVersion()))
                        .param("reason","Member withdrew the request")
                        .param("employeeId",businessId).param("year",String.valueOf(today().getYear()))
                        .param("month",String.valueOf(today().getMonthValue())).with(csrf()).with(user("maker").roles("ADMIN")))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("withdrawalSuccess"));
        assertEquals(0,withdrawals.reserved(oldId,-1L));
        assertEquals(10,holdings.findById(oldId).orElseThrow().getShareQuantity(),"Cancellation must not post a share reduction.");
        var cancelled=withdrawals.findById(id).orElseThrow();
        assertEquals("Member withdrew the request",cancelled.getCancellationReason());
        assertEquals("maker",cancelled.getResolvedBy());
        assertEquals(ShareWithdrawal.WithdrawalStatus.CANCELLED,cancelled.getStatus());
    }

    @Test void valuationPreviewUsesBigDecimalIsRequestSpecificAndNeverPersistsFinancialValues() throws Exception {
        Long id=request(2);
        var request=withdrawals.findById(id).orElseThrow();
        String url="/admin/contribution-workspace/withdrawals/"+id+"/valuation-preview";
        mvc.perform(post(url).param("version",String.valueOf(request.getVersion()))
                        .param("proposedValuePerShare","250.00").param("proposedDeduction","50.00")
                        .param("valuationDate",today().toString()).param("valuationReason","Illustrative review only")
                        .with(csrf()).with(user("maker").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.requestId").value(id))
                .andExpect(jsonPath("$.employeeId").value(businessId))
                .andExpect(jsonPath("$.category").value("OLD"))
                .andExpect(jsonPath("$.requestedQuantity").value(2))
                .andExpect(jsonPath("$.referenceValuePerShare").value("500.00"))
                .andExpect(jsonPath("$.grossAmount").value("500.00"))
                .andExpect(jsonPath("$.proposedDeduction").value("50.00"))
                .andExpect(jsonPath("$.netAmount").value("450.00"))
                .andExpect(jsonPath("$.previewOnly").value(true));
        var unchanged=withdrawals.findById(id).orElseThrow();
        assertEquals(ShareWithdrawal.WithdrawalStatus.PENDING,unchanged.getStatus());
        assertNull(unchanged.getGrossAmount());assertNull(unchanged.getDeductionAmount());assertNull(unchanged.getNetAmount());
        assertEquals(10,holdings.findById(oldId).orElseThrow().getShareQuantity());
        assertEquals(2,withdrawals.reserved(oldId,-1L));
        resetMaker();
        assertThrows(IllegalArgumentException.class,() -> service.previewValuation(id,unchanged.getVersion(),
                new BigDecimal("-0.01"),BigDecimal.ZERO,today(),"Invalid negative rate"));
        assertThrows(IllegalArgumentException.class,() -> service.previewValuation(id,unchanged.getVersion(),
                new BigDecimal("100.00"),new BigDecimal("200.01"),today(),"Deduction exceeds gross"));
        assertThrows(IllegalArgumentException.class,() -> service.previewValuation(id,unchanged.getVersion(),
                new BigDecimal("1.001"),BigDecimal.ZERO,today(),"No silent rounding"));
        var maximum=service.previewValuation(id,unchanged.getVersion(),
                new BigDecimal("499999999999999999.99"),BigDecimal.ZERO,today(),"Maximum supported preview");
        assertEquals("999999999999999999.98",maximum.grossAmount());
        Long newRequest=service.request(businessId,ShareHolding.ShareCategory.NEW,1,today(),"NEW category preview",UUID.randomUUID().toString());
        var newPreview=service.previewValuation(newRequest,withdrawals.findById(newRequest).orElseThrow().getVersion(),
                new BigDecimal("500.00"),BigDecimal.ZERO,today(),"NEW category review");
        assertEquals(ShareHolding.ShareCategory.NEW,newPreview.category());
        assertEquals("500.00",newPreview.grossAmount());
        assertNull(newPreview.referenceValuePerShare(),"Missing reference valuation must remain clearly unavailable.");
    }

    private MockHttpServletRequestBuilder withdrawalPost(String token, LocalDate effectiveDate) {
        return post("/admin/contribution-workspace/withdrawals")
                .param("workspaceContext","member-operations")
                .param("contextEmployeeId",businessId).param("contextYear",String.valueOf(today().getYear()))
                .param("contextMonth",String.valueOf(today().getMonthValue())).param("contextStatus","ALL")
                .param("employeeId",businessId).param("year",String.valueOf(today().getYear()))
                .param("month",String.valueOf(today().getMonthValue()))
                .param("category","NEW").param("quantity","1").param("effectiveDate",effectiveDate.toString())
                .param("reason","Future effective withdrawal request").param("requestToken",token);
    }
    @Test void invalidInputsMembershipAndHoldingsAreRejected() {
        assertThrows(IllegalArgumentException.class,()->request(0));assertThrows(IllegalArgumentException.class,()->request(-1));
        assertThrows(IllegalArgumentException.class,()->request(11));
        assertThrows(IllegalArgumentException.class,()->service.request("UNKNOWN",ShareHolding.ShareCategory.OLD,1,"Reason",UUID.randomUUID().toString()));
        assertThrows(IllegalArgumentException.class,()->service.request(businessId,null,1,"Reason",UUID.randomUUID().toString()));
        var employee=em.createQuery("select e from Employee e where e.employeeId=:id",Employee.class).setParameter("id",businessId).getSingleResult();
        employee.setFundExitDate(today().minusDays(1));em.flush();assertThrows(IllegalArgumentException.class,()->request(1));
    }
    @Test void newHoldingSelectionDoesNotDeductOldShares() {
        Long id=service.request(businessId,ShareHolding.ShareCategory.NEW,2,"NEW request",UUID.randomUUID().toString());approve(id);
        as("checker",()->service.postShares(id,service.details(id).version()));
        assertEquals(10,holdings.findById(oldId).orElseThrow().getShareQuantity());
        assertEquals(3,holdings.findByEmployeeEmployeeIdAndShareCategory(businessId,ShareHolding.ShareCategory.NEW).orElseThrow().getShareQuantity());
    }
    @Test void independentApprovalRejectsDuplicateAndPostingIsIdempotent() {
        Long id=request(4);assertThrows(IllegalStateException.class,()->service.approve(id,service.details(id).version()));
        asChecker("maker",()->assertThrows(IllegalStateException.class,()->service.approve(id,service.details(id).version())));
        approve(id);var approved=service.details(id);assertNotNull(approved.approvedAt());assertEquals("checker",approved.approvedBy());
        assertEquals(10,holdings.findById(oldId).orElseThrow().getShareQuantity());
        as("checker",()->assertThrows(IllegalStateException.class,()->service.approve(id,approved.version())));assertEquals(approved.approvedAt(),service.details(id).approvedAt());
        as("checker",()->service.postShares(id,approved.version()));
        as("checker",()->service.postShares(id,approved.version()));
        var result=withdrawals.details(id).orElseThrow();var audit=result.getShareTransaction();
        assertEquals(6,holdings.findById(oldId).orElseThrow().getShareQuantity());assertEquals(0,withdrawals.reserved(oldId,-1L));
        assertNotNull(audit);assertEquals(-4,audit.getQuantityChange());assertEquals(10,audit.getQuantityBefore());assertEquals(6,audit.getQuantityAfter());
        assertEquals(today(),audit.getEffectiveDate());assertEquals(ShareTransaction.ShareTransactionType.WITHDRAWAL_POSTED,audit.getTransactionType());
        assertNotNull(result.getSharesPostedAt());assertEquals("checker",result.getSharesPostedBy());
    }
    @Test void adminWithoutCheckerAuthorityCannotApproveOrReject() {
        Long id=request(2);
        as("reviewer-admin",()->assertThrows(IllegalStateException.class,()->service.approve(id,service.details(id).version())));
        as("reviewer-admin",()->assertThrows(IllegalStateException.class,()->service.reject(id,service.details(id).version(),"Not authorized")));
        assertEquals(ShareWithdrawal.WithdrawalStatus.PENDING,service.details(id).status());
        assertEquals(2,withdrawals.reserved(oldId,-1L));
        as("checker",()->service.approve(id,service.details(id).version()));
        as("reviewer-admin",()->assertThrows(IllegalStateException.class,()->service.postShares(id,service.details(id).version())));
        assertEquals(ShareWithdrawal.WithdrawalStatus.APPROVED,service.details(id).status());
        assertNull(service.details(id).postedAt());
        assertEquals(10,holdings.findById(oldId).orElseThrow().getShareQuantity());
    }
    @Test void cancellationAndRejectionReleaseReservationsWithoutBalanceChanges() {
        Long cancelled=request(2);service.cancel(cancelled,service.details(cancelled).version(),"Changed my mind");
        assertEquals(0,withdrawals.reserved(oldId,-1L));assertEquals(ShareWithdrawal.WithdrawalStatus.CANCELLED,service.details(cancelled).status());
        Long rejected=request(3);as("checker",()->service.reject(rejected,service.details(rejected).version(),"Review rejected"));
        assertEquals(ShareWithdrawal.WithdrawalStatus.REJECTED,service.details(rejected).status());
        assertEquals(0,withdrawals.reserved(oldId,-1L));assertEquals(10,holdings.findById(oldId).orElseThrow().getShareQuantity());
        assertThrows(IllegalStateException.class,()->service.approve(rejected,service.details(rejected).version()));
        assertThrows(IllegalStateException.class,()->service.postShares(cancelled,service.details(cancelled).version()));
    }
    @Test void manualAdjustmentsCannotConsumeReservedShares() {
        request(8);assertThrows(IllegalArgumentException.class,()->shares.adjustShares(oldId,-3,today(),"Must not consume reservations"));
        shares.adjustShares(oldId,2,today(),"Increase holdings");assertEquals(12,holdings.findById(oldId).orElseThrow().getShareQuantity());
    }
    @Test void postingAppliesToTheEffectiveMonthButPreservesAlreadyGeneratedObligations() {
        LocalDate month=today().withDayOfMonth(1);generation.generateOne(businessId,month);
        Long id=request(3);approve(id);as("checker",()->service.postShares(id,service.details(id).version()));
        money("170",shares.calculateMonthlyContribution(businessId,month).expectedAmount());
        money("170",shares.calculateMonthlyContribution(businessId,month.plusMonths(1)).expectedAmount());
        money("200",reporting.member(businessId,month.getYear(),month.getMonthValue(),month.getYear(),month.getMonthValue()).expected());
        money("200",reporting.annual(month.getYear(),businessId,"","ALL",0,10,"employeeId","asc").expected());
    }
    @Test void valuationRecordingAndDisbursementLifecycleSucceeds() {
        Long withdrawalId = request(2);
        var view1 = service.details(withdrawalId);
        service.recordValuation(withdrawalId, view1.version(), new BigDecimal("1000.00"), new BigDecimal("100.00"), new BigDecimal("900.00"), "Verified valuation");

        approve(withdrawalId);
        var view2 = service.details(withdrawalId);
        as("checker", () -> service.postShares(withdrawalId, view2.version()));

        var view3 = service.details(withdrawalId);
        service.confirmDisbursement(withdrawalId, view3.version(), "REF-12345");

        var finalView = service.details(withdrawalId);
        assertEquals(ShareWithdrawal.WithdrawalStatus.DISBURSED, finalView.status());
        assertEquals("REF-12345", finalView.paymentReference());
        assertNotNull(finalView.disbursedAt());
    }
    @Test void relationshipAndVersionConflictsAreRejected() {
        Long id=request(1);as("checker",()->assertThrows(IllegalStateException.class,()->service.approve(id,999L)));
        Employee other=new Employee();other.setEmployeeId("WD002");other.setFullName("Other Member");other.setEmployeeDesignation(Employee.EmployeeDesignation.EO);em.persist(other);em.flush();
        var w=withdrawals.findById(id).orElseThrow();w.setEmployee(other);em.flush();
        as("checker",()->assertThrows(IllegalStateException.class,()->service.approve(id,w.getVersion())));
    }
    @Test void workspaceLookupHistoryAndPolicyStatesRender() throws Exception {
        Long id=request(2);
        for(String tab:List.of("overview","withdrawals","profit","history")) {
            var result=mvc.perform(get("/admin/share-withdrawal-profit").param("tab",tab).param("employeeId",businessId)
                    .param("requestId",id.toString()).with(user("checker").roles("ADMIN")))
                    .andExpect(status().isOk()).andReturn();
            assertTrue(result.getResponse().getContentAsString().contains("Share Withdrawal &amp; Profit Allocation"));
            var folder=java.nio.file.Path.of("target/withdrawal-ui");java.nio.file.Files.createDirectories(folder);
            java.nio.file.Files.writeString(folder.resolve(tab+".html"),result.getResponse().getContentAsString());
        }
        mvc.perform(get("/admin/share-withdrawal-profit/employee").param("employeeId",businessId).with(user("maker").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.employeeId").value(businessId))
                .andExpect(jsonPath("$.id").doesNotExist()).andExpect(jsonPath("$.holdings.length()").value(2));
        var employeeResponse=mvc.perform(get("/admin/share-withdrawal-profit/employee").param("employeeId",businessId).with(user("maker").roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse();
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/withdrawal-ui/employee.json"),employeeResponse.getContentAsString());
        resetMaker();
        var history=service.history(businessId,today(),today(),"OLD","PENDING",0,10,"employeeId");assertEquals(1,history.getTotalElements());
        assertThrows(IllegalArgumentException.class,()->service.history("",today(),today().minusDays(1),"","",0,10,"requestDate"));
    }
    @Test void requestFormUsesBusinessIdAndRejectsMassAssignmentWithRetrySafety() throws Exception {
        String token=UUID.randomUUID().toString();
        for(int i=0;i<2;i++) mvc.perform(post("/admin/share-withdrawal-profit/requests")
                .param("employeeId",businessId).param("category","OLD").param("quantity","2").param("reason","HTTP request fixture")
                .param("requestToken",token).param("requestedBy","forged").param("status","DISBURSED").param("netAmount","99999")
                .with(csrf()).with(user("maker").roles("ADMIN"))).andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("moduleSuccess"));
        resetMaker();assertEquals(1,withdrawals.count());var view=service.details(withdrawals.findAll().get(0).getId());
        assertEquals("maker",view.requestedBy());assertEquals(ShareWithdrawal.WithdrawalStatus.PENDING,view.status());
        assertNull(view.net());assertNull(view.disbursedAt());assertEquals(10,holdings.findById(oldId).orElseThrow().getShareQuantity());
    }
    @Test void csrfRolesAndSensitiveConfirmationAreEnforced() throws Exception {
        mvc.perform(get("/admin/share-withdrawal-profit").with(anonymous())).andExpect(status().is3xxRedirection());
        mvc.perform(get("/admin/share-withdrawal-profit").with(user("ordinary").roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/share-withdrawal-profit/requests").with(user("maker").roles("ADMIN"))).andExpect(status().isForbidden());
        resetMaker(); Long id=request(2);
        mvc.perform(post("/admin/share-withdrawal-profit/requests/"+id+"/approve").param("version",service.details(id).version().toString())
                .param("confirmed","true").with(csrf()).with(user("ordinary-admin").roles("ADMIN")))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("moduleError"));
        resetMaker();assertEquals(ShareWithdrawal.WithdrawalStatus.PENDING,service.details(id).status());
        mvc.perform(post("/admin/share-withdrawal-profit/requests/"+id+"/approve").param("version",service.details(id).version().toString())
                .with(csrf()).with(user("checker").roles("ADMIN"))).andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("moduleError"));
        resetMaker();assertEquals(ShareWithdrawal.WithdrawalStatus.PENDING,service.details(id).status());
        mvc.perform(post("/admin/share-withdrawal-profit/requests/"+id+"/approve").param("version",service.details(id).version().toString())
                .param("confirmed","true").param("approvedBy","forged-client-name").with(csrf()).with(user("checker").roles("ADMIN","WITHDRAWAL_CHECKER")))
                .andExpect(status().is3xxRedirection());resetMaker();assertEquals("checker",service.details(id).approvedBy());
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void concurrentRequestsCannotOverReserveTheHolding() throws Exception {
        ExecutorService executor=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> tasks=new ArrayList<>();
            for(int i=0;i<2;i++) { final int n=i;tasks.add(executor.submit(()->{
                start.await();boolean[] ok={false};as("maker"+n,()->{try { request(8);ok[0]=true; } catch(IllegalArgumentException expected) {} });return ok[0];
            })); }
            start.countDown();int successful=0;for(var f:tasks)if(f.get(20,TimeUnit.SECONDS))successful++;
            assertEquals(1,successful);tx(()->assertEquals(8,withdrawals.reserved(oldId,-1L)));
        } finally { executor.shutdownNow(); }
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void concurrentPostingDeductsSharesAndCreatesAuditOnce() throws Exception {
        Long id=request(4);approve(id);Long version=service.details(id).version();
        ExecutorService executor=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<?>> tasks=new ArrayList<>();for(int i=0;i<2;i++)tasks.add(executor.submit(()->{try{start.await();as("checker",()->service.postShares(id,version));}catch(InterruptedException ex){Thread.currentThread().interrupt();}}));
            start.countDown();for(var f:tasks)f.get(20,TimeUnit.SECONDS);
            tx(()->{assertEquals(6,holdings.findById(oldId).orElseThrow().getShareQuantity());
                assertEquals(1L,em.createQuery("select count(t) from ShareTransaction t where t.transactionType=:type",Long.class).setParameter("type",ShareTransaction.ShareTransactionType.WITHDRAWAL_POSTED).getSingleResult());});
        } finally { executor.shutdownNow(); }
    }
}
