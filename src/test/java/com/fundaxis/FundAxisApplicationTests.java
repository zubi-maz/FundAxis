package com.fundaxis;

import com.fundaxis.entity.Employee;
import com.fundaxis.entity.Loan;
import com.fundaxis.entity.RepaymentSchedule;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@TestPropertySource(locations = "classpath:application-reporting-test.properties")
class FundAxisApplicationTests {

    @Autowired EntityManager entityManager;
    @Autowired WebApplicationContext context;

    @Test
    void contextLoads() {
    }

    @Test
    @Transactional
    void loanDetailsRendersManualScheduleAndUnreconciledPrincipalClearly() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Employee employee = new Employee();
        employee.setEmployeeId("PH2-" + suffix);
        employee.setFullName("Schedule Test Employee");
        employee.setEmployeeDesignation(Employee.EmployeeDesignation.EO);
        employee.setEmployeeStatus(Employee.EmployeeStatus.ACTIVE);
        employee.setFundJoinDate(LocalDate.of(2020, 1, 1));
        entityManager.persist(employee);

        Loan loan = new Loan();
        loan.setLoanApplicationId("PH2-LOAN-" + suffix);
        loan.setEmployee(employee);
        loan.setLoanType(Loan.LoanType.PERSONAL);
        loan.setRequestedAmount(new BigDecimal("1000.00"));
        loan.setTenureMonths(2);
        loan.setLoanPurpose("Isolated schedule view test");
        loan.setApplicationDate(LocalDate.now());
        loan.setLoanStatus(Loan.LoanStatus.DRAFT);
        loan.setEligibilityStatus(Loan.EligibilityStatus.PENDING);
        entityManager.persist(loan);

        RepaymentSchedule schedule = new RepaymentSchedule();
        schedule.setLoan(loan);
        schedule.setInstallmentNumber(1);
        schedule.setDueDate(LocalDate.now().minusDays(1));
        schedule.setPrincipalAmount(new BigDecimal("500.00"));
        schedule.setInterestAmount(new BigDecimal("25.00"));
        schedule.setTotalInstallmentAmount(new BigDecimal("525.00"));
        schedule.setPaidAmount(BigDecimal.ZERO);
        schedule.setRemainingAmount(new BigDecimal("525.00"));
        schedule.setInstallmentStatus(RepaymentSchedule.InstallmentStatus.PENDING);
        entityManager.persist(schedule);
        entityManager.flush();

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/admin/loans/{id}", loan.getLoanApplicationId())
                        .with(SecurityMockMvcRequestPostProcessors.user("schedule-view-admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/loan-details"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Repayment Schedule")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Outstanding Principal")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Not yet reconciled")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Mark Overdue")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("BDT 525.00")));
    }

    @Test
    void scheduleCreateFormPreservesLoanDetailsContext() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/admin/repayment-schedules/new")
                        .param("loanApplicationId", "LOAN-CONTEXT-1")
                        .param("returnToLoanDetails", "true")
                        .with(SecurityMockMvcRequestPostProcessors.user("schedule-view-admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/repayment-schedule-form"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"LOAN-CONTEXT-1\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"returnToLoanDetails\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/loans/LOAN-CONTEXT-1")));
    }

    @Test
    void loanExcelExportIsAdminOnlyAndReturnsAnXlsxWorkbook() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/admin/loans/export/excel")
                        .with(SecurityMockMvcRequestPostProcessors.user("loan-report-admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment; filename=\"fundaxis-loan-reports-")))
                .andExpect(result -> {
                    byte[] body = result.getResponse().getContentAsByteArray();
                    assertTrue(body.length > 100);
                    assertTrue(body[0] == 'P' && body[1] == 'K');
                });

        mvc.perform(get("/admin/loans/export/excel")
                        .with(SecurityMockMvcRequestPostProcessors.user("loan-report-support").roles("IT_SUPPORT")))
                .andExpect(status().isForbidden());
    }

}
