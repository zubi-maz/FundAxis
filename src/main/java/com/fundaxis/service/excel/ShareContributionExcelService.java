package com.fundaxis.service.excel; // Package of the Excel services

import com.fundaxis.entity.Contribution; // Monthly due entity
import com.fundaxis.entity.ContributionPayment; // Direct cash payment entity
import com.fundaxis.entity.Employee; // NBL Employee entity
import com.fundaxis.entity.MemberCreditAllocation; // Credit applied to a due
import com.fundaxis.entity.ShareHolding; // Share holding per category
import com.fundaxis.entity.ShareTransaction; // Effective-dated share history

import jakarta.persistence.EntityManager; // JPA query access
import org.apache.poi.ss.usermodel.*; // POI sheet/cell/style types
import org.apache.poi.ss.util.CellRangeAddress; // Merge/filter ranges
import org.apache.poi.ss.util.CellReference; // Column index to letter
import org.apache.poi.xssf.usermodel.XSSFCellStyle; // XLSX cell style
import org.apache.poi.xssf.usermodel.XSSFColor; // RGB colour
import org.apache.poi.xssf.usermodel.XSSFWorkbook; // XLSX workbook
import org.springframework.stereotype.Service; // Spring service bean
import org.springframework.transaction.annotation.Transactional; // Read-only tx

import java.io.ByteArrayOutputStream; // In-memory output
import java.io.IOException; // Write failure type
import java.math.BigDecimal; // Exact money maths
import java.time.LocalDate; // Date without time
import java.time.YearMonth; // Year + month
import java.time.ZoneId; // Time zone id
import java.time.ZonedDateTime; // Date-time with zone
import java.time.format.DateTimeFormatter; // Date formatting
import java.time.temporal.ChronoUnit; // Month difference
import java.util.*; // Collections, Date, Locale
import java.util.stream.Collectors; // Stream grouping

/**
 * Share and Contribution Excel exports. HOW THIS FILE IS ORGANISED:
 *
 *   PART 1  PUBLIC METHODS      exportYear, exportRange (YEARLY), exportMonth (MONTHLY)
 *   PART 2  YEARLY EXPORT       two-sheet yearly register + details  -> edit here for YEARLY
 *   PART 3  MONTHLY EXPORT      one-sheet single month register      -> edit here for MONTHLY (16 Columns A-P)
 *   PART 4  SHARED CODE         data loading, shares, styles, cell writers (used by both)
 *
 * This is a due-month settlement report, NOT a bank cashflow report.
 */
@Service // Register as Spring bean
public class ShareContributionExcelService {

    /// ==================================================================
    /// PART 1 - PUBLIC METHODS
    /// ==================================================================

    private final EntityManager entityManager; // JPA entity manager

    public ShareContributionExcelService(EntityManager entityManager) { // Constructor injection
        this.entityManager = entityManager; // Keep the manager
    }

    /** MONTHLY: ONE month only, ONE sheet (16-column IT HUB-style Single Month Register). */
    @Transactional(readOnly = true) // Read-only transaction
    public byte[] exportMonth(int year, int month) { // Single-month export

        if (year < 1900 || year > 9999
                || month < 1 || month > 12) { // Reject invalid year/month
            throw new IllegalArgumentException(
                    "Invalid reporting year or month"
            );
        }

        return monthlyGenerate(
                YearMonth.of(year, month)
        ); // Build the monthly workbook
    }

    /** MONTHLY: same report, for callers that already hold a YearMonth. */
    @Transactional(readOnly = true) // Read-only transaction
    public byte[] exportMonth(YearMonth month) { // Single-month export (overload)

        if (month == null) { // Missing input
            throw new IllegalArgumentException("Contribution month is required"); // Tell the caller
        }

        return exportMonth(month.getYear(), month.getMonthValue()); // Reuse the int validation
    }

    /** YEARLY: full calendar year (Jan-Dec), two sheets. */
    @Transactional(readOnly = true) // Read-only transaction
    public byte[] exportYear(int year) { // Full-year export

        if (year < 1900 || year > 9999) { // Reject silly years
            throw new IllegalArgumentException("Invalid reporting year"); // Tell the caller
        }

        return yearlyGenerate(YearMonth.of(year, 1), YearMonth.of(year, 12)); // Jan to Dec
    }

    /** YEARLY: custom month range (max 12 months), two sheets. */
    @Transactional(readOnly = true) // Read-only transaction
    public byte[] exportRange(YearMonth startMonth, YearMonth endMonth) { // Range export
        return yearlyGenerate(startMonth, endMonth); // Same workbook, other period
    }

    /// ##################################################################
    /// PART 2 - YEARLY EXPORT  (edit here ONLY for the yearly report)
    /// ##################################################################

    private static final String YEARLY_REGISTER_SHEET = "MEMBER MONTHLY REGISTER"; // Yearly sheet 1 name
    private static final String YEARLY_DETAILS_SHEET = "CONTRIBUTION DETAILS"; // Yearly sheet 2 name

    private static final int YEARLY_MAX_MONTHS = 12; // Register month limit
    private static final int YEARLY_GROUP_ROW = 4; // Register group header row
    private static final int YEARLY_HEADER_ROW = 5; // Register column header row
    private static final int YEARLY_DATA_ROW = 6; // Register first data row
    private static final int YEARLY_DET_HEADER_ROW = 4; // Details column header row
    private static final int YEARLY_DET_DATA_ROW = 5; // Details first data row
    private static final int YEARLY_FIRST_MONTH_COL = 6; // Months start at G

    private static final String CANCELLED = "CANCELLED"; // Canceled-only marker

    // ---------- YEARLY 2.1 main builder ----------

    private byte[] yearlyGenerate(YearMonth startMonth, YearMonth endMonth) { // Builds yearly workbook

        yearlyValidateRange(startMonth, endMonth); // Check the period

        LedgerData data = loadLedger(startMonth.atDay(1), endMonth.atDay(1)); // Load once

        Map<String, YearlyMemberRow> members = new TreeMap<>(); // One row per employee, sorted by ID
        List<YearlyDetailLine> detailLines = new ArrayList<>(); // Rows for details sheet

        BigDecimal detailDue = BigDecimal.ZERO; // Details due total (check)
        BigDecimal detailSettled = BigDecimal.ZERO; // Details settled total (check)

        for (Contribution due : data.dues()) { // Walk every due

            YearMonth month = YearMonth.from(due.getContributionMonth()); // Due month

            Settlement s = settlementOf(due, data); // Validated amounts

            Long employeeDbId = due.getEmployee().getId(); // Database id
            String employeeId = due.getEmployee().getEmployeeId(); // Business employee ID

            Shares monthShares = sharesAt(employeeDbId, due.getContributionMonth(),
                    data.holdingsByEmployee(), data.historyByHolding()); // Shares on the 1st

            detailLines.add(new YearlyDetailLine(
                    employeeId,
                    due.getEmployee().getFullName(),
                    due.getContributionMonth(),
                    monthShares,
                    due.getId(),
                    s.due(),
                    s.cash(),
                    s.credit(),
                    due.getContributionStatus().name())); // Add the detail row

            detailDue = detailDue.add(s.due()); // Running due check
            detailSettled = detailSettled.add(s.settled()); // Running settled check

            YearlyMemberRow member = members.computeIfAbsent(employeeId,
                    id -> new YearlyMemberRow(
                            employeeDbId,
                            employeeId,
                            due.getEmployee().getFullName(),
                            designationOf(due.getEmployee()))); // Create row once

            member.totalDue = member.totalDue.add(s.due()); // Add to total due

            if (s.canceled()) { // Canceled due
                member.canceledMonths.add(month); // Remember for CANCELLED marker
            } else { // Active due
                member.settledByMonth.merge(month, s.settled(), BigDecimal::add); // Aggregate per month
            }
        }

        BigDecimal memberDue = BigDecimal.ZERO; // Register due total
        BigDecimal memberSettled = BigDecimal.ZERO; // Register settled total

        for (YearlyMemberRow m : members.values()) { // Every register row
            memberDue = memberDue.add(m.totalDue); // Add due
            for (BigDecimal v : m.settledByMonth.values()) { // Every month cell
                memberSettled = memberSettled.add(v); // Add settled
            }
        }

        if (memberDue.compareTo(detailDue) != 0
                || memberSettled.compareTo(detailSettled) != 0) { // Sheets disagree
            throw new IllegalStateException(
                    "Register and details totals do not reconcile"); // Block export
        }

        detailLines.sort(Comparator
                .comparing(YearlyDetailLine::contributionMonth)
                .thenComparing(YearlyDetailLine::employeeId)
                .thenComparingLong(YearlyDetailLine::contributionId)); // Month, then employee ID

        List<YearMonth> months = new ArrayList<>(); // Selected month columns

        for (YearMonth m = startMonth; !m.isAfter(endMonth); m = m.plusMonths(1)) { // Each month
            months.add(m); // Keep the month
        }

        try (XSSFWorkbook workbook = new XSSFWorkbook(); // New workbook
             ByteArrayOutputStream output = new ByteArrayOutputStream()) { // Output buffer

            workbook.getProperties().getCoreProperties()
                    .setCreator("National Bank PLC - FundAxis"); // File author

            workbook.getProperties().getCoreProperties()
                    .setTitle("Share & Contribution Member Monthly Register"); // File title

            ExcelStyles styles = new ExcelStyles(workbook); // Shared styles

            Sheet register = workbook.createSheet(YEARLY_REGISTER_SHEET); // Sheet 1
            Sheet details = workbook.createSheet(YEARLY_DETAILS_SHEET); // Sheet 2

            String period = "Period: " + startMonth.atDay(1).format(LONG_DATE)
                    + " - " + endMonth.atEndOfMonth().format(LONG_DATE); // Full period text

            yearlyBuildRegisterSheet(register, period, months, members.values(),
                    endMonth.atEndOfMonth(), data, styles); // Fill sheet 1

            yearlyBuildDetailsSheet(details, period, detailLines, styles); // Fill sheet 2

            finishWorkbook(workbook, output); // Evaluate + write

            return output.toByteArray(); // Return the bytes

        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Failed to generate contribution Excel report", ex); // Wrap IO error
        }
    }

