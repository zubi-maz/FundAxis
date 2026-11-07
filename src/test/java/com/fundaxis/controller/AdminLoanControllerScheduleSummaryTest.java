package com.fundaxis.controller;

import com.fundaxis.entity.Loan;
import com.fundaxis.entity.RepaymentSchedule;
import com.fundaxis.entity.Repayment;
import com.fundaxis.entity.LoanRepayment;
import com.fundaxis.service.LoanRepaymentService;
import com.fundaxis.service.LoanExtensionService;
import com.fundaxis.service.excel.LoanReportingExcelService;
import com.fundaxis.service.RepaymentService;
import com.fundaxis.service.LoanDisbursementService;
import com.fundaxis.service.LoanService;
import com.fundaxis.service.RepaymentScheduleService;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AdminLoanControllerScheduleSummaryTest {

    @Test
    void loanDetailsSummaryUsesSavedNonCancelledInstallmentsAndDoesNotInferPrincipalOutstanding() {
        LoanService loanService = mock(LoanService.class);
        LoanDisbursementService disbursementService = mock(LoanDisbursementService.class);
        RepaymentScheduleService scheduleService = mock(RepaymentScheduleService.class);
        RepaymentService repaymentService = mock(RepaymentService.class);
        LoanRepaymentService loanRepaymentService = mock(LoanRepaymentService.class);
        LoanReportingExcelService loanReportingExcelService = mock(LoanReportingExcelService.class);
        LoanExtensionService loanExtensionService = mock(LoanExtensionService.class);
        Model model = mock(Model.class);
        Loan loan = new Loan();
        loan.setId(12L);
        loan.setLoanApplicationId("LOAN-12");

        RepaymentSchedule active = schedule(1, "50.00", "5.00", "55.00", "20.00", "35.00",
                RepaymentSchedule.InstallmentStatus.PARTIAL);
        RepaymentSchedule cancelled = schedule(2, "100.00", "10.00", "110.00", "0.00", "110.00",
                RepaymentSchedule.InstallmentStatus.CANCELLED);
        when(loanService.getLoanByApplicationId("LOAN-12")).thenReturn(loan);
        when(loanExtensionService.getExtensionsByLoanId(12L)).thenReturn(List.of());
        when(disbursementService.getDisbursementsByLoanId(12L)).thenReturn(List.of());
        when(disbursementService.getTotalDisbursedAmount(12L)).thenReturn(new BigDecimal("60.00"));
        when(scheduleService.getRepaymentSchedulesListByLoanId(12L)).thenReturn(List.of(active, cancelled));
        when(repaymentService.getRepaymentsByLoanId(12L)).thenReturn(List.of());
        when(loanRepaymentService.getLoanRepaymentsByLoanId(12L)).thenReturn(List.of());

        AdminLoanController controller = new AdminLoanController(loanService, disbursementService, scheduleService,
                repaymentService, loanRepaymentService, loanReportingExcelService, loanExtensionService);
        assertEquals("admin/loan-details", controller.viewLoanDetails("LOAN-12", null, model));

        verify(model).addAttribute("scheduledPrincipalTotal", new BigDecimal("50.00"));
        verify(model).addAttribute("scheduledInterestTotal", new BigDecimal("5.00"));
        verify(model).addAttribute("scheduledInstallmentTotal", new BigDecimal("55.00"));
        verify(model).addAttribute("paidInstallmentTotal", new BigDecimal("20.00"));
        verify(model).addAttribute("remainingInstallmentTotal", new BigDecimal("35.00"));
        verify(model).addAttribute("activeScheduleCount", 1L);
        verify(model).addAttribute("cancelledScheduleCount", 1L);
        verify(model).addAttribute(eq("scheduleTotalsConsistent"), eq(true));
        verify(model).addAttribute(eq("repaymentSchedules"), eq(List.of(active, cancelled)));
        verify(model).addAttribute(eq("repaymentLedgerTotal"), eq(BigDecimal.ZERO));
        verify(model).addAttribute(eq("repaymentLedgerReconciled"), eq(false));
    }

    @Test
    void loanDetailsReconcilesCompletedPaymentsFromBothExistingLedgersAgainstScheduleCashPaid() {
        LoanService loanService = mock(LoanService.class);
        LoanDisbursementService disbursementService = mock(LoanDisbursementService.class);
        RepaymentScheduleService scheduleService = mock(RepaymentScheduleService.class);
        RepaymentService repaymentService = mock(RepaymentService.class);
        LoanRepaymentService loanRepaymentService = mock(LoanRepaymentService.class);
        LoanReportingExcelService loanReportingExcelService = mock(LoanReportingExcelService.class);
        LoanExtensionService loanExtensionService = mock(LoanExtensionService.class);
        Model model = mock(Model.class);
        Loan loan = new Loan();
        loan.setId(13L);
        loan.setLoanApplicationId("LOAN-13");
        RepaymentSchedule schedule = schedule(1, "50.00", "5.00", "55.00", "20.00", "35.00",
                RepaymentSchedule.InstallmentStatus.PARTIAL);
        Repayment direct = new Repayment();
        direct.setRepaymentAmount(new BigDecimal("12.00"));
        direct.setRepaymentStatus(Repayment.RepaymentStatus.COMPLETED);
        direct.setReferenceNumber("RCPT-1");
        LoanRepayment workflow = new LoanRepayment();
        workflow.setRepaymentAmount(new BigDecimal("8.00"));
        workflow.setRepaymentStatus(LoanRepayment.RepaymentStatus.COMPLETED);
        workflow.setReferenceNumber("RCPT-2");

        when(loanService.getLoanByApplicationId("LOAN-13")).thenReturn(loan);
        when(loanExtensionService.getExtensionsByLoanId(13L)).thenReturn(List.of());
        when(disbursementService.getDisbursementsByLoanId(13L)).thenReturn(List.of());
        when(disbursementService.getTotalDisbursedAmount(13L)).thenReturn(BigDecimal.ZERO);
        when(scheduleService.getRepaymentSchedulesListByLoanId(13L)).thenReturn(List.of(schedule));
        when(repaymentService.getRepaymentsByLoanId(13L)).thenReturn(List.of(direct));
        when(loanRepaymentService.getLoanRepaymentsByLoanId(13L)).thenReturn(List.of(workflow));

        AdminLoanController controller = new AdminLoanController(loanService, disbursementService, scheduleService,
                repaymentService, loanRepaymentService, loanReportingExcelService, loanExtensionService);
        controller.viewLoanDetails("LOAN-13", null, model);

        verify(model).addAttribute("repaymentLedgerTotal", new BigDecimal("20.00"));
        verify(model).addAttribute("repaymentLedgerReconciled", true);
        verify(model).addAttribute("duplicateCrossLedgerReference", false);
        verify(model).addAttribute("directRepayments", List.of(direct));
        verify(model).addAttribute("workflowRepayments", List.of(workflow));
    }

    @Test
    void loanDetailsFlagsSameReferencePresentInBothRepaymentLedgers() {
        LoanService loanService = mock(LoanService.class);
        LoanDisbursementService disbursementService = mock(LoanDisbursementService.class);
        RepaymentScheduleService scheduleService = mock(RepaymentScheduleService.class);
        RepaymentService repaymentService = mock(RepaymentService.class);
        LoanRepaymentService loanRepaymentService = mock(LoanRepaymentService.class);
        LoanReportingExcelService loanReportingExcelService = mock(LoanReportingExcelService.class);
        LoanExtensionService loanExtensionService = mock(LoanExtensionService.class);
        Model model = mock(Model.class);
        Loan loan = new Loan();
        loan.setId(14L);
        loan.setLoanApplicationId("LOAN-14");
        RepaymentSchedule schedule = schedule(1, "50.00", "5.00", "55.00", "20.00", "35.00",
                RepaymentSchedule.InstallmentStatus.PARTIAL);
        Repayment direct = new Repayment();
        direct.setReferenceNumber("Receipt-duplicate");
        direct.setRepaymentAmount(new BigDecimal("20.00"));
        direct.setRepaymentStatus(Repayment.RepaymentStatus.COMPLETED);
        LoanRepayment workflow = new LoanRepayment();
        workflow.setReferenceNumber(" receipt-DUPLICATE ");
        workflow.setRepaymentAmount(BigDecimal.ZERO);
        workflow.setRepaymentStatus(LoanRepayment.RepaymentStatus.PENDING);

        when(loanService.getLoanByApplicationId("LOAN-14")).thenReturn(loan);
        when(loanExtensionService.getExtensionsByLoanId(14L)).thenReturn(List.of());
        when(disbursementService.getDisbursementsByLoanId(14L)).thenReturn(List.of());
        when(disbursementService.getTotalDisbursedAmount(14L)).thenReturn(BigDecimal.ZERO);
        when(scheduleService.getRepaymentSchedulesListByLoanId(14L)).thenReturn(List.of(schedule));
        when(repaymentService.getRepaymentsByLoanId(14L)).thenReturn(List.of(direct));
        when(loanRepaymentService.getLoanRepaymentsByLoanId(14L)).thenReturn(List.of(workflow));

        AdminLoanController controller = new AdminLoanController(loanService, disbursementService, scheduleService,
                repaymentService, loanRepaymentService, loanReportingExcelService, loanExtensionService);
        controller.viewLoanDetails("LOAN-14", null, model);

        verify(model).addAttribute("duplicateCrossLedgerReference", true);
        verify(model).addAttribute("repaymentLedgerReconciled", false);
    }

    private RepaymentSchedule schedule(int number, String principal, String interest, String total,
                                       String paid, String remaining,
                                       RepaymentSchedule.InstallmentStatus status) {
        RepaymentSchedule schedule = new RepaymentSchedule();
        schedule.setId((long) number);
        schedule.setInstallmentNumber(number);
        schedule.setDueDate(LocalDate.of(2026, number, 1));
        schedule.setPrincipalAmount(new BigDecimal(principal));
        schedule.setInterestAmount(new BigDecimal(interest));
        schedule.setTotalInstallmentAmount(new BigDecimal(total));
        schedule.setPaidAmount(new BigDecimal(paid));
        schedule.setRemainingAmount(new BigDecimal(remaining));
        schedule.setInstallmentStatus(status);
        return schedule;
    }
}
