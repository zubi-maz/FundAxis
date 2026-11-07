package com.fundaxis.reporting;

import com.fundaxis.entity.*;
import com.fundaxis.service.*;
import com.fundaxis.service.excel.LoanReportingExcelService;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoanReportingExcelServiceTest {

    @Test
    void workbookContainsSixLoanReportsAndFlagsCrossLedgerReferenceWithoutInventingComponents() throws Exception {
        LoanService loans = mock(LoanService.class);
        LoanDisbursementService disbursements = mock(LoanDisbursementService.class);
        RepaymentScheduleService schedules = mock(RepaymentScheduleService.class);
        RepaymentService directPayments = mock(RepaymentService.class);
        LoanRepaymentService workflowPayments = mock(LoanRepaymentService.class);

        Loan loan = new Loan();
        loan.setId(51L);
        loan.setLoanApplicationId("LN-51");
        Employee employee = new Employee();
        employee.setEmployeeId("EMP-51");
        employee.setFullName("=1+1");
        loan.setEmployee(employee);
        loan.setLoanType(Loan.LoanType.PERSONAL);
        loan.setLoanStatus(Loan.LoanStatus.ACTIVE);
        loan.setEligibilityStatus(Loan.EligibilityStatus.ELIGIBLE);
        loan.setRequestedAmount(new BigDecimal("200.00"));
        loan.setApprovedAmount(new BigDecimal("200.00"));
        loan.setTenureMonths(2);
        loan.setApplicationDate(LocalDate.of(2026, 1, 1));

        RepaymentSchedule schedule = new RepaymentSchedule();
        schedule.setInstallmentNumber(1);
        schedule.setDueDate(LocalDate.of(2026, 2, 1));
        schedule.setPrincipalAmount(new BigDecimal("100.00"));
        schedule.setInterestAmount(new BigDecimal("10.00"));
        schedule.setTotalInstallmentAmount(new BigDecimal("110.00"));
        schedule.setPaidAmount(new BigDecimal("20.00"));
        schedule.setRemainingAmount(new BigDecimal("90.00"));
        schedule.setInstallmentStatus(RepaymentSchedule.InstallmentStatus.PARTIAL);

        Repayment direct = new Repayment();
        direct.setReferenceNumber("RCPT-1");
        direct.setRepaymentDate(LocalDate.of(2026, 2, 2));
        direct.setRepaymentAmount(new BigDecimal("20.00"));
        direct.setPaymentMethod(Repayment.PaymentMethod.CASH);
        direct.setRepaymentStatus(Repayment.RepaymentStatus.COMPLETED);
        LoanRepayment workflow = new LoanRepayment();
        workflow.setReferenceNumber(" rcpt-1 ");
        workflow.setRepaymentDate(LocalDate.of(2026, 2, 2));
        workflow.setRepaymentAmount(new BigDecimal("1.00"));
        workflow.setPaymentMethod(LoanRepayment.PaymentMethod.CASH);
        workflow.setRepaymentStatus(LoanRepayment.RepaymentStatus.COMPLETED);

        when(loans.getAllLoansList()).thenReturn(List.of(loan));
        LoanDisbursement paidDisbursement = new LoanDisbursement();
        paidDisbursement.setDisbursementAmount(new BigDecimal("150.00"));
        paidDisbursement.setDisbursementStatus(LoanDisbursement.DisbursementStatus.DISBURSED);
        paidDisbursement.setPaymentMethod(LoanDisbursement.PaymentMethod.BANK_TRANSFER);
        paidDisbursement.setReferenceNumber("TX-51");
        LoanDisbursement cancelledDisbursement = new LoanDisbursement();
        cancelledDisbursement.setDisbursementAmount(new BigDecimal("50.00"));
        cancelledDisbursement.setDisbursementStatus(LoanDisbursement.DisbursementStatus.CANCELLED);
        when(disbursements.getDisbursementsByLoanId(51L)).thenReturn(List.of(paidDisbursement, cancelledDisbursement));
        when(disbursements.getTotalDisbursedAmount(51L)).thenReturn(new BigDecimal("150.00"));
        when(schedules.getRepaymentSchedulesListByLoanId(51L)).thenReturn(List.of(schedule));
        when(directPayments.getRepaymentsByLoanId(51L)).thenReturn(List.of(direct));
        when(workflowPayments.getLoanRepaymentsByLoanId(51L)).thenReturn(List.of(workflow));

        LoanReportingExcelService service = new LoanReportingExcelService(loans, disbursements, schedules,
                directPayments, workflowPayments);
        byte[] bytes = service.export(null, null, null, null);

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertEquals(List.of("Loan Register", "Disbursements", "Repayment Schedule", "Repayment History",
                    "Outstanding Summary", "Reconciliation"),
                    java.util.stream.IntStream.range(0, workbook.getNumberOfSheets())
                            .mapToObj(workbook::getSheetName).toList());
            var exportedName = workbook.getSheet("Loan Register").getRow(3).getCell(2);
            assertEquals(CellType.STRING, exportedName.getCellType());
            assertEquals("=1+1", exportedName.getStringCellValue());
            var disbursementSheet = workbook.getSheet("Disbursements");
            assertEquals(CellType.NUMERIC, disbursementSheet.getRow(3).getCell(6).getCellType());
            assertEquals(150.0, disbursementSheet.getRow(3).getCell(6).getNumericCellValue());
            assertEquals("DISBURSED SUBTOTAL", disbursementSheet.getRow(5).getCell(1).getStringCellValue());
            assertEquals(150.0, disbursementSheet.getRow(5).getCell(6).getNumericCellValue());
            var history = workbook.getSheet("Repayment History");
            assertTrue(history.getRow(4).getCell(8).getStringCellValue().contains("CROSS-LEDGER REFERENCE REVIEW"));
            var outstanding = workbook.getSheet("Outstanding Summary").getRow(3);
            assertEquals("NOT RECONCILED", outstanding.getCell(7).getStringCellValue());
            assertEquals("NOT CONFIGURED", outstanding.getCell(9).getStringCellValue());
            var reconciliation = workbook.getSheet("Reconciliation");
            assertEquals("RECONCILED", reconciliation.getRow(3).getCell(6).getStringCellValue());
            assertEquals("REVIEW REQUIRED", reconciliation.getRow(4).getCell(6).getStringCellValue());
        }
    }
}