    // ---------- YEARLY 2.2 sheet 1: member monthly register ----------

    private void yearlyBuildRegisterSheet(
            Sheet sheet,
            String period,
            List<YearMonth> months,
            Collection<YearlyMemberRow> members,
            LocalDate periodEnd,
            LedgerData data,
            ExcelStyles styles) {

        List<String> headers = new ArrayList<>(List.of(
                "SL No.",
                "Employee ID",
                "Employee Full Name",
                "Designation",
                "OLD Shares (Period End)",
                "NEW Shares (Period End)")); // Fixed left headers

        for (YearMonth m : months) { // One header per month
            headers.add(m.format(SHORT_MONTH_LABEL) + "\nSettled (BDT)"); // Two-line month header
        }

        headers.add("Total Settled (BDT)"); // Total column 1
        headers.add("Total Contribution Due (BDT)"); // Total column 2
        headers.add("Remaining Due (BDT)"); // Total column 3

        int lastMonthCol = YEARLY_FIRST_MONTH_COL + months.size() - 1; // Last month column
        int settledCol = lastMonthCol + 1; // Total Settled column
        int dueCol = lastMonthCol + 2; // Total Due column
        int remainingCol = lastMonthCol + 3; // Remaining Due column
        int columnCount = headers.size(); // Total columns

        banners(sheet, "SHARE & CONTRIBUTION | MEMBER MONTHLY REGISTER",
                period, columnCount, styles); // Top block

        writeGroupRow(sheet, YEARLY_GROUP_ROW, columnCount, styles,
                new String[]{"EMPLOYEE INFORMATION", "SHARE HOLDINGS",
                        "MONTHLY CONTRIBUTIONS", "TOTALS"},
                new int[][]{{0, 3}, {4, 5},
                        {YEARLY_FIRST_MONTH_COL, lastMonthCol},
                        {lastMonthCol + 1, columnCount - 1}}); // Group headers

        writeHeaderRow(sheet, YEARLY_HEADER_ROW, headers, styles); // Column headers

        int[] widths = new int[columnCount]; // Column widths

        widths[0] = 7; // SL No.
        widths[1] = 16; // Employee ID
        widths[2] = 34; // Full name
        widths[3] = 27; // Designation
        widths[4] = 14; // OLD shares
        widths[5] = 14; // NEW shares

        for (int col = YEARLY_FIRST_MONTH_COL; col <= lastMonthCol; col++) { // Month widths
            widths[col] = 17; // Month column width
        }

        widths[settledCol] = 22; // Total Settled width
        widths[dueCol] = 22; // Total Due width
        widths[remainingCol] = 22; // Remaining width

        String firstMonthLetter = CellReference.convertNumToColString(YEARLY_FIRST_MONTH_COL); // First month letter
        String lastMonthLetter = CellReference.convertNumToColString(lastMonthCol); // Last month letter
        String settledLetter = CellReference.convertNumToColString(settledCol); // Settled letter
        String dueLetter = CellReference.convertNumToColString(dueCol); // Due letter

        int rowIndex = YEARLY_DATA_ROW; // Start right under header
        int serial = 1; // SL counter

        int oldTotal = 0; // Unique OLD holdings total
        int newTotal = 0; // Unique NEW holdings total
        boolean sharesKnown = true; // False if any N/A

        for (YearlyMemberRow member : members) { // One row per employee

            Row row = sheet.createRow(rowIndex); // New row
            row.setHeightInPoints(24); // Row height

            int r = rowIndex + 1; // Excel row number

            boolean alt = (rowIndex - YEARLY_DATA_ROW) % 2 == 1; // Zebra flag

            CellStyle text = styles.cell(CellKind.TEXT, alt); // Left text style
            CellStyle center = styles.cell(CellKind.CENTER, alt); // Centered text style
            CellStyle count = styles.cell(CellKind.COUNT, alt); // Integer style
            CellStyle money = styles.cell(CellKind.MONEY, alt); // BDT style

            putCount(row, 0, serial++, count); // SL No.
            putText(row, 1, member.employeeId, center); // Employee ID
            putText(row, 2, member.employeeName, text); // Employee name
            putText(row, 3, member.designation, text); // Designation

            Shares shares = sharesAt(member.employeeDbId, periodEnd,
                    data.holdingsByEmployee(), data.historyByHolding()); // Shares at period end

            putCount(row, 4, shares.oldShares(), count); // OLD shares
            putCount(row, 5, shares.newShares(), count); // NEW shares

            if (shares.oldShares() == null || shares.newShares() == null) { // History missing
                sharesKnown = false; // Do not total shares
            } else { // History known
                oldTotal += shares.oldShares(); // Add OLD
                newTotal += shares.newShares(); // Add NEW
            }

            for (int i = 0; i < months.size(); i++) { // Month cells

                YearMonth month = months.get(i); // This column's month
                int col = YEARLY_FIRST_MONTH_COL + i; // Column index

                BigDecimal settled = member.settledByMonth.get(month); // Null = no active due

                if (settled != null) { // Active due exists
                    putNumber(row, col, settled.doubleValue(), money); // Amount (0.00 if unpaid)
                } else if (member.canceledMonths.contains(month)) { // Only canceled due
                    putText(row, col, CANCELLED, styles.cancelled); // CANCELLED marker
                } else { // No record at all
                    row.createCell(col).setCellStyle(center); // Blank but bordered
                }
            }

            putFormula(row, settledCol,
                    "SUM(" + firstMonthLetter + r + ":" + lastMonthLetter + r + ")",
                    money); // Total settled formula

            putNumber(row, dueCol, member.totalDue.doubleValue(), money); // Total due from records

            putFormula(row, remainingCol,
                    dueLetter + r + "-" + settledLetter + r, money); // Remaining formula

            rowIndex++; // Next row
        }

        boolean hasData = rowIndex > YEARLY_DATA_ROW; // Any employee rows
        int lastDataRow = rowIndex - 1; // Last data row (zero-based)
        int firstExcel = YEARLY_DATA_ROW + 1; // First data row (Excel)
        int lastExcel = lastDataRow + 1; // Last data row (Excel)

        Row total = sheet.createRow(rowIndex); // Total row
        total.setHeightInPoints(28); // Total row height

        for (int col = 0; col < columnCount; col++) { // Style every cell
            total.createCell(col).setCellStyle(styles.totalText); // Green with black border
        }

        putText(total, 0, "TOTAL (BDT)", styles.totalText); // Label
        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 0, 3)); // Merge label A:D

        if (sharesKnown) { // Unique holdings known
            putCount(total, 4, oldTotal, styles.totalCount); // OLD total
            putCount(total, 5, newTotal, styles.totalCount); // NEW total
        } else { // Some history missing
            putText(total, 4, "N/A", styles.totalText); // Not verifiable
            putText(total, 5, "N/A", styles.totalText); // Not verifiable
        }

        for (int col = YEARLY_FIRST_MONTH_COL; col <= remainingCol; col++) { // Sum each money column
            if (hasData) { // Rows exist
                putFormula(total, col, sumFormula(col, firstExcel, lastExcel), styles.total); // Column total
            } else { // Empty export
                putNumber(total, col, 0.0, styles.total); // Plain 0.00
            }
        }

        int countRowIndex = rowIndex + 1; // Row under the total
        Row countRow = sheet.createRow(countRowIndex); // Count row
        countRow.setHeightInPoints(24); // Row height

        for (int col = 0; col < columnCount; col++) { // Style every cell
            countRow.createCell(col).setCellStyle(styles.totalText); // Green with black border
        }

        putText(countRow, 0, "MEMBER COUNT (ACTIVE DUES)", styles.totalText); // Label
        sheet.addMergedRegion(new CellRangeAddress(countRowIndex, countRowIndex, 0, 3)); // Merge label A:D

        for (int col = YEARLY_FIRST_MONTH_COL; col <= lastMonthCol; col++) { // Each month
            String letter = CellReference.convertNumToColString(col); // Column letter
            if (hasData) { // Rows exist
                putFormula(countRow, col,
                        "COUNT(" + letter + firstExcel + ":" + letter + lastExcel + ")",
                        styles.totalCount); // Numeric cells = active dues
            } else { // Empty export
                putCount(countRow, col, 0, styles.totalCount); // Plain 0
            }
        }

        int legendStart = countRowIndex + 2; // One blank row gap

        addLegend(sheet, legendStart, columnCount, styles,
                "Blank = no due record  |  0.00 = due but no settlement  |  "
                        + CANCELLED + " = canceled contribution  |  Month = contribution due month.",
                "Settled = active direct cash + applied member credit (NOT fresh cash receipts). "
                        + "Shares are as of the period end; \"N/A\" = no share history available. "
                        + "This is a due-month settlement register, not a bank cashflow report.",
                hasData ? "" : "No contribution records found for the selected period."); // Notes

        highlightPositive(sheet, remainingCol, YEARLY_DATA_ROW, lastDataRow); // Soft red when still due

        finishSheet(sheet, widths, 3, YEARLY_GROUP_ROW, YEARLY_HEADER_ROW, YEARLY_DATA_ROW,
                hasData ? lastDataRow : -1, legendStart + 2, PrintSetup.A3_PAPERSIZE); // Freeze, filter, print
    }

    // ---------- YEARLY 2.3 sheet 2: contribution details ----------

    private void yearlyBuildDetailsSheet(
            Sheet sheet,
            String period,
            List<YearlyDetailLine> lines,
            ExcelStyles styles) {

        String[] headers = { // Exactly 12 columns
                "Employee ID", // A
                "Employee Full Name", // B
                "Contribution Month", // C
                "OLD Shares (That Month)", // D
                "NEW Shares (That Month)", // E
                "Contribution Reference ID", // F
                "Contribution Due (BDT)", // G
                "Direct Cash Received (BDT)", // H
                "Member Credit Applied (BDT)", // I
                "Total Settled (BDT)", // J = H + I
                "Remaining Due (BDT)", // K = G - J
                "Contribution Status" // L
        };

        banners(sheet, "SHARE & CONTRIBUTION | DETAILED LEDGER",
                period, headers.length, styles); // Top block

        writeHeaderRow(sheet, YEARLY_DET_HEADER_ROW, Arrays.asList(headers), styles); // Column headers

        int[] widths = {16, 34, 16, 15, 15, 18, 22, 24, 24, 22, 22, 18}; // Column widths

        int rowIndex = YEARLY_DET_DATA_ROW; // Start right under header

        for (YearlyDetailLine line : lines) { // One row per contribution

            Row row = sheet.createRow(rowIndex); // New row
            row.setHeightInPoints(24); // Row height

            int r = rowIndex + 1; // Excel row number

            boolean alt = (rowIndex - YEARLY_DET_DATA_ROW) % 2 == 1; // Zebra flag

            CellStyle text = styles.cell(CellKind.TEXT, alt); // Left text style
            CellStyle center = styles.cell(CellKind.CENTER, alt); // Centered text style
            CellStyle month = styles.cell(CellKind.MONTH, alt); // Month date style
            CellStyle count = styles.cell(CellKind.COUNT, alt); // Integer style
            CellStyle money = styles.cell(CellKind.MONEY, alt); // BDT style

            putText(row, 0, line.employeeId(), center); // Employee ID
            putText(row, 1, line.employeeName(), text); // Employee name

            Cell monthCell = row.createCell(2); // Month cell
            monthCell.setCellValue(toExcelDate(line.contributionMonth())); // Real Excel date
            monthCell.setCellStyle(month); // Show as "mmm yyyy"

            putCount(row, 3, line.shares().oldShares(), count); // OLD shares
            putCount(row, 4, line.shares().newShares(), count); // NEW shares
            putCount(row, 5, (int) line.contributionId(), count); // Reference ID

            putNumber(row, 6, line.due().doubleValue(), money); // Due
            putNumber(row, 7, line.cash().doubleValue(), money); // Cash
            putNumber(row, 8, line.credit().doubleValue(), money); // Applied credit

            putFormula(row, 9, "H" + r + "+I" + r, money); // Settled = cash + credit
            putFormula(row, 10, "G" + r + "-J" + r, money); // Remaining = due - settled

            setStatus(row, 11, line.status(), styles); // Coloured status

            rowIndex++; // Next row
        }

        boolean hasData = rowIndex > YEARLY_DET_DATA_ROW; // Any rows
        int lastDataRow = rowIndex - 1; // Last data row (zero-based)
        int firstExcel = YEARLY_DET_DATA_ROW + 1; // First data row (Excel)
        int lastExcel = lastDataRow + 1; // Last data row (Excel)

        Row total = sheet.createRow(rowIndex); // Grand total row
        total.setHeightInPoints(28); // Total row height

        for (int col = 0; col < headers.length; col++) { // Style every cell
            total.createCell(col).setCellStyle(styles.totalText); // Green with black border
        }

        putText(total, 0, "GRAND TOTAL", styles.totalText); // Label
        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 0, 5)); // Merge label A:F

        for (int col = 6; col <= 10; col++) { // Columns G to K
            if (hasData) { // Rows exist
                putFormula(total, col, sumFormula(col, firstExcel, lastExcel), styles.total); // SUM formula
            } else { // Empty export
                putNumber(total, col, 0.0, styles.total); // Plain 0.00
            }
        }

        int legendStart = rowIndex + 2; // One blank row gap

        addLegend(sheet, legendStart, headers.length, styles,
                "Shares are as of the 1st of the contribution month. Total Settled = Direct Cash + "
                        + "Member Credit Applied. CANCELED contributions show 0.00 due and settlement.",
                hasData ? "" : "No contribution records found for the selected period."); // Notes

        highlightPositive(sheet, 10, YEARLY_DET_DATA_ROW, lastDataRow); // Soft red when still due

        finishSheet(sheet, widths, 2, -1, YEARLY_DET_HEADER_ROW, YEARLY_DET_DATA_ROW,
                hasData ? lastDataRow : -1, legendStart + 1, PrintSetup.A4_PAPERSIZE); // Freeze, filter, print
    }

    // ---------- YEARLY 2.4 yearly-only helpers ----------

    private void yearlyValidateRange(YearMonth start, YearMonth end) { // Range checks

        if (start == null || end == null) { // Missing input
            throw new IllegalArgumentException("Start and end months are required"); // Tell the caller
        }

        if (start.isAfter(end)) { // Wrong order
            throw new IllegalArgumentException("Start month cannot exceed end month"); // Tell the caller
        }

        if (start.getYear() < 1900 || end.getYear() > 9999) { // Silly years
            throw new IllegalArgumentException("Invalid reporting year"); // Tell the caller
        }

        long months = ChronoUnit.MONTHS.between(start, end) + 1; // Inclusive month count

        if (months > YEARLY_MAX_MONTHS) { // Register too wide
            throw new IllegalArgumentException(
                    "Please select a range of up to 12 months for the member register."); // Clear message
        }
    }

    /** One employee row of the yearly register. */
    private static class YearlyMemberRow {

        final Long employeeDbId; // Database id
        final String employeeId; // Business employee ID
        final String employeeName; // Full name
        final String designation; // Job title (blank if unmapped)

        BigDecimal totalDue = BigDecimal.ZERO; // Active due over the period

        final Map<YearMonth, BigDecimal> settledByMonth = new HashMap<>(); // Active months only
        final Set<YearMonth> canceledMonths = new HashSet<>(); // Months with a canceled due

        YearlyMemberRow(Long employeeDbId, String employeeId,
                        String employeeName, String designation) { // Constructor
            this.employeeDbId = employeeDbId; // Store db id
            this.employeeId = employeeId; // Store employee ID
            this.employeeName = employeeName; // Store name
            this.designation = designation; // Store designation
        }
    }

    /** One contribution row of the yearly details sheet. */
    private record YearlyDetailLine(
            String employeeId,
            String employeeName,
            LocalDate contributionMonth,
            Shares shares,
            long contributionId,
            BigDecimal due,
            BigDecimal cash,
            BigDecimal credit,
            String status) {
    }

    /// ##################################################################
    /// PART 3 - MONTHLY EXPORT  (edit here ONLY for the monthly report)
    /// ##################################################################

    private static final String MONTHLY_SHEET = "MONTHLY CONTRIBUTION"; // Monthly sheet name

    private static final int MONTHLY_GROUP_ROW = 4; // Group header row
    private static final int MONTHLY_HEADER_ROW = 5; // Column header row
    private static final int MONTHLY_DATA_ROW = 6; // First data row

    private static final DateTimeFormatter FULL_MONTH_LABEL =
            DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH); // e.g. October 2026

    // ---------- MONTHLY 3.1 main builder ----------

    private byte[] monthlyGenerate(YearMonth month) { // Builds monthly workbook

        LedgerData data = loadLedger(month.atDay(1), month.atDay(1)); // Load once

        Map<String, MonthlyRow> rows = new TreeMap<>(); // One row per employee, sorted by ID

        for (Contribution due : data.dues()) { // Walk every due of the month

            Settlement s = settlementOf(due, data); // Validated amounts

            Long employeeDbId = due.getEmployee().getId(); // Database id
            String employeeId = due.getEmployee().getEmployeeId(); // Business employee ID

            MonthlyRow row = rows.computeIfAbsent(employeeId,
                    id -> new MonthlyRow(
                            employeeDbId,
                            employeeId,
                            due.getEmployee().getFullName(),
                            designationOf(due.getEmployee()))); // Create row once

            row.records++; // Count the record

            if (s.canceled()) { // Canceled due
                row.canceledRecords++; // Count canceled
            } else { // Active due
                row.due = row.due.add(s.due()); // Add active due
                row.cash = row.cash.add(s.cash()); // Add cash
                row.credit = row.credit.add(s.credit()); // Add credit
            }
        }

        try (XSSFWorkbook workbook = new XSSFWorkbook(); // New workbook
             ByteArrayOutputStream output = new ByteArrayOutputStream()) { // Output buffer

            workbook.getProperties().getCoreProperties()
                    .setCreator("National Bank PLC - FundAxis"); // File author

            workbook.getProperties().getCoreProperties()
                    .setTitle("Monthly Contribution Register - " + month.format(FULL_MONTH_LABEL)); // File title

            ExcelStyles styles = new ExcelStyles(workbook); // Shared styles

            Sheet sheet = workbook.createSheet(MONTHLY_SHEET); // The only sheet

            monthlyBuildSheet(sheet, month, rows.values(), data, styles); // Fill the sheet

            finishWorkbook(workbook, output); // Evaluate + write

            return output.toByteArray(); // Return the bytes

        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Failed to generate monthly contribution Excel report", ex); // Wrap IO error
        }
    }

    // ---------- MONTHLY 3.2 the sheet (16 Columns A through P) ----------

    private void monthlyBuildSheet(
            Sheet sheet,
            YearMonth month,
            Collection<MonthlyRow> rows,
            LedgerData data,
            ExcelStyles styles) {

        String monthName = month.format(FULL_MONTH_LABEL); // e.g. October 2026

        // Exactly 16 Columns (A through P) as per specification
        String[] headers = {
                "SL No.",                          // Col A (0)
                "Employee ID",                     // Col B (1)
                "Employee Full Name",              // Col C (2)
                "Designation",                     // Col D (3)
                "OLD Shares",                      // Col E (4)
                "NEW Shares",                      // Col F (5)
                "Total Shares",                    // Col G (6) = E + F
                "Share Value per Share (BDT)",     // Col H (7)
                "Total Share Value (BDT)",         // Col I (8)
                "Monthly Contribution Due (BDT)",  // Col J (9)
                "Cash Received (BDT)",             // Col K (10)
                "Member Credit Applied (BDT)",     // Col L (11)
                "Total Contribution Paid (BDT)",   // Col M (12) = K + L
                "Remaining Contribution Due (BDT)",// Col N (13) = J - M
                "Payment Status",                  // Col O (14)
                "Remarks"                          // Col P (15)
        };

        String period = "Contribution Month: " + monthName + "  ("
                + month.atDay(1).format(LONG_DATE) + " - "
                + month.atEndOfMonth().format(LONG_DATE) + ")"; // Period text

        banners(sheet, "SHARE & CONTRIBUTION | MONTHLY CONTRIBUTION REGISTER",
                period, headers.length, styles); // Top block

        writeGroupRow(sheet, MONTHLY_GROUP_ROW, headers.length, styles,
                new String[]{
                        "EMPLOYEE INFORMATION",
                        "SHARE HOLDINGS & VALUATION (1ST OF MONTH)",
                        monthName.toUpperCase(Locale.ENGLISH) + " CONTRIBUTION",
                        "STATUS & REMARKS"
                },
                new int[][]{
                        {0, 3},   // A to D
                        {4, 8},   // E to I
                        {9, 13},  // J to N
                        {14, 15}  // O to P
                }); // Group headers

        writeHeaderRow(sheet, MONTHLY_HEADER_ROW, Arrays.asList(headers), styles); // Column headers

        // Column widths for 16 columns
        int[] widths = {
                7,  // A: SL No.
                16, // B: Employee ID
                34, // C: Employee Full Name
                25, // D: Designation
                13, // E: OLD Shares
                13, // F: NEW Shares
                14, // G: Total Shares
                18, // H: Share Value per Share (BDT)
                20, // I: Total Share Value (BDT)
                22, // J: Monthly Contribution Due (BDT)
                18, // K: Cash Received (BDT)
                22, // L: Member Credit Applied (BDT)
                20, // M: Total Contribution Paid (BDT)
                20, // N: Remaining Contribution Due (BDT)
                16, // O: Payment Status
                32  // P: Remarks
        };

        int rowIndex = MONTHLY_DATA_ROW; // Start right under header
        int serial = 1; // SL counter

        int oldTotal = 0; // OLD shares total
        int newTotal = 0; // NEW shares total
        boolean sharesKnown = true; // False if any N/A

        for (MonthlyRow m : rows) { // One row per employee

            Row row = sheet.createRow(rowIndex); // New row
            row.setHeightInPoints(24); // Row height

            int r = rowIndex + 1; // Excel row number

            boolean alt = (rowIndex - MONTHLY_DATA_ROW) % 2 == 1; // Zebra flag

            CellStyle text = styles.cell(CellKind.TEXT, alt); // Left text style
            CellStyle center = styles.cell(CellKind.CENTER, alt); // Centered text style
            CellStyle count = styles.cell(CellKind.COUNT, alt); // Integer style
            CellStyle money = styles.cell(CellKind.MONEY, alt); // BDT style

            Shares shares = sharesAt(m.employeeDbId, month.atDay(1),
                    data.holdingsByEmployee(), data.historyByHolding()); // Shares on the 1st

            // Col A (0): SL No.
            putCount(row, 0, serial++, count);

            // Col B (1): Employee ID
            putText(row, 1, m.employeeId, center);

            // Col C (2): Employee Full Name
            putText(row, 2, m.employeeName, text);

            // Col D (3): Designation
            putText(row, 3, m.designation, text);

            // Col E (4): OLD Shares
            putCount(row, 4, shares.oldShares(), count);

            // Col F (5): NEW Shares
            putCount(row, 5, shares.newShares(), count);

            // Col G (6): Total Shares (E + F)
            if (shares.oldShares() == null || shares.newShares() == null) {
                putText(row, 6, "N/A", center);
                sharesKnown = false;
            } else {
                putFormula(row, 6, "E" + r + "+F" + r, count);
                oldTotal += shares.oldShares();
                newTotal += shares.newShares();
            }

            // Col H (7): Share Value per Share (BDT) -> Approved rate not linked, banking policy "N/A"
            putText(row, 7, "N/A", center);

            // Col I (8): Total Share Value (BDT) -> Total shares * rate, "N/A" without valuation rate
            putText(row, 8, "N/A", center);

            // Col J (9): Monthly Contribution Due (BDT)
            putNumber(row, 9, m.due.doubleValue(), money);

            // Col K (10): Cash Received (BDT)
            putNumber(row, 10, m.cash.doubleValue(), money);

            // Col L (11): Member Credit Applied (BDT)
            putNumber(row, 11, m.credit.doubleValue(), money);

            // Col M (12): Total Contribution Paid (BDT) -> K + L
            putFormula(row, 12, "K" + r + "+L" + r, money);

            // Col N (13): Remaining Contribution Due (BDT) -> J - M
            putFormula(row, 13, "J" + r + "-M" + r, money);

            // Col O (14): Payment Status (PAID / PENDING / CANCELED)
            BigDecimal remaining = m.due.subtract(m.cash).subtract(m.credit);
            String status = monthlyStatusOf(m, remaining);
            setStatus(row, 14, status, styles);

            // Col P (15): Remarks
            putText(row, 15, monthlyRemarkOf(m, status), text);

            rowIndex++; // Next row
        }

        boolean hasData = rowIndex > MONTHLY_DATA_ROW; // Any employee rows
        int lastDataRow = rowIndex - 1; // Last data row (zero-based)
        int firstExcel = MONTHLY_DATA_ROW + 1; // First data row (Excel)
        int lastExcel = lastDataRow + 1; // Last data row (Excel)

        // ----------------------------------------------------
        // GRAND TOTAL ROW
        // ----------------------------------------------------
        Row total = sheet.createRow(rowIndex); // Total row
        total.setHeightInPoints(28); // Total row height

        for (int col = 0; col < headers.length; col++) { // Style every cell with black border
            total.createCell(col).setCellStyle(styles.totalText);
        }

        putText(total, 0, "GRAND TOTAL (BDT)", styles.totalText); // Label
        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 0, 3)); // Merge label A:D

        // Share Totals (Cols E, F, G)
        if (sharesKnown) {
            putCount(total, 4, oldTotal, styles.totalCount); // Col E
            putCount(total, 5, newTotal, styles.totalCount); // Col F
            putCount(total, 6, oldTotal + newTotal, styles.totalCount); // Col G
        } else {
            putText(total, 4, "N/A", styles.totalText);
            putText(total, 5, "N/A", styles.totalText);
            putText(total, 6, "N/A", styles.totalText);
        }

        // Share Values (Cols H, I)
        putText(total, 7, "N/A", styles.totalText);
        putText(total, 8, "N/A", styles.totalText);

        // Financial Columns J through N (Cols 9 to 13)
        for (int col = 9; col <= 13; col++) {
            if (hasData) {
                putFormula(total, col, sumFormula(col, firstExcel, lastExcel), styles.total);
            } else {
                putNumber(total, col, 0.0, styles.total);
            }
        }

        // Col O (14): Active Member Count
        if (hasData) {
            putFormula(total, 14,
                    "COUNTA(B" + firstExcel + ":B" + lastExcel + ")&\" members\"",
                    styles.totalText);
        } else {
            putText(total, 14, "0 members", styles.totalText);
        }

        // Col P (15): Blank cell with total styling
        putText(total, 15, "", styles.totalText);

        // ----------------------------------------------------
        // STATUS SUMMARY ROW (PAID / PENDING / CANCELED)
        // ----------------------------------------------------
        int summaryIndex = rowIndex + 1; // Row under total
        Row summary = sheet.createRow(summaryIndex); // Status summary row
        summary.setHeightInPoints(24); // Row height

        for (int col = 0; col < headers.length; col++) {
            summary.createCell(col).setCellStyle(styles.info); // Soft green banner
        }

        if (hasData) {
            String statusRange = "O" + firstExcel + ":O" + lastExcel; // Status is Col O
            putFormula(summary, 0,
                    "\"PAID: \"&COUNTIF(" + statusRange + ",\"PAID\")"
                            + "&\"   |   PENDING: \"&COUNTIF(" + statusRange + ",\"PENDING\")"
                            + "&\"   |   CANCELED: \"&COUNTIF(" + statusRange + ",\"CANCELED\")",
                    styles.info);
        } else {
            putText(summary, 0, "PAID: 0   |   PENDING: 0   |   CANCELED: 0", styles.info);
        }

        sheet.addMergedRegion(new CellRangeAddress(summaryIndex, summaryIndex, 0, headers.length - 1));

        // ----------------------------------------------------
        // LEGEND / NOTES
        // ----------------------------------------------------
        int legendStart = summaryIndex + 2; // One blank row gap

        addLegend(sheet, legendStart, headers.length, styles,
                "Monthly Contribution Due = this month's contribution (canceled excluded).  "
                        + "Total Contribution Paid = Cash Received + Member Credit Applied.  "
                        + "Remaining Contribution Due = Monthly Contribution Due - Total Contribution Paid.",
                "Shares are as of the 1st of the month; \"N/A\" = no share history available. "
                        + "Share Value per Share & Total Share Value show \"N/A\" when no official valuation rate is approved. "
                        + "Paid amounts follow the contribution month, not the bank deposit date.",
                hasData ? "" : "No contribution records found for " + monthName + ".");

        // Highlight Remaining Due (Col N = Index 13) when > 0
        highlightPositive(sheet, 13, MONTHLY_DATA_ROW, lastDataRow);

        // Freeze panes on Column 4 (A, B, C, D frozen for easy horizontal scrolling)
        finishSheet(sheet, widths, 4, MONTHLY_GROUP_ROW, MONTHLY_HEADER_ROW, MONTHLY_DATA_ROW,
                hasData ? lastDataRow : -1, legendStart + 2, PrintSetup.A3_PAPERSIZE);
    }

    // ---------- MONTHLY 3.3 monthly-only helpers ----------

    /** PAID / PENDING / CANCELED for one employee's month. */
    private String monthlyStatusOf(MonthlyRow m, BigDecimal remaining) {

        if (m.records == m.canceledRecords) { // Every record canceled
            return "CANCELED"; // Nothing active
        }

        return remaining.signum() == 0 ? "PAID" : "PENDING"; // Fully paid or not
    }

    /** Short plain-words remark for the admin. */
    private String monthlyRemarkOf(MonthlyRow m, String status) {

        String remark = ""; // Default: nothing to say

        if ("CANCELED".equals(status)) { // Canceled month
            remark = "Contribution canceled"; // Say so
        } else if ("PENDING".equals(status)) { // Unpaid or partly paid
            remark = (m.cash.signum() > 0 || m.credit.signum() > 0)
                    ? "Partly paid"
                    : "Not paid yet"; // Partly or not at all
        } else if (m.credit.signum() > 0) { // Paid using credit
            remark = m.cash.signum() > 0
                    ? "Paid by cash + member credit"
                    : "Paid from member credit"; // Credit used
        }

        if (m.records > 1) { // Several dues in the month
            remark = remark.isEmpty()
                    ? m.records + " contribution records"
                    : remark + " | " + m.records + " records"; // Mention the count
        }

        return remark; // Final text
    }

    /** One employee row of the monthly register. */
    private static class MonthlyRow {

        final Long employeeDbId; // Database id
        final String employeeId; // Business employee ID
        final String employeeName; // Full name
        final String designation; // Job title (blank if unmapped)

        BigDecimal due = BigDecimal.ZERO; // Active due this month
        BigDecimal cash = BigDecimal.ZERO; // Active cash this month
        BigDecimal credit = BigDecimal.ZERO; // Applied credit this month

        int records = 0; // All records this month
        int canceledRecords = 0; // Canceled records this month

        MonthlyRow(Long employeeDbId, String employeeId,
                   String employeeName, String designation) { // Constructor
            this.employeeDbId = employeeDbId; // Store db id
            this.employeeId = employeeId; // Store employee ID
            this.employeeName = employeeName; // Store name
            this.designation = designation; // Store designation
        }
    }

    /// ##################################################################
    /// PART 4 - SHARED CODE  (used by BOTH yearly and monthly)
    /// ##################################################################

    private static final int MAX_DUES = 30000; // Safety cap on records

    private static final String BANK = "NATIONAL BANK PLC"; // Banner line 1

    private static final ZoneId BANK_ZONE = ZoneId.of("Asia/Dhaka"); // Bank time zone

    private static final DateTimeFormatter SHORT_MONTH_LABEL =
            DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH); // e.g. Jan 2026

    private static final DateTimeFormatter LONG_DATE =
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH); // e.g. 01 Jan 2026

    private static final int BRAND_GREEN = 0x024731; // Titles, headers, totals
    private static final int ROW_TINT = 0xF2F8F5; // Alternate row pale green
    private static final int ROW_WHITE = 0xFFFFFF; // Normal row white
    private static final int SOFT_RED = 0xFDE8E7; // Canceled / outstanding tint
    private static final int SOFT_GREEN = 0xDFF3E6; // Paid / info tint
    private static final int SOFT_AMBER = 0xFFF3CD; // Pending tint
    private static final int SOFT_GREY = 0xE9E9E9; // Cancelled register cell

    // ---------- SHARED 4.1 data loading ----------

    /** Everything loaded once per export. */
    private record LedgerData(
            List<Contribution> dues,
            Map<Long, BigDecimal> cashMap,
            Map<Long, BigDecimal> creditMap,
            Map<Long, List<ShareHolding>> holdingsByEmployee,
            Map<Long, List<ShareTransaction>> historyByHolding) {
    }

    /** Validated amounts of ONE contribution. */
    private record Settlement(BigDecimal due, BigDecimal cash,
                              BigDecimal credit, boolean canceled) {

        BigDecimal settled() { // Cash + applied credit
            return cash.add(credit); // Sum of both
        }
    }

    private LedgerData loadLedger(LocalDate from, LocalDate to) { // Loads dues, money, shares

        List<Contribution> dues = entityManager.createQuery( // Load the dues
                        """
                        SELECT c FROM Contribution c
                        JOIN FETCH c.employee
                        WHERE c.contributionMonth BETWEEN :from AND :to
                        ORDER BY c.contributionMonth,
                                 c.employee.employeeId, c.id
                        """,
                        Contribution.class) // Result type
                .setParameter("from", from) // Period start
                .setParameter("to", to) // Period end
                .setMaxResults(MAX_DUES + 1) // Detect overflow
                .getResultList(); // Run the query

        if (dues.size() > MAX_DUES) { // Too many records
            throw new IllegalArgumentException(
                    "Too many contribution records. Select a shorter range."); // Ask for less
        }

        List<ContributionPayment> payments = entityManager.createQuery( // Load cash payments
                        """
                        SELECT p FROM ContributionPayment p
                        JOIN FETCH p.contribution c
                        WHERE c.contributionMonth BETWEEN :from AND :to
                        """,
                        ContributionPayment.class) // Result type
                .setParameter("from", from) // Period start
                .setParameter("to", to) // Period end
                .getResultList(); // Run the query

        List<MemberCreditAllocation> allocations = entityManager.createQuery( // Load credit use
                        """
                        SELECT a FROM MemberCreditAllocation a
                        JOIN FETCH a.contribution c
                        WHERE c.contributionMonth BETWEEN :from AND :to
                          AND a.reversed = false
                        """,
                        MemberCreditAllocation.class) // Result type
                .setParameter("from", from) // Period start
                .setParameter("to", to) // Period end
                .getResultList(); // Run the query

        Map<Long, BigDecimal> cashMap = new HashMap<>(); // Cash per contribution id
        Map<Long, BigDecimal> creditMap = new HashMap<>(); // Credit per contribution id

        for (ContributionPayment p : payments) { // Sum active cash
            if (!p.isReversed()) { // Skip reversed payments
                cashMap.merge(p.getContribution().getId(),
                        p.getPaymentAmount(), BigDecimal::add); // Add to the total
            }
        }

        for (MemberCreditAllocation a : allocations) { // Sum active credit
            creditMap.merge(a.getContribution().getId(),
                    a.getAmount(), BigDecimal::add); // Add to the total
        }

        List<ShareHolding> holdings = entityManager.createQuery( // Load all holdings
                        """
                        SELECT h FROM ShareHolding h
                        JOIN FETCH h.employee
                        """,
                        ShareHolding.class) // Result type
                .getResultList(); // Run the query

        List<ShareTransaction> transactions = entityManager.createQuery( // Load share history
                        """
                        SELECT t FROM ShareTransaction t
                        JOIN FETCH t.shareHolding
                        ORDER BY t.effectiveDate, t.id
                        """,
                        ShareTransaction.class) // Result type
                .getResultList(); // Run the query

        Map<Long, List<ShareHolding>> holdingsByEmployee = holdings.stream() // Group holdings
                .collect(Collectors.groupingBy(h -> h.getEmployee().getId())); // By employee id

        Map<Long, List<ShareTransaction>> historyByHolding = transactions.stream() // Group history
                .collect(Collectors.groupingBy(t -> t.getShareHolding().getId())); // By holding id

        return new LedgerData(dues, cashMap, creditMap,
                holdingsByEmployee, historyByHolding); // Hand everything over
    }

    /** Due, cash and credit of one contribution, with all safety checks. */
    private Settlement settlementOf(Contribution due, LedgerData data) {

        boolean canceled = due.getContributionStatus()
                == Contribution.ContributionStatus.CANCELED; // Is it canceled

        BigDecimal cash = data.cashMap().getOrDefault(due.getId(), BigDecimal.ZERO); // Active cash
        BigDecimal credit = data.creditMap().getOrDefault(due.getId(), BigDecimal.ZERO); // Active credit

        if (canceled && (cash.signum() != 0 || credit.signum() != 0)) { // Bad data
            throw new IllegalStateException(
                    "Canceled contribution has active settlement: " + due.getId()); // Block export
        }

        if (cash.signum() < 0 || credit.signum() < 0) { // Negative settlement
            throw new IllegalStateException(
                    "Negative settlement detected for contribution: " + due.getId()); // Block export
        }

        BigDecimal expected = canceled
                ? BigDecimal.ZERO
                : due.getContributionAmount(); // Canceled = nothing expected

        if (expected.subtract(cash).subtract(credit).signum() < 0) { // Over-settlement
            throw new IllegalStateException(
                    "Over-settlement detected for contribution: " + due.getId()); // Block export
        }

        return new Settlement(expected, cash, credit, canceled); // Validated result
    }

    // ---------- SHARED 4.2 shares (effective-dated) ----------

    /** OLD / NEW share quantity at one date. null = history unavailable. */
    private record Shares(Integer oldShares, Integer newShares) {
    }

    private Shares sharesAt(
            Long employeeDbId,
            LocalDate asOf,
            Map<Long, List<ShareHolding>> holdingsByEmployee,
            Map<Long, List<ShareTransaction>> historyByHolding) {

        int oldQuantity = 0; // Running OLD total
        int newQuantity = 0; // Running NEW total
        boolean oldKnown = true; // OLD history complete
        boolean newKnown = true; // NEW history complete

        for (ShareHolding holding : holdingsByEmployee.getOrDefault(employeeDbId, List.of())) { // Each holding

            Integer quantity = quantityAt(
                    historyByHolding.getOrDefault(holding.getId(), List.of()), asOf); // Qty on the date

            if (holding.getShareCategory() == ShareHolding.ShareCategory.OLD) { // OLD category

                if (quantity == null) { // No history at all
                    oldKnown = false; // Unknown, not zero
                } else { // History exists
                    oldQuantity += quantity; // Add quantity
                }

            } else if (holding.getShareCategory() == ShareHolding.ShareCategory.NEW) { // NEW category

                if (quantity == null) { // No history at all
                    newKnown = false; // Unknown, not zero
                } else { // History exists
                    newQuantity += quantity; // Add quantity
                }
            }
        }

        return new Shares(oldKnown ? oldQuantity : null, newKnown ? newQuantity : null); // Null = N/A
    }

    /** Quantity of ONE holding on a date; null when it has no history at all. */
    private Integer quantityAt(List<ShareTransaction> transactions, LocalDate asOf) {

        if (transactions.isEmpty()) { // No history
            return null; // Unknown, never fake zero
        }

        int quantity = 0; // Before first transaction

        for (ShareTransaction transaction : transactions) { // Ordered by date, id
            if (!transaction.getEffectiveDate().isAfter(asOf)) { // Effective by the date
                quantity = transaction.getQuantityAfter(); // Latest quantity wins
            }
        }

        return quantity; // Quantity on the date
    }

    /** Reads designation directly from Employee entity without reflection warnings. */
    private String designationOf(Employee employee) {
        if (employee == null || employee.getEmployeeDesignation() == null) {
            return "";
        }
        return employee.getEmployeeDesignation().name();
    }

    // ---------- SHARED 4.3 workbook finishing ----------

    private void finishWorkbook(XSSFWorkbook workbook, ByteArrayOutputStream output)
            throws IOException { // Common ending

        workbook.setActiveSheet(0); // Open on first sheet

        workbook.getCreationHelper().createFormulaEvaluator().evaluateAll(); // Cache formula results

        workbook.setForceFormulaRecalculation(true); // Recalculate on open

        workbook.write(output); // Write the file
    }

    // ---------- SHARED 4.4 styles ----------

    private enum CellKind {
        TEXT, // Left aligned text
        CENTER, // Centered text
        MONTH, // Date shown as month + year
        COUNT, // Integer
        MONEY // BDT amount
    }

    private static class ExcelStyles {

        final CellStyle title; // Bank name banner
        final CellStyle subtitle; // Report title banner
        final CellStyle info; // Period / summary banner
        final CellStyle group; // Group header (merged)
        final CellStyle header; // Column headers
        final CellStyle legend; // Notes under the table

        final CellStyle total; // Total money
        final CellStyle totalCount; // Total integer
        final CellStyle totalText; // Total text

        final CellStyle cancelled; // CANCELLED register cell
        final CellStyle statusPaid; // PAID colour
        final CellStyle statusPending; // PENDING colour
        final CellStyle statusCanceled; // CANCELED colour

        private final XSSFWorkbook workbook; // Owning workbook
        private final Font bodyFont; // Body font

        private final Map<String, CellStyle> cache = new HashMap<>(); // Reused body styles

        ExcelStyles(XSSFWorkbook workbook) { // Build all styles

            this.workbook = workbook; // Keep workbook

            Font titleFont = font(workbook, 17, true, IndexedColors.WHITE); // Big white
            Font subtitleFont = font(workbook, 12, true, IndexedColors.WHITE); // Medium white
            Font bodyFont = font(workbook, 10, false, IndexedColors.BLACK); // Normal black
            Font boldFont = font(workbook, 10, true, IndexedColors.BLACK); // Bold black
            Font headerFont = font(workbook, 10, true, IndexedColors.WHITE); // Bold white
            Font totalFont = font(workbook, 11, true, IndexedColors.WHITE); // Bold white

            this.bodyFont = bodyFont; // Keep body font

            XSSFColor brand = color(BRAND_GREEN); // Brand green

            title = style(workbook, titleFont, brand, true, false); // Title, no border
            subtitle = style(workbook, subtitleFont, brand, true, false); // Subtitle, no border
            info = style(workbook, boldFont, color(SOFT_GREEN), true, false); // Info, no border

            group = style(workbook, headerFont, brand, true, true); // Group header, bordered
            header = style(workbook, headerFont, brand, true, true); // Header, bordered
            header.setWrapText(true); // Wrap long headers

            legend = style(workbook, bodyFont, color(ROW_WHITE), false, false); // Legend, no border
            legend.setWrapText(true); // Wrap long notes

            total = style(workbook, totalFont, brand, true, true); // Total money style
            total.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00")); // BDT format

            totalCount = style(workbook, totalFont, brand, true, true); // Total integer style
            totalCount.setDataFormat(workbook.createDataFormat().getFormat("0")); // Integer format

            totalText = style(workbook, totalFont, brand, true, true); // Total text style

            cancelled = style(workbook, bodyFont, color(SOFT_GREY), true, true); // Grey cell
            statusPaid = style(workbook, bodyFont, color(SOFT_GREEN), true, true); // Soft green
            statusPending = style(workbook, bodyFont, color(SOFT_AMBER), true, true); // Soft amber
            statusCanceled = style(workbook, bodyFont, color(SOFT_RED), true, true); // Soft red
        }

        CellStyle cell(CellKind kind, boolean alternate) { // Body cell style

            String key = kind + "|" + alternate; // Cache key

            return cache.computeIfAbsent(key, k -> { // Create once

                XSSFCellStyle style = style(workbook, bodyFont,
                        color(alternate ? ROW_TINT : ROW_WHITE),
                        kind != CellKind.TEXT, true); // Text left, rest centered

                String format = formatOf(kind); // Number format

                if (format != null) { // Needs a format
                    style.setDataFormat(workbook.createDataFormat().getFormat(format)); // Apply it
                }

                return style; // Cache it
            });
        }

        private static String formatOf(CellKind kind) { // Format per kind

            return switch (kind) {
                case MONTH -> "mmm yyyy"; // Month + year
                case COUNT -> "0"; // Integer
                case MONEY -> "#,##0.00"; // BDT
                case TEXT, CENTER -> null; // General
            };
        }
    }

    private static Font font(Workbook workbook, int size, boolean bold, IndexedColors color) {

        Font font = workbook.createFont(); // New font
        font.setFontName("Calibri"); // Consistent family
        font.setFontHeightInPoints((short) size); // Size
        font.setBold(bold); // Weight
        font.setColor(color.getIndex()); // Colour

        return font; // Done
    }

    /** Converts 0xRRGGBB into an Excel colour. */
    private static XSSFColor color(int rgb) {

        byte[] bytes = {
                (byte) ((rgb >> 16) & 0xFF), // Red
                (byte) ((rgb >> 8) & 0xFF), // Green
                (byte) (rgb & 0xFF) // Blue
        };

        return new XSSFColor(bytes, null); // RGB colour
    }

    /** Style with RGB background; bordered = thin BLACK on all four sides. */
    private static XSSFCellStyle style(Workbook workbook, Font font,
                                       XSSFColor background, boolean centered, boolean bordered) {

        XSSFCellStyle style = (XSSFCellStyle) workbook.createCellStyle(); // New style

        style.setFont(font); // Font
        style.setVerticalAlignment(VerticalAlignment.CENTER); // Middle vertically
        style.setAlignment(centered
                ? HorizontalAlignment.CENTER
                : HorizontalAlignment.LEFT); // Horizontal alignment

        style.setFillForegroundColor(background); // Background
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND); // Solid fill

        if (bordered) { // Real cell borders
            short black = IndexedColors.BLACK.getIndex(); // Black colour index
            style.setBorderTop(BorderStyle.THIN); // Top line
            style.setBorderBottom(BorderStyle.THIN); // Bottom line
            style.setBorderLeft(BorderStyle.THIN); // Left line
            style.setBorderRight(BorderStyle.THIN); // Right line
            style.setTopBorderColor(black); // Top black
            style.setBottomBorderColor(black); // Bottom black
            style.setLeftBorderColor(black); // Left black
            style.setRightBorderColor(black); // Right black
        }

        return style; // Done
    }

    // ---------- SHARED 4.5 sheet frame ----------

    /** Top block used by every sheet (rows 0-3). */
    private void banners(Sheet sheet, String reportName, String period,
                         int columns, ExcelStyles styles) {

        sheet.setDisplayGridlines(false); // Clean look

        String generated = ZonedDateTime.now(BANK_ZONE)
                .format(DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.ENGLISH)); // Dhaka time

        banner(sheet, 0, BANK, columns, styles.title, 34); // Bank name
        banner(sheet, 1, reportName, columns, styles.subtitle, 26); // Report name
        banner(sheet, 2, period, columns, styles.info, 22); // Period
        banner(sheet, 3, "Currency: BDT  |  Generated (Dhaka): " + generated
                + "  |  Internal / Confidential", columns, styles.info, 22); // Currency line
    }

    private void banner(Sheet sheet, int rowIndex, String value,
                        int columns, CellStyle style, int height) {

        Row row = sheet.createRow(rowIndex); // New row
        row.setHeightInPoints(height); // Row height

        for (int col = 0; col < columns; col++) { // Style every cell
            row.createCell(col).setCellStyle(style); // Banner colour
        }

        row.getCell(0).setCellValue(value); // Text in first cell

        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 0, columns - 1)); // Merge the row
    }

    /** Group header row; labels[i] spans columns spans[i][0]..spans[i][1]. */
    private void writeGroupRow(Sheet sheet, int rowIndex, int columns,
                               ExcelStyles styles, String[] labels, int[][] spans) {

        Row row = sheet.createRow(rowIndex); // New row
        row.setHeightInPoints(24); // Row height

        for (int col = 0; col < columns; col++) { // Style every cell
            row.createCell(col).setCellStyle(styles.group); // Bordered green
        }

        for (int i = 0; i < labels.length; i++) { // Each group

            int first = spans[i][0]; // Group start column
            int last = spans[i][1]; // Group end column

            row.getCell(first).setCellValue(labels[i]); // Group label

            if (last > first) { // More than one column
                sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, first, last)); // Merge group only
            }
        }
    }

    /** Column header row; each header stays in its own cell. */
    private void writeHeaderRow(Sheet sheet, int rowIndex,
                                List<String> headers, ExcelStyles styles) {

        Row headerRow = sheet.createRow(rowIndex); // Header right after banner
        headerRow.setHeightInPoints(42); // Header height

        for (int i = 0; i < headers.size(); i++) { // Each header
            Cell cell = headerRow.createCell(i); // New cell
            cell.setCellValue(headers.get(i)); // Header text
            cell.setCellStyle(styles.header); // Header style
        }
    }

    /** Merged note lines under the table; blank lines are skipped. */
    private void addLegend(Sheet sheet, int firstRow, int columns,
                           ExcelStyles styles, String... lines) {

        int rowIndex = firstRow; // Start row

        for (String line : lines) { // One row per note

            if (line == null || line.isEmpty()) { // Nothing to show
                continue; // Skip it
            }

            Row row = sheet.createRow(rowIndex); // New row
            row.setHeightInPoints(30); // Room for wrapped text

            for (int col = 0; col < columns; col++) { // Style every cell
                row.createCell(col).setCellStyle(styles.legend); // Legend style
            }

            row.getCell(0).setCellValue(line); // Note text

            sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 0, columns - 1)); // Merge the row

            rowIndex++; // Next note
        }
    }

    private void finishSheet(Sheet sheet, int[] columnWidths, int freezeColumns,
                             int groupRow, int headerRow, int dataRow,
                             int lastDataRow, int lastUsedRow, int paperSize) {

        for (int i = 0; i < columnWidths.length; i++) { // Each column
            sheet.setColumnWidth(i, columnWidths[i] * 256); // Width in 1/256 chars
        }

        sheet.createFreezePane(freezeColumns, dataRow); // Freeze header rows + identity columns

        if (lastDataRow >= dataRow) { // Has data rows
            sheet.setAutoFilter(new CellRangeAddress(
                    headerRow, lastDataRow, 0, columnWidths.length - 1)); // Filter, excluding totals
        }

        PrintSetup printSetup = sheet.getPrintSetup(); // Print options

        printSetup.setPaperSize((short) paperSize); // A3 or A4
        printSetup.setLandscape(true); // Landscape
        printSetup.setFitWidth((short) 1); // One page wide
        printSetup.setFitHeight((short) 0); // Any pages tall

        sheet.setFitToPage(true); // Enable fit-to-page
        sheet.setHorizontallyCenter(true); // Center on page

        Workbook workbook = sheet.getWorkbook(); // Owning workbook

        workbook.setPrintArea(workbook.getSheetIndex(sheet),
                0, columnWidths.length - 1, 0, lastUsedRow); // Print area

        int firstRepeat = (groupRow >= 0 ? groupRow : headerRow) + 1; // First repeated row (Excel)
        int lastRepeat = headerRow + 1; // Last repeated row (Excel)

        sheet.setRepeatingRows(CellRangeAddress.valueOf(
                firstRepeat + ":" + lastRepeat)); // Repeat header on each page

        sheet.getFooter().setCenter(
                "NATIONAL BANK PLC | CONFIDENTIAL | Page &P of &N"); // Footer text
    }

    /** Soft-red highlight for every cell in a column that is greater than zero. */
    private void highlightPositive(Sheet sheet, int col, int firstDataRow, int lastDataRow) {

        if (lastDataRow < firstDataRow) { // No data rows
            return; // Nothing to highlight
        }

        SheetConditionalFormatting formatting = sheet.getSheetConditionalFormatting(); // Conditional formats

        String letter = CellReference.convertNumToColString(col); // Column letter

        ConditionalFormattingRule rule = formatting.createConditionalFormattingRule(
                "$" + letter + (firstDataRow + 1) + ">0"); // Rule: value above zero

        PatternFormatting fill = rule.createPatternFormatting(); // Fill settings
        fill.setFillBackgroundColor(color(SOFT_RED)); // Soft red
        fill.setFillPattern(PatternFormatting.SOLID_FOREGROUND); // Solid fill

        FontFormatting font = rule.createFontFormatting(); // Font settings
        font.setFontColorIndex(IndexedColors.DARK_RED.getIndex()); // Dark red text

        formatting.addConditionalFormatting(
                new CellRangeAddress[]{new CellRangeAddress(firstDataRow, lastDataRow, col, col)},
                rule); // Apply to data cells only
    }

    // ---------- SHARED 4.6 cell writers ----------

    private String sumFormula(int col, int firstExcelRow, int lastExcelRow) {

        String letter = CellReference.convertNumToColString(col); // Column letter

        return "SUM(" + letter + firstExcelRow + ":" + letter + lastExcelRow + ")"; // SUM(J7:J20)
    }

    /** Converts a LocalDate to a java.util.Date for POI. */
    private Date toExcelDate(LocalDate date) {
        return Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant()); // Start of day
    }

    private void putText(Row row, int col, String value, CellStyle style) {

        Cell cell = row.getCell(col) != null
                ? row.getCell(col)
                : row.createCell(col); // Reuse a pre-styled cell

        String safe = value == null ? "" : value; // Null-safe text

        if (!safe.isEmpty() && "=+-@\t\r\n".indexOf(safe.charAt(0)) >= 0) { // Looks like a formula
            safe = "'" + safe; // Neutralise formula injection
        }

        cell.setCellValue(safe); // Write text
        cell.setCellStyle(style); // Apply style
    }

    private void putNumber(Row row, int col, double value, CellStyle style) {

        Cell cell = row.getCell(col) != null
                ? row.getCell(col)
                : row.createCell(col); // Reuse a pre-styled cell

        cell.setCellValue(value); // Write number
        cell.setCellStyle(style); // Apply style
    }

    /** Integer count; null (unknown share history) is shown as "N/A". */
    private void putCount(Row row, int col, Integer value, CellStyle style) {

        Cell cell = row.getCell(col) != null
                ? row.getCell(col)
                : row.createCell(col); // Reuse a pre-styled cell

        if (value == null) { // Unknown history
            cell.setCellValue("N/A"); // Not a fake zero
        } else { // Known quantity
            cell.setCellValue(value); // Write integer
        }

        cell.setCellStyle(style); // Apply style
    }

    private void putFormula(Row row, int col, String formula, CellStyle style) {

        Cell cell = row.getCell(col) != null
                ? row.getCell(col)
                : row.createCell(col); // Reuse a pre-styled cell

        cell.setCellFormula(formula); // Write formula
        cell.setCellStyle(style); // Apply style
    }

    private void setStatus(Row row, int col, String status, ExcelStyles styles) {

        Cell cell = row.createCell(col); // New cell
        cell.setCellValue(status); // Status text

        switch (status) { // Pick the colour
            case "PAID" -> cell.setCellStyle(styles.statusPaid); // Green
            case "PENDING" -> cell.setCellStyle(styles.statusPending); // Amber
            default -> cell.setCellStyle(styles.statusCanceled); // Red
        }
    }
}