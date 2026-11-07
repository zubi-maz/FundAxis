package com.fundaxis.service.excel;

import com.fundaxis.entity.Loan;
import com.fundaxis.entity.LoanDisbursement;
import com.fundaxis.entity.LoanRepayment;
import com.fundaxis.entity.Repayment;
import com.fundaxis.entity.RepaymentSchedule;
import com.fundaxis.service.LoanDisbursementService;
import com.fundaxis.service.LoanRepaymentService;
import com.fundaxis.service.LoanService;
import com.fundaxis.service.RepaymentScheduleService;
import com.fundaxis.service.RepaymentService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Builds the loan reporting workbook from persisted FundAxis records. */
@Service
public class LoanReportingExcelService {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final LoanService loanService;
    private final LoanDisbursementService disbursementService;
    private final RepaymentScheduleService scheduleService;
    private final RepaymentService repaymentService;
    private final LoanRepaymentService loanRepaymentService;

    public LoanReportingExcelService(LoanService loanService,
                                     LoanDisbursementService disbursementService,
                                     RepaymentScheduleService scheduleService,
                                     RepaymentService repaymentService,
                                     LoanRepaymentService loanRepaymentService) {
        this.loanService = loanService;
        this.disbursementService = disbursementService;
        this.scheduleService = scheduleService;
        this.repaymentService = repaymentService;
        this.loanRepaymentService = loanRepaymentService;
    }

    @Transactional(readOnly = true)
    public byte[] export(String keyword, String loanStatus, String eligibilityStatus, String loanType) {
        List<Loan> loans = filteredLoans(keyword, loanStatus, eligibilityStatus, loanType);
        SXSSFWorkbook workbook = new SXSSFWorkbook(100);
        workbook.setCompressTempFiles(true);
        try (workbook; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Styles styles = new Styles(workbook);
            writeLoanRegister(workbook, styles, loans, keyword, loanStatus, eligibilityStatus, loanType);
            writeDisbursements(workbook, styles, loans);
            writeSchedules(workbook, styles, loans);
            writeRepaymentHistory(workbook, styles, loans);
            writeOutstanding(workbook, styles, loans);
            writeReconciliation(workbook, styles, loans);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to generate loan financial workbook", ex);
        } finally {
            workbook.dispose();
        }
    }

    private List<Loan> filteredLoans(String keyword, String status, String eligibility, String type) {
        Predicate<Loan> filter = loan -> true;
        String query = trimToNull(keyword);
        if (query != null) {
            String normalized = query.toLowerCase(Locale.ROOT);
            filter = filter.and(loan -> contains(loan.getLoanApplicationId(), normalized)
                    || loan.getEmployee() != null && (contains(loan.getEmployee().getEmployeeId(), normalized)
                    || contains(loan.getEmployee().getFullName(), normalized)));
        }
        Loan.LoanStatus loanStatus = enumFilter(status, Loan.LoanStatus.class, "loan status");
        Loan.EligibilityStatus eligibilityStatus = enumFilter(eligibility, Loan.EligibilityStatus.class, "eligibility status");
        Loan.LoanType loanTypeFilter = enumFilter(type, Loan.LoanType.class, "loan type");
        if (loanStatus != null) filter = filter.and(loan -> loan.getLoanStatus() == loanStatus);
        if (eligibilityStatus != null) filter = filter.and(loan -> loan.getEligibilityStatus() == eligibilityStatus);
        if (loanTypeFilter != null) filter = filter.and(loan -> loan.getLoanType() == loanTypeFilter);
        return loanService.getAllLoansList().stream().filter(filter)
                .sorted(Comparator.comparing(Loan::getApplicationDate,
                        Comparator.nullsLast(Comparator.reverseOrder()))).toList();
    }

    private static <E extends Enum<E>> E enumFilter(String value, Class<E> enumType, String label) {
        String normalized = trimToNull(value);
        if (normalized == null) return null;
        try {
            return Enum.valueOf(enumType, normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid " + label + " filter");
        }
    }

    private void writeLoanRegister(Workbook workbook, Styles styles, List<Loan> loans,
                                   String keyword, String status, String eligibility, String type) {
        Sheet sheet = sheet(workbook, "Loan Register", "Loan Register", "Filters: keyword=" + safe(keyword)
                + "; status=" + safe(status) + "; eligibility=" + safe(eligibility) + "; type=" + safe(type),
                styles, "Loan Reference", "Employee ID", "Employee Name", "Loan Type", "Guarantor",
                "Application Date", "Approved Amount (BDT)", "Interest Rate (%)", "Tenure (Months)",
                "Loan Status", "Approved By", "Approval Date");
        for (Loan loan : loans) {
            Row row = sheet.createRow(sheet.getLastRowNum() + 1);
            strings(row, loan.getLoanApplicationId(), employeeId(loan), employeeName(loan), text(loan.getLoanType()),
                    guarantor(loan), date(loan.getApplicationDate()));
            number(row, 6, loan.getApprovedAmount(), styles.money);
            number(row, 7, loan.getInterestRate(), styles.percent);
            number(row, 8, loan.getApprovedTenureMonths() == null ? loan.getTenureMonths() : loan.getApprovedTenureMonths(), styles.integer);
            stringsAt(row, 9, text(loan.getLoanStatus()), loan.getApprovedBy(), date(loan.getApprovalDate()));
        }
        widths(sheet, 24, 18, 30, 18, 28, 18, 22, 18, 18, 20, 22, 18);
    }

    private void writeDisbursements(Workbook workbook, Styles styles, List<Loan> loans) {
        Sheet sheet = sheet(workbook, "Disbursements", "Loan Disbursement Register", "Only persisted disbursement records are exported; totals below include DISBURSED records only.",
                styles, "Loan Reference", "Employee ID", "Employee Name", "Disbursement Reference", "Disbursement Date",
                "Payment Method", "Amount (BDT)", "Status", "Processed By");
        for (Loan loan : loans) {
            BigDecimal subtotal = BigDecimal.ZERO;
            for (LoanDisbursement d : disbursementService.getDisbursementsByLoanId(loan.getId())) {
                Row row = sheet.createRow(sheet.getLastRowNum() + 1);
                strings(row, loan.getLoanApplicationId(), employeeId(loan), employeeName(loan), d.getReferenceNumber(),
                        date(d.getDisbursementDate()), text(d.getPaymentMethod()));
                number(row, 6, d.getDisbursementAmount(), styles.money);
                stringsAt(row, 7, text(d.getDisbursementStatus()), d.getProcessedBy());
                if (d.getDisbursementStatus() == LoanDisbursement.DisbursementStatus.DISBURSED && d.getDisbursementAmount() != null)
                    subtotal = subtotal.add(d.getDisbursementAmount());
            }
            Row total = sheet.createRow(sheet.getLastRowNum() + 1);
            strings(total, loan.getLoanApplicationId(), "DISBURSED SUBTOTAL");
            number(total, 6, subtotal, styles.money);
            total.getCell(0).setCellStyle(styles.total);
            total.getCell(1).setCellStyle(styles.total);
            total.getCell(6).setCellStyle(styles.totalMoney);
        }
        widths(sheet, 22, 18, 28, 28, 18, 20, 22, 18, 24);
    }

    private void writeSchedules(Workbook workbook, Styles styles, List<Loan> loans) {
        Sheet sheet = sheet(workbook, "Repayment Schedule", "Repayment Schedule", "Scheduled obligations are not cash receipts.", styles,
                "Loan Reference", "Employee ID", "Employee Name", "Installment", "Due Date", "Principal (BDT)",
                "Regular Interest (BDT)", "Installment Total (BDT)", "Paid (BDT)", "Remaining (BDT)", "Status");
        for (Loan loan : loans) for (RepaymentSchedule s : scheduleService.getRepaymentSchedulesListByLoanId(loan.getId())) {
            Row row = sheet.createRow(sheet.getLastRowNum() + 1);
            strings(row, loan.getLoanApplicationId(), employeeId(loan), employeeName(loan));
            number(row, 3, s.getInstallmentNumber(), styles.integer);
            string(row, 4, date(s.getDueDate()));
            number(row, 5, s.getPrincipalAmount(), styles.money);
            number(row, 6, s.getInterestAmount(), styles.money);
            number(row, 7, s.getTotalInstallmentAmount(), styles.money);
            number(row, 8, s.getPaidAmount(), styles.money);
            number(row, 9, s.getRemainingAmount(), styles.money);
            string(row, 10, text(s.getInstallmentStatus()));
        }
        widths(sheet, 22, 17, 28, 15, 18, 22, 24, 24, 20, 22, 18);
    }

    private void writeRepaymentHistory(Workbook workbook, Styles styles, List<Loan> loans) {
        Sheet sheet = sheet(workbook, "Repayment History", "Repayment Transaction History", "The source ledger is shown for audit. Matching cross-ledger references are marked for reconciliation; principal, interest and fee allocations are not available.", styles,
                "Loan Reference", "Employee ID", "Employee Name", "Source Ledger", "Payment Reference", "Payment Date",
                "Amount (BDT)", "Method", "Status", "Recorded/Processed By", "Reversal Details");
        for (Loan loan : loans) {
            List<Repayment> direct = repaymentService.getRepaymentsByLoanId(loan.getId());
            List<LoanRepayment> workflow = loanRepaymentService.getLoanRepaymentsByLoanId(loan.getId());
            Set<String> directRefs = direct.stream().map(Repayment::getReferenceNumber).filter(Objects::nonNull)
                    .map(LoanReportingExcelService::normalizeReference).collect(Collectors.toSet());
            for (Repayment p : direct) {
                Row row = sheet.createRow(sheet.getLastRowNum() + 1);
                String ref = p.getReferenceNumber();
                strings(row, loan.getLoanApplicationId(), employeeId(loan), employeeName(loan), "repayments", ref,
                        date(p.getRepaymentDate()));
                number(row, 6, p.getRepaymentAmount(), styles.money);
                stringsAt(row, 7, text(p.getPaymentMethod()), text(p.getRepaymentStatus()), p.getRecordedBy(),
                        p.getRepaymentStatus() == Repayment.RepaymentStatus.REVERSED
                                ? date(p.getReversalDate()) + " | " + safe(p.getReversedBy()) + " | " + safe(p.getReversalReason()) : "");
            }
            for (LoanRepayment p : workflow) {
                Row row = sheet.createRow(sheet.getLastRowNum() + 1);
                String ref = p.getReferenceNumber();
                String status = text(p.getRepaymentStatus());
                if (ref != null && directRefs.contains(normalizeReference(ref))) status += " — CROSS-LEDGER REFERENCE REVIEW";
                strings(row, loan.getLoanApplicationId(), employeeId(loan), employeeName(loan), "loan_repayments", ref,
                        date(p.getRepaymentDate()));
                number(row, 6, p.getRepaymentAmount(), styles.money);
                stringsAt(row, 7, text(p.getPaymentMethod()), status, p.getProcessedBy(),
                        safe(p.getFailureReason()) + (p.getCancellationReason() == null ? "" : " | " + safe(p.getCancellationReason())));
            }
        }
        widths(sheet, 22, 17, 28, 22, 28, 18, 22, 20, 40, 25, 54);
    }

    private void writeOutstanding(Workbook workbook, Styles styles, List<Loan> loans) {
        Sheet sheet = sheet(workbook, "Outstanding Summary", "Loan Outstanding Summary", "Principal, interest and fees are not reported as zero where component allocation/policy is unavailable.", styles,
                "Loan Reference", "Employee ID", "Employee Name", "Approved Amount (BDT)", "Disbursed Principal (BDT)",
                "Schedule Balance (BDT)", "Principal Repaid", "Outstanding Principal", "Interest Outstanding",
                "Late Fee Outstanding", "Reconciliation Status");
        for (Loan loan : loans) {
            BigDecimal disbursed = disbursementService.getTotalDisbursedAmount(loan.getId());
            List<RepaymentSchedule> schedules = scheduleService.getRepaymentSchedulesListByLoanId(loan.getId());
            List<RepaymentSchedule> activeSchedules = schedules.stream()
                    .filter(s -> s.getInstallmentStatus() != RepaymentSchedule.InstallmentStatus.CANCELLED).toList();
            boolean scheduleBalanceReliable = !activeSchedules.isEmpty()
                    && activeSchedules.stream().allMatch(s -> s.getRemainingAmount() != null);
            BigDecimal scheduleBalance = scheduleBalanceReliable ? activeSchedules.stream()
                    .map(RepaymentSchedule::getRemainingAmount).reduce(BigDecimal.ZERO, BigDecimal::add) : null;
            Row row = sheet.createRow(sheet.getLastRowNum() + 1);
            strings(row, loan.getLoanApplicationId(), employeeId(loan), employeeName(loan));
            number(row, 3, loan.getApprovedAmount(), styles.money);
            number(row, 4, disbursed, styles.money);
            if (scheduleBalanceReliable) number(row, 5, scheduleBalance, styles.money);
            else string(row, 5, "NOT RECONCILED");
            string(row, 6, "NOT RECONCILED");
            string(row, 7, "NOT RECONCILED");
            string(row, 8, "NOT RECONCILED");
            string(row, 9, "NOT CONFIGURED");
            string(row, 10, "Schedule balance only; component outstanding is not established");
        }
        widths(sheet, 22, 17, 28, 24, 25, 23, 24, 25, 25, 24, 56);
    }

    private void writeReconciliation(Workbook workbook, Styles styles, List<Loan> loans) {
        Sheet sheet = sheet(workbook, "Reconciliation", "Loan Financial Reconciliation", "No differences are automatically adjusted by this report.", styles,
                "Loan Reference", "Employee ID", "Record Type", "Recorded Amount (BDT)", "Calculated Amount (BDT)",
                "Difference (BDT)", "Status", "Issue Description");
        for (Loan loan : loans) {
            List<RepaymentSchedule> schedules = scheduleService.getRepaymentSchedulesListByLoanId(loan.getId());
            for (RepaymentSchedule s : schedules) {
                Row row = sheet.createRow(sheet.getLastRowNum() + 1);
                BigDecimal parts = s.getPrincipalAmount() == null || s.getInterestAmount() == null
                        ? null : s.getPrincipalAmount().add(s.getInterestAmount());
                BigDecimal total = s.getTotalInstallmentAmount();
                BigDecimal difference = total == null || parts == null ? null : total.subtract(parts);
                boolean ok = s.getPrincipalAmount() != null && s.getInterestAmount() != null
                        && difference != null && difference.compareTo(BigDecimal.ZERO) == 0
                        && s.getPaidAmount() != null && s.getRemainingAmount() != null
                        && s.getPaidAmount().add(s.getRemainingAmount()).compareTo(total) == 0;
                strings(row, loan.getLoanApplicationId(), employeeId(loan), "INSTALLMENT " + s.getInstallmentNumber());
                number(row, 3, total, styles.money);
                number(row, 4, parts, styles.money);
                number(row, 5, difference, styles.money);
                string(row, 6, ok ? "RECONCILED" : "REVIEW REQUIRED");
                string(row, 7, ok ? "Saved installment components and balance reconcile" : "Installment amount, components or paid/remaining values differ");
            }
            List<Repayment> direct = repaymentService.getRepaymentsByLoanId(loan.getId());
            List<LoanRepayment> workflow = loanRepaymentService.getLoanRepaymentsByLoanId(loan.getId());
            BigDecimal activeReceipts = direct.stream().filter(p -> p.getRepaymentStatus() == Repayment.RepaymentStatus.COMPLETED)
                    .map(Repayment::getRepaymentAmount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .add(workflow.stream().filter(p -> p.getRepaymentStatus() == LoanRepayment.RepaymentStatus.COMPLETED)
                            .map(LoanRepayment::getRepaymentAmount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add));
            BigDecimal schedulePaid = schedules.stream().filter(s -> s.getInstallmentStatus() != RepaymentSchedule.InstallmentStatus.CANCELLED)
                    .map(RepaymentSchedule::getPaidAmount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            boolean receiptAmountsValid = direct.stream().filter(p -> p.getRepaymentStatus() == Repayment.RepaymentStatus.COMPLETED)
                    .allMatch(p -> p.getRepaymentAmount() != null && p.getRepaymentAmount().compareTo(BigDecimal.ZERO) > 0)
                    && workflow.stream().filter(p -> p.getRepaymentStatus() == LoanRepayment.RepaymentStatus.COMPLETED)
                    .allMatch(p -> p.getRepaymentAmount() != null && p.getRepaymentAmount().compareTo(BigDecimal.ZERO) > 0);
            Set<String> refs = direct.stream().map(Repayment::getReferenceNumber).filter(Objects::nonNull)
                    .map(LoanReportingExcelService::normalizeReference).collect(Collectors.toSet());
            boolean duplicateRef = workflow.stream().map(LoanRepayment::getReferenceNumber).filter(Objects::nonNull)
                    .map(LoanReportingExcelService::normalizeReference).anyMatch(refs::contains);
            boolean hasActiveSchedule = schedules.stream()
                    .anyMatch(s -> s.getInstallmentStatus() != RepaymentSchedule.InstallmentStatus.CANCELLED);
            boolean reconciled = hasActiveSchedule && receiptAmountsValid && !duplicateRef && activeReceipts.compareTo(schedulePaid) == 0
                    && schedules.stream().filter(s -> s.getInstallmentStatus() != RepaymentSchedule.InstallmentStatus.CANCELLED)
                    .allMatch(s -> s.getPaidAmount() != null && s.getRemainingAmount() != null && s.getTotalInstallmentAmount() != null
                            && s.getPrincipalAmount() != null && s.getInterestAmount() != null
                            && s.getPrincipalAmount().add(s.getInterestAmount()).compareTo(s.getTotalInstallmentAmount()) == 0
                            && s.getPaidAmount().add(s.getRemainingAmount()).compareTo(s.getTotalInstallmentAmount()) == 0);
            Row row = sheet.createRow(sheet.getLastRowNum() + 1);
            strings(row, loan.getLoanApplicationId(), employeeId(loan), "COMPLETED RECEIPTS VS SCHEDULE PAID");
            number(row, 3, activeReceipts, styles.money);
            number(row, 4, schedulePaid, styles.money);
            number(row, 5, activeReceipts.subtract(schedulePaid), styles.money);
            string(row, 6, reconciled ? "RECONCILED" : "REVIEW REQUIRED");
            String issue = duplicateRef ? "Reference exists in both repayment ledgers"
                    : activeReceipts.compareTo(schedulePaid) != 0 ? "Active receipt total differs from schedule paid total"
                    : "Arithmetic comparison only; source receipt uniqueness is not proven";
            string(row, 7, issue);
        }
        widths(sheet, 22, 17, 38, 25, 25, 22, 20, 56);
    }

    private static Sheet sheet(Workbook workbook, String name, String title, String subtitle, Styles styles, String... headers) {
        Sheet sheet = workbook.createSheet(name);
        Row titleRow = sheet.createRow(0);
        titleRow.createCell(0).setCellValue(title);
        titleRow.getCell(0).setCellStyle(styles.title);
        Row info = sheet.createRow(1);
        info.createCell(0).setCellValue(subtitle);
        info.createCell(1).setCellValue("Generated: " + LocalDateTime.now().format(TIMESTAMP));
        Row header = sheet.createRow(2);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(styles.header);
        }
        sheet.createFreezePane(0, 3);
        return sheet;
    }

    private static void widths(Sheet sheet, int... widths) {
        sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(2, Math.max(2, sheet.getLastRowNum()), 0, widths.length - 1));
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, Math.min(widths[i] * 256, 255 * 256));
    }

    private static void strings(Row row, String... values) {
        for (int i = 0; i < values.length; i++) string(row, i, values[i]);
    }

    private static void stringsAt(Row row, int startColumn, String... values) {
        for (int i = 0; i < values.length; i++) string(row, startColumn + i, values[i]);
    }

    private static void string(Row row, int column, String value) {
        if (value == null || value.isBlank()) return;
        Cell cell = row.createCell(column);
        cell.setCellValue(value); // Explicit string cell; user text is never evaluated as a formula.
    }

    private static void number(Row row, int column, BigDecimal value, CellStyle style) {
        if (value == null) return;
        Cell cell = row.createCell(column);
        cell.setCellValue(value.doubleValue());
        cell.setCellStyle(style);
    }

    private static void number(Row row, int column, Integer value, CellStyle style) {
        if (value == null) return;
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static String employeeId(Loan loan) { return loan.getEmployee() == null ? "" : loan.getEmployee().getEmployeeId(); }
    private static String employeeName(Loan loan) { return loan.getEmployee() == null ? "" : loan.getEmployee().getFullName(); }
    private static String guarantor(Loan loan) {
        if (loan.getGuarantorEmployee() != null)
            return safe(loan.getGuarantorEmployee().getEmployeeId()) + " — " + safe(loan.getGuarantorEmployee().getFullName());
        return safe(loan.getLegacyGuarantorName());
    }
    private static String text(Object value) { return value == null ? "" : value.toString(); }
    private static String date(LocalDate value) { return value == null ? "" : DATE.format(value); }
    private static String safe(String value) { return value == null ? "" : value; }
    private static String trimToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static boolean contains(String value, String normalized) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(normalized);
    }
    private static String normalizeReference(String value) { return value.trim().toUpperCase(Locale.ROOT); }

    private static final class Styles {
        private final CellStyle title;
        private final CellStyle header;
        private final CellStyle money;
        private final CellStyle percent;
        private final CellStyle integer;
        private final CellStyle total;
        private final CellStyle totalMoney;

        private Styles(Workbook workbook) {
            Font titleFont = workbook.createFont(); titleFont.setBold(true); titleFont.setFontHeightInPoints((short) 14);
            title = workbook.createCellStyle(); title.setFont(titleFont);
            Font headerFont = workbook.createFont(); headerFont.setBold(true); headerFont.setColor(IndexedColors.WHITE.getIndex());
            header = workbook.createCellStyle(); header.setFont(headerFont); header.setFillForegroundColor(IndexedColors.DARK_GREEN.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND); header.setWrapText(true);
            money = workbook.createCellStyle(); money.setDataFormat(workbook.createDataFormat().getFormat("\"BDT \"#,##0.00;[Red](\"BDT \"#,##0.00)"));
            percent = workbook.createCellStyle(); percent.setDataFormat(workbook.createDataFormat().getFormat("0.00\"%\""));
            integer = workbook.createCellStyle(); integer.setDataFormat(workbook.createDataFormat().getFormat("0"));
            Font totalFont = workbook.createFont(); totalFont.setBold(true);
            total = workbook.createCellStyle(); total.setFont(totalFont);
            totalMoney = workbook.createCellStyle(); totalMoney.cloneStyleFrom(money); totalMoney.setFont(totalFont);
        }
    }
}
