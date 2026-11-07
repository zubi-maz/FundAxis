package com.fundaxis.service.excel; // Package of the Excel services

import com.fundaxis.service.ContributionReportingService.MonthlyRegisterRow; // One employee row

import org.apache.poi.ss.usermodel.*; // POI sheet/cell/style types
import org.apache.poi.ss.util.CellRangeAddress; // Merge / filter ranges
import org.apache.poi.ss.util.CellReference; // Column index to letter
import org.apache.poi.xssf.usermodel.XSSFCellStyle; // XLSX cell style
import org.apache.poi.xssf.usermodel.XSSFColor; // RGB colour
import org.apache.poi.xssf.usermodel.XSSFWorkbook; // XLSX workbook
import org.springframework.stereotype.Service; // Spring service bean

import java.io.ByteArrayOutputStream; // In-memory output
import java.io.IOException; // Write failure type
import java.math.BigDecimal; // Exact money type
import java.math.RoundingMode; // Rounding for the rate
import java.time.YearMonth; // Year + month
import java.time.ZoneId; // Time zone id
import java.time.ZonedDateTime; // Date-time with zone
import java.time.format.DateTimeFormatter; // Date formatting
import java.util.HashMap; // Style cache map
import java.util.List; // Record / month lists
import java.util.Locale; // English month names
import java.util.Map; // Maps of month data

///==================== Monthly Contribution Register Excel Service ====================///
// Dedicated XLSX formatter for Admin > Monthly Records > Monthly Contribution Register
// Creates one workbook with 2 sheets: "Monthly Contribution Register" and "Financial Summary"
// SECTIONS: MONTHLY REGISTER (export) -> SHARED HELPERS (look, writeText, writeNumber, writeMoney ...)
// LOGIC IS UNCHANGED: same method, same inputs, same columns, same totals; only the look is new

@Service // Register as Spring bean
public class MonthlyContributionRegisterExcelService {

    ///==================== MONTHLY REGISTER: Export ====================///

    private static final int GROUP_ROW = 4; // Group heading row
    private static final int HEADER_ROW = 5; // Column heading row
    private static final int DATA_ROW = 6; // First employee row

    ///---------- Build The Monthly Contribution Register Excel File ----------
    // Returns the file as bytes, the controller sends them to the browser as a download
    // months = the month columns, records = one row per employee, the rest = the totals of the register
    public byte[] export(
            List<YearMonth> months,
            List<MonthlyRegisterRow> records,
            BigDecimal totalDue,
            BigDecimal totalSettled,
            BigDecimal outstanding,
            int oldShares,
            int newShares
    ) {

        // At least one month is needed to build the month columns
        if (months == null || months.isEmpty()) { // Check months

            throw new IllegalArgumentException(
                    "At least one reporting month is required."); // Tell the caller

        }

        // The record list itself is required (it may be empty)
        if (records == null) { // Check records

            throw new IllegalArgumentException(
                    "Register records cannot be null."); // Tell the caller

        }

        // try-with-resources: the workbook and the output stream are closed automatically at the end
        try (
                XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()
        ) {

            // ----- Look (all styles, created once) -----

            Look look = new Look(workbook); // Colours, fonts, borders

            DateTimeFormatter shortMonth = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH); // e.g. Oct 2026

            int monthCount = months.size(); // Number of month columns

            // 7 fixed columns + months + 3 financial columns
            int totalColumns = 10 + monthCount; // Total column count

            // Period text used by both sheets, for example "Oct 2026 to Dec 2026"
            String periodText = months.get(0).format(shortMonth)
                    + " to " + months.get(monthCount - 1).format(shortMonth); // Period text

            // Generated line shown under the title
            String generated = "Currency: BDT  |  Generated (Dhaka): " + ZonedDateTime.now(ZoneId.of("Asia/Dhaka"))
                    .format(DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.ENGLISH))
                    + "  |  Internal / Confidential"; // Timestamp line

            // ----- Sheet 1: Monthly Contribution Register -----

            Sheet sheet = workbook.createSheet("Monthly Contribution Register"); // Sheet 1
            sheet.setDisplayGridlines(false); // Clean look, no grey grid

            // Rows 0 to 3: bank name, report title, period, currency line
            banner(sheet, 0, "NATIONAL BANK PLC | FUNDAXIS", totalColumns, look.title, 34); // Bank name
            banner(sheet, 1, "MONTHLY CONTRIBUTION REGISTER", totalColumns, look.subtitle, 26); // Report title
            banner(sheet, 2, "Reporting Period: " + periodText, totalColumns, look.info, 22); // Period
            banner(sheet, 3, generated, totalColumns, look.info, 22); // Generated line

            // Row 4: group headings above the column headings
            Row groupRow = sheet.createRow(GROUP_ROW); // Group row
            groupRow.setHeightInPoints(24); // Row height
            for (int i = 0; i < totalColumns; i++) { // Style every cell

                groupRow.createCell(i).setCellStyle(look.group); // Green with border

            }
            group(sheet, groupRow, 0, 3, "EMPLOYEE INFORMATION"); // SL to Designation
            group(sheet, groupRow, 4, 6, "SHARE HOLDINGS"); // OLD, NEW, Total
            group(sheet, groupRow, 7, 6 + monthCount, "MONTHLY CONTRIBUTIONS (SETTLED)"); // All months
            group(sheet, groupRow, 7 + monthCount, totalColumns - 1, "TOTALS"); // Three totals

            // Row 5: column headings
            Row headerRow = sheet.createRow(HEADER_ROW); // Heading row
            headerRow.setHeightInPoints(42); // Taller row for wrapped text

            String[] fixedHeaders = {
                    "SL No.",
                    "Employee ID",
                    "Employee Full Name",
                    "Designation",
                    "OLD Shares",
                    "NEW Shares",
                    "Total Shares"
            }; // Same fixed headings

            int col = 0; // Column counter

            for (String header : fixedHeaders) { // Each fixed heading

                Cell cell = headerRow.createCell(col++); // New cell
                cell.setCellValue(header); // Heading text
                cell.setCellStyle(look.header); // Header style

            }

            // Dynamic month columns
            for (YearMonth month : months) { // Each month

                Cell cell = headerRow.createCell(col++); // New cell
                cell.setCellValue(month.format(shortMonth) + "\nSettled (BDT)"); // Two-line heading
                cell.setCellStyle(look.header); // Header style

            }

            // Final financial columns
            String[] finalHeaders = {
                    "Total Settled (BDT)",
                    "Total Due (BDT)",
                    "Outstanding (BDT)"
            }; // Same final headings

            for (String header : finalHeaders) { // Each final heading

                Cell cell = headerRow.createCell(col++); // New cell
                cell.setCellValue(header); // Heading text
                cell.setCellStyle(look.header); // Header style

            }

            // ====================================================
            // EMPLOYEE ROWS
            // ====================================================

            int rowIndex = DATA_ROW; // First data row
            int serial = 1; // SL counter

            BigDecimal[] monthlyTotals = new BigDecimal[monthCount]; // Total per month column

            for (int i = 0; i < monthCount; i++) { // Start every total at zero

                monthlyTotals[i] = BigDecimal.ZERO; // Zero

            }

            for (MonthlyRegisterRow record : records) { // One row per employee

                Row row = sheet.createRow(rowIndex++); // New row
                row.setHeightInPoints(23); // Row height

                boolean alternate = serial % 2 == 0; // Zebra stripe flag

                CellStyle center = look.body(Kind.CENTER, alternate); // Centered text
                CellStyle left = look.body(Kind.TEXT, alternate); // Left text
                CellStyle count = look.body(Kind.COUNT, alternate); // Whole number
                CellStyle money = look.body(Kind.MONEY, alternate); // BDT amount

                int column = 0; // Column counter

                writeNumber(row, column++, serial++, count); // SL No.
                writeText(row, column++, record.employeeId(), center); // Employee ID
                writeText(row, column++, record.employeeName(), left); // Employee name
                writeText(row, column++, record.designation(), left); // Designation
                writeNumber(row, column++, record.oldShares(), count); // OLD shares
                writeNumber(row, column++, record.newShares(), count); // NEW shares
                writeNumber(row, column++, record.oldShares() + record.newShares(), count); // Total shares

                // Each selected month
                for (int i = 0; i < monthCount; i++) { // Month cells

                    YearMonth month = months.get(i); // This column's month

                    Map<YearMonth, String> statuses = record.monthlyStatuses(); // Status per month

                    String status = statuses.get(month); // Status of this month

                    BigDecimal amount = record.monthlySettled()
                            .getOrDefault(month, BigDecimal.ZERO); // Settled amount

                    if ("CANCELED".equals(status)) { // Canceled month

                        writeText(row, column++, "CANCELED", look.cancelled); // Grey marker

                    } else if (status == null || "NO_RECORD".equals(status)) { // No due record

                        writeText(row, column++, "-", center); // Dash

                    } else { // Normal month

                        writeMoney(row, column++, amount, money); // Amount

                        monthlyTotals[i] = monthlyTotals[i].add(amount); // Add to month total

                    }

                }

                writeMoney(row, column++, record.totalSettled(), money); // Total settled
                writeMoney(row, column++, record.totalDue(), money); // Total due
                writeMoney(row, column, record.remainingDue(), money); // Outstanding

            }

            // ====================================================
            // GRAND TOTAL ROW
            // ====================================================

            int totalIndex = rowIndex; // Total row index

            Row grandTotalRow = sheet.createRow(totalIndex); // Total row
            grandTotalRow.setHeightInPoints(28); // Row height

            for (int i = 0; i < totalColumns; i++) { // Green total look first

                grandTotalRow.createCell(i).setCellStyle(look.totalText); // Green with border

            }

            grandTotalRow.getCell(0).setCellValue("GRAND TOTAL"); // Label
            sheet.addMergedRegion(new CellRangeAddress(totalIndex, totalIndex, 0, 3)); // Merge A:D

            grandTotalRow.getCell(4).setCellValue(oldShares); // OLD total
            grandTotalRow.getCell(5).setCellValue(newShares); // NEW total
            grandTotalRow.getCell(6).setCellValue(oldShares + newShares); // Total shares

            int totalColumn = 7; // First month column

            for (BigDecimal monthlyTotal : monthlyTotals) { // Each month total

                writeMoney(grandTotalRow, totalColumn++, monthlyTotal, look.totalMoney); // Month total

            }

            writeMoney(grandTotalRow, totalColumn++, totalSettled, look.totalMoney); // Settled total
            writeMoney(grandTotalRow, totalColumn++, totalDue, look.totalMoney); // Due total
            writeMoney(grandTotalRow, totalColumn, outstanding, look.totalMoney); // Outstanding total

            // A short note under the table explains the signs
            Row note = sheet.createRow(totalIndex + 2); // Note row
            note.setHeightInPoints(32); // Room for wrapped text

            for (int i = 0; i < totalColumns; i++) { // Plain note look

                note.createCell(i).setCellStyle(look.legend); // No border

            }

            note.getCell(0).setCellValue("Month cell = amount settled against that month's contribution.  "
                    + "\"-\" = no due record for that month.  CANCELED = canceled contribution.  "
                    + "Outstanding = Total Due - Total Settled."); // Plain-words note
            sheet.addMergedRegion(new CellRangeAddress(totalIndex + 2, totalIndex + 2, 0, totalColumns - 1)); // Merge row

            // ====================================================
            // SHEET FORMATTING
            // ====================================================

            sheet.createFreezePane(3, DATA_ROW); // Keep headings + first 3 columns visible

            sheet.setAutoFilter(new CellRangeAddress(
                    HEADER_ROW, Math.max(HEADER_ROW, rowIndex - 1), 0, totalColumns - 1)); // Filter, not the total row

            highlightPositive(sheet, totalColumns - 1, DATA_ROW, rowIndex - 1); // Outstanding above zero turns soft red

            sheet.setColumnWidth(0, 9 * 256); // SL No.
            sheet.setColumnWidth(1, 17 * 256); // Employee ID
            sheet.setColumnWidth(2, 34 * 256); // Employee name
            sheet.setColumnWidth(3, 24 * 256); // Designation
            sheet.setColumnWidth(4, 13 * 256); // OLD shares
            sheet.setColumnWidth(5, 13 * 256); // NEW shares
            sheet.setColumnWidth(6, 13 * 256); // Total shares

            for (int i = 7; i < 7 + monthCount; i++) { // Month columns

                sheet.setColumnWidth(i, 17 * 256); // Month width

            }

            for (int i = 7 + monthCount; i < totalColumns; i++) { // Total columns

                sheet.setColumnWidth(i, 22 * 256); // Total width

            }

            printSetup(sheet, GROUP_ROW, HEADER_ROW, totalColumns - 1, totalIndex + 2, PrintSetup.A3_PAPERSIZE); // Print layout

            // ====================================================
            // SHEET 2 — FINANCIAL SUMMARY
            // ====================================================

            Sheet summarySheet = workbook.createSheet("Financial Summary"); // Sheet 2
            summarySheet.setDisplayGridlines(false); // Clean look

            banner(summarySheet, 0, "NATIONAL BANK PLC | FUNDAXIS", 2, look.title, 34); // Bank name
            banner(summarySheet, 1, "MONTHLY REGISTER FINANCIAL SUMMARY", 2, look.subtitle, 26); // Report title
            banner(summarySheet, 2, "Reporting Period: " + periodText, 2, look.info, 22); // Period
            banner(summarySheet, 3, "Currency: BDT", 2, look.info, 22); // Currency line

            Row sh = summarySheet.createRow(4); // Heading row
            sh.setHeightInPoints(26); // Row height

            Cell shLabel = sh.createCell(0); // Left heading
            shLabel.setCellValue("Summary Item"); // Heading text
            shLabel.setCellStyle(look.header); // Header style

            Cell shValue = sh.createCell(1); // Right heading
            shValue.setCellValue("Value"); // Heading text
            shValue.setCellStyle(look.header); // Header style

            BigDecimal safeDue = valueOrZero(totalDue); // Null-safe due
            BigDecimal safeSettled = valueOrZero(totalSettled); // Null-safe settled

            BigDecimal collectionRate = safeDue.signum() == 0
                    ? BigDecimal.ZERO
                    : safeSettled.multiply(BigDecimal.valueOf(100))
                    .divide(safeDue, 2, RoundingMode.HALF_UP); // Same rate maths

            int summaryRowIndex = 5; // First summary line

            summaryRowIndex = addSummaryText(summarySheet, summaryRowIndex, "Reporting Period", periodText, look); // Period
            summaryRowIndex = addSummaryCount(summarySheet, summaryRowIndex, "Total Members", records.size(), look); // Members
            summaryRowIndex = addSummaryCount(summarySheet, summaryRowIndex, "OLD Shares", oldShares, look); // OLD
            summaryRowIndex = addSummaryCount(summarySheet, summaryRowIndex, "NEW Shares", newShares, look); // NEW
            summaryRowIndex = addSummaryCount(summarySheet, summaryRowIndex, "Total Shares", oldShares + newShares, look); // Total shares
            summaryRowIndex = addSummaryMoney(summarySheet, summaryRowIndex, "Total Contribution Due (BDT)", safeDue, look); // Due
            summaryRowIndex = addSummaryMoney(summarySheet, summaryRowIndex, "Total Settled (BDT)", safeSettled, look); // Settled
            summaryRowIndex = addSummaryMoney(summarySheet, summaryRowIndex, "Total Outstanding (BDT)", valueOrZero(outstanding), look); // Outstanding
            addSummaryPercent(summarySheet, summaryRowIndex, "Collection Rate", collectionRate, look); // Rate

            summarySheet.setColumnWidth(0, 40 * 256); // Label column
            summarySheet.setColumnWidth(1, 30 * 256); // Value column
            summarySheet.createFreezePane(0, 5); // Freeze banner + heading
            printSetup(summarySheet, -1, 4, 1, summaryRowIndex, PrintSetup.A4_PAPERSIZE); // Print layout

            // ====================================================
            // WRITE EXCEL
            // ====================================================

            workbook.setActiveSheet(0); // Open on the first sheet

            workbook.write(output); // Write the file

            return output.toByteArray(); // Return the .xlsx bytes

        } catch (IOException exception) {

            throw new IllegalStateException(
                    "Failed to generate Monthly Contribution Register Excel.",
                    exception); // Same message

        }

    }


    /** Statement export retains the existing shared POI styles and cell writers. */
    public byte[] exportStatement(com.fundaxis.service.ContributionReportingService.StatementReport report) {
        try (XSSFWorkbook book=new XSSFWorkbook(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            Look look=new Look(book); var e=report.employee(); var s=report.summary();
            Sheet sheet=reportSheet(book,"Employee Statement","EMPLOYEE CONTRIBUTION STATEMENT",
                    "Contribution period: "+s.fromMonth()+" to "+YearMonth.from(s.toMonth()).atEndOfMonth()+" | Currency: BDT",look);
            reportRow(sheet,5,look,"Employee ID",e.getEmployeeId(),"Employee name",e.getFullName());
            reportRow(sheet,6,look,"Designation",e.getEmployeeDesignation(),"Employee status",e.getEmployeeStatus());
            reportRow(sheet,7,look,"Fund joined",e.getFundJoinDate(),"Fund exited",e.getFundExitDate());
            reportRow(sheet,8,look,"Reporting period",s.fromMonth(),s.toMonth(),"Generated on",report.generatedOn());
            reportRow(sheet,9,look,"Total recorded monthly due",s.expected(),"Net direct cash received",s.collected());
            reportRow(sheet,10,look,"Applied member credit",s.creditApplied(),"Total settled",s.totalSettled());
            reportRow(sheet,11,look,"Outstanding balance",s.outstanding(),"Paid contribution months",report.paidContributionMonths());
            reportRow(sheet,12,look,"Pending contribution months",report.pendingCount(),"Reversed payment records",report.reversalCount());
            reportHeader(sheet,14,look,"Contribution month","Recorded Monthly Due (BDT)","Net Direct Payment (BDT)","Applied Member Credit (BDT)","Total Settled (BDT)","Outstanding Balance (BDT)","Settlement Status");
            int n=15;
            for (var m:report.months()) {
                boolean noRecord=m.status().equals("NOT CREATED")||m.status().equals("NOT YET JOINED")||m.status().equals("AFTER EXIT");
                reportRow(sheet,n++,look,m.month(),noRecord?"—":m.expected(),noRecord?"—":m.collected(),noRecord?"—":m.creditApplied(),
                        noRecord?"—":m.collected().add(m.creditApplied()),noRecord?"—":m.outstanding(),m.status());
            }
            reportRow(sheet,n++,look,"GRAND TOTAL",s.expected(),s.collected(),s.creditApplied(),s.totalSettled(),s.outstanding(),"");
            reportRow(sheet,n+1,look,"Generated by FundAxis. Internal financial reporting document.");
            sheet.setAutoFilter(new CellRangeAddress(14,n-2,0,6));
            Sheet payments=reportSheet(book,"Payment History","Linked Payment History",
                    "Amounts are audit history, not additional obligations. REVERSED rows are excluded from cash received.",look);
            reportHeader(payments,4,look,"Payment ID","Payment date","Contribution period","Amount (BDT)","Payment method","Reference","Status","Reversal date","Reversal reason");
            n=5;
            for (var p:report.payments()) reportRow(payments,n++,look,p.paymentId(),p.paymentDate(),p.contributionMonth(),
                    p.amount(),p.displayMethod(),p.reference(),p.reversed()?"REVERSED":"RECORDED",p.reversalDate(),p.reversalReason());
            reportRow(payments,n,look,"NET DIRECT CASH RECEIVED","","",s.collected());
            finishReport(payments,n-1,9);
            Sheet credits=reportSheet(book,"Credit Allocations","Member Credit Allocation History",
                    "Active allocations settle obligations. Reversed allocations are retained for audit and excluded from settled totals.",look);
            reportHeader(credits,4,look,"Credit reference","Contribution period","Allocated amount (BDT)","Allocation date","Status","Reversal date","Reversal reason");
            n=5;
            for (var a:report.allocations()) reportRow(credits,n++,look,a.reference(),a.contributionMonth(),a.amount(),a.appliedDate(),
                    a.reversed()?"REVERSED":"APPLIED",a.reversalDate(),a.reversalReason());
            reportRow(credits,n,look,"ACTIVE CREDIT APPLIED","",s.creditApplied());
            finishReport(credits,n-1,7);
            book.write(out); return out.toByteArray();
        } catch (IOException ex) { throw new IllegalStateException("Unable to generate statement workbook.",ex); }
    }

    static Sheet reportSheet(XSSFWorkbook book,String name,String title,String note,Look look) {
        Sheet sheet=book.createSheet(name); sheet.setDisplayGridlines(false);
        reportRow(sheet,0,look,"National Bank PLC"); sheet.getRow(0).getCell(0).setCellStyle(look.title);
        reportRow(sheet,1,look,"FundAxis - Employee Fund Management System");
        reportRow(sheet,2,look,title); sheet.getRow(2).getCell(0).setCellStyle(look.subtitle);
        reportRow(sheet,3,look,note+" | Generated: "+ZonedDateTime.now(ZoneId.of("Asia/Dhaka")).format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm z")));
        for(int i=0;i<4;i++) { sheet.addMergedRegion(new CellRangeAddress(i,i,0,9)); sheet.getRow(i).setHeightInPoints(30); }
        for(int i=0;i<10;i++) sheet.setColumnWidth(i,(i==1?30:23)*256);
        sheet.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE); sheet.getPrintSetup().setLandscape(true);
        sheet.setFitToPage(true); sheet.getPrintSetup().setFitWidth((short)1); sheet.getPrintSetup().setFitHeight((short)0);
        return sheet;
    }
    static void reportHeader(Sheet sheet,int index,Look look,String... labels) {
        Row row=sheet.createRow(index); row.setHeightInPoints(34);
        for(int i=0;i<labels.length;i++) writeText(row,i,labels[i],look.header);
        sheet.createFreezePane(0,index+1); sheet.setRepeatingRows(new CellRangeAddress(index,index,-1,-1));
    }
    static void reportRow(Sheet sheet,int index,Look look,Object... values) {
        Row row=sheet.createRow(index); row.setHeightInPoints(24);
        for(int i=0;i<values.length;i++) {
            Object v=values[i];
            if(v instanceof BigDecimal money) writeMoney(row,i,money,look.reportMoney(index%2==0));
            else if(v instanceof Number number) { Cell cell=row.createCell(i); cell.setCellValue(number.doubleValue()); cell.setCellStyle(look.body(Kind.COUNT,index%2==0)); }
            else writeText(row,i,v==null?"":v.toString(),look.body(Kind.TEXT,index%2==0));
        }
    }
    static void finishReport(Sheet sheet,int lastRow,int columns) {
        if(lastRow>=5) sheet.setAutoFilter(new CellRangeAddress(4,lastRow,0,columns-1));
    }

    ///==================== SHARED HELPERS ====================///
    // Package-visible report formatters and Look are shared with the yearly exporter.
    // They format existing reporting data; they do not query or calculate obligations.

    ///---------- Kinds Of Body Cells ----------
    private enum Kind { TEXT, CENTER, COUNT, MONEY } // Left text, centered text, integer, BDT


    ///---------- Brand Colours (0xRRGGBB) ----------
    private static final int BRAND_GREEN = 0x024731; // Titles, headers, totals
    private static final int ROW_TINT = 0xF2F8F5; // Zebra stripe
    private static final int ROW_WHITE = 0xFFFFFF; // Normal row
    private static final int SOFT_GREEN = 0xDFF3E6; // Info band
    private static final int SOFT_GREY = 0xE9E9E9; // Canceled cell


    ///---------- All Styles Of The Workbook ----------
    // One object holds every style, so the export code above stays short
    static final class Look {

        final CellStyle title; // Bank name band
        final CellStyle subtitle; // Report title band
        final CellStyle info; // Period / currency band
        final CellStyle group; // Group heading
        final CellStyle header; // Column headings
        final CellStyle label; // Summary labels
        final CellStyle legend; // Note under the table
        final CellStyle totalText; // Total row text
        final CellStyle totalMoney; // Total row money
        final CellStyle percent; // Collection rate
        final CellStyle cancelled; // CANCELED month cell

        private final XSSFWorkbook workbook; // Owning workbook
        private final Font bodyFont; // Normal font
        private final Map<String, CellStyle> cache = new HashMap<>(); // Reused body styles

        Look(XSSFWorkbook workbook) { // Build every style once

            this.workbook = workbook; // Keep workbook

            Font titleFont = font(workbook, 17, true, IndexedColors.WHITE); // Big white
            Font subtitleFont = font(workbook, 12, true, IndexedColors.WHITE); // Medium white
            Font boldFont = font(workbook, 10, true, IndexedColors.BLACK); // Bold black
            Font headerFont = font(workbook, 10, true, IndexedColors.WHITE); // Bold white
            Font totalFont = font(workbook, 11, true, IndexedColors.WHITE); // Bold white
            this.bodyFont = font(workbook, 10, false, IndexedColors.BLACK); // Normal black

            title = style(workbook, titleFont, BRAND_GREEN, true, false); // Title, no border
            subtitle = style(workbook, subtitleFont, BRAND_GREEN, true, false); // Subtitle, no border
            info = style(workbook, boldFont, SOFT_GREEN, true, false); // Info band, no border

            group = style(workbook, headerFont, BRAND_GREEN, true, true); // Group heading, bordered
            header = style(workbook, headerFont, BRAND_GREEN, true, true); // Header, bordered
            header.setWrapText(true); // Wrap long headings

            label = style(workbook, headerFont, BRAND_GREEN, false, true); // Summary label, left aligned

            legend = style(workbook, bodyFont, ROW_WHITE, false, false); // Note, no border
            legend.setWrapText(true); // Wrap long note

            totalText = style(workbook, totalFont, BRAND_GREEN, true, true); // Total text
            totalMoney = style(workbook, totalFont, BRAND_GREEN, true, true); // Total money
            totalMoney.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00")); // 1,234.50

            percent = style(workbook, bodyFont, ROW_WHITE, true, true); // Rate cell
            percent.setDataFormat(workbook.createDataFormat().getFormat("0.00\"%\"")); // 85.50 %

            cancelled = style(workbook, bodyFont, SOFT_GREY, true, true); // Grey cell

        }

        CellStyle reportMoney(boolean alternate) {
            CellStyle base=body(Kind.MONEY,alternate);
            return cache.computeIfAbsent("REPORT_MONEY|"+alternate, key -> {
                CellStyle money=workbook.createCellStyle();
                money.cloneStyleFrom(base);
                money.setAlignment(HorizontalAlignment.RIGHT);
                return money;
            });
        }

        // Body cell: white or light green, black border, number format by kind
        CellStyle body(Kind kind, boolean alternate) { // Body style

            String key = kind + "|" + alternate; // Cache key

            return cache.computeIfAbsent(key, k -> { // Create once

                XSSFCellStyle s = style(workbook, bodyFont, alternate ? ROW_TINT : ROW_WHITE,
                        kind != Kind.TEXT, true); // Text left, the rest centered

                if (kind == Kind.COUNT) { // Integer
                    s.setDataFormat(workbook.createDataFormat().getFormat("0")); // No decimals
                } else if (kind == Kind.MONEY) { // BDT
                    s.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00")); // 1,234.50
                }

                return s; // Cache it

            });

        }

    }


    ///---------- Create A Font ----------
    private static Font font(Workbook workbook, int size, boolean bold, IndexedColors color) {

        Font font = workbook.createFont(); // New font
        font.setFontName("Calibri"); // One clean family
        font.setFontHeightInPoints((short) size); // Size
        font.setBold(bold); // Weight
        font.setColor(color.getIndex()); // Colour

        return font; // Done

    }


    ///---------- Create A Style ----------
    // bordered = thin BLACK line on all four sides
    private static XSSFCellStyle style(Workbook workbook, Font font, int rgb, boolean centered, boolean bordered) {

        XSSFCellStyle style = (XSSFCellStyle) workbook.createCellStyle(); // New style
        style.setFont(font); // Font
        style.setVerticalAlignment(VerticalAlignment.CENTER); // Middle vertically
        style.setAlignment(centered ? HorizontalAlignment.CENTER : HorizontalAlignment.LEFT); // Horizontal alignment
        style.setFillForegroundColor(new XSSFColor(new byte[]{(byte) (rgb >> 16), (byte) (rgb >> 8), (byte) rgb}, null)); // Background
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


    ///---------- Merged Banner Row ----------
    private static void banner(Sheet sheet, int rowIndex, String text, int columns, CellStyle style, int height) {

        Row row = sheet.createRow(rowIndex); // New row
        row.setHeightInPoints(height); // Row height

        for (int col = 0; col < columns; col++) { // Style every cell

            row.createCell(col).setCellStyle(style); // Band colour

        }

        row.getCell(0).setCellValue(text); // Text in the first cell
        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 0, columns - 1)); // Merge the band

    }


    ///---------- Group Heading Over Several Columns ----------
    private static void group(Sheet sheet, Row row, int first, int last, String text) {

        row.getCell(first).setCellValue(text); // Group label

        if (last > first) { // More than one column

            sheet.addMergedRegion(new CellRangeAddress(row.getRowNum(), row.getRowNum(), first, last)); // Merge the group only

        }

    }


    ///---------- Soft Red For Values Above Zero ----------
    private static void highlightPositive(Sheet sheet, int col, int firstRow, int lastRow) {

        if (lastRow < firstRow) { // No data rows
            return; // Nothing to colour
        }

        SheetConditionalFormatting formatting = sheet.getSheetConditionalFormatting(); // Conditional formats
        String letter = CellReference.convertNumToColString(col); // Column letter
        ConditionalFormattingRule rule = formatting.createConditionalFormattingRule("$" + letter + (firstRow + 1) + ">0"); // Value above zero
        PatternFormatting fill = rule.createPatternFormatting(); // Fill settings
        fill.setFillBackgroundColor(new XSSFColor(new byte[]{(byte) 0xFD, (byte) 0xE8, (byte) 0xE7}, null)); // Soft red
        fill.setFillPattern(PatternFormatting.SOLID_FOREGROUND); // Solid fill
        FontFormatting fontFormat = rule.createFontFormatting(); // Font settings
        fontFormat.setFontColorIndex(IndexedColors.DARK_RED.getIndex()); // Dark red text
        formatting.addConditionalFormatting(new CellRangeAddress[]{new CellRangeAddress(firstRow, lastRow, col, col)}, rule); // Data cells only

    }


    ///---------- Print Setup ----------
    // firstRepeat..headerRow = rows repeated on every page (headerRow -1 = none)
    private static void printSetup(Sheet sheet, int firstRepeat, int headerRow, int lastCol, int lastRow, int paper) {

        PrintSetup print = sheet.getPrintSetup(); // Print options
        print.setPaperSize((short) paper); // A3 or A4
        print.setLandscape(true); // Landscape
        print.setFitWidth((short) 1); // One page wide
        print.setFitHeight((short) 0); // Any pages tall
        sheet.setFitToPage(true); // Enable fit-to-page
        sheet.setHorizontallyCenter(true); // Center on page
        sheet.getWorkbook().setPrintArea(sheet.getWorkbook().getSheetIndex(sheet), 0, lastCol, 0, lastRow); // Print area

        if (firstRepeat >= 0) { // Repeat the heading rows
            sheet.setRepeatingRows(CellRangeAddress.valueOf((firstRepeat + 1) + ":" + (headerRow + 1))); // Repeat heading
        }

        sheet.getFooter().setCenter("NATIONAL BANK PLC | CONFIDENTIAL | Page &P of &N"); // Footer text

    }


    ///---------- Null-Safe Money ----------
    private static BigDecimal valueOrZero(BigDecimal value) {

        return value == null ? BigDecimal.ZERO : value; // Null becomes zero

    }


    ///---------- Write A Text Cell ----------
    // A null value is written as empty text (Excel does not like null cells)
    private static void writeText(Row row, int column, String value, CellStyle style) {

        Cell cell = row.createCell(column); // New cell
        cell.setCellValue(value == null ? "" : value); // Text or empty
        cell.setCellStyle(style); // Apply the look

    }


    ///---------- Write A Whole-Number Cell ----------
    private static void writeNumber(Row row, int column, int value, CellStyle style) {

        Cell cell = row.createCell(column); // New cell
        cell.setCellValue(value); // Number
        cell.setCellStyle(style); // Apply the look

    }


    ///---------- Write A Money Cell ----------
    // A null value is written as 0
    private static void writeMoney(Row row, int column, BigDecimal value, CellStyle style) {

        Cell cell = row.getCell(column) == null ? row.createCell(column) : row.getCell(column); // Reuse or create
        cell.setCellValue(valueOrZero(value).doubleValue()); // Excel stores money as a double
        cell.setCellStyle(style); // Apply the look

    }


    ///---------- Summary Sheet Lines ----------
    // Each helper writes one label + value row and returns the next row index

    private static int addSummaryText(Sheet sheet, int rowIndex, String label, String value, Look look) {

        Row row = summaryRow(sheet, rowIndex, label, look); // Label cell
        writeText(row, 1, value, look.body(Kind.CENTER, false)); // Text value

        return rowIndex + 1; // Next row

    }

    static int addSummaryCount(Sheet sheet, int rowIndex, String label, int value, Look look) {

        Row row = summaryRow(sheet, rowIndex, label, look); // Label cell
        writeNumber(row, 1, value, look.body(Kind.COUNT, false)); // Whole number

        return rowIndex + 1; // Next row

    }

    static int addSummaryMoney(Sheet sheet, int rowIndex, String label, BigDecimal value, Look look) {

        Row row = summaryRow(sheet, rowIndex, label, look); // Label cell
        writeMoney(row, 1, value, look.body(Kind.MONEY, false)); // BDT amount

        return rowIndex + 1; // Next row

    }

    static int addSummaryPercent(Sheet sheet, int rowIndex, String label, BigDecimal value, Look look) {

        Row row = summaryRow(sheet, rowIndex, label, look); // Label cell
        writeMoney(row, 1, value, look.percent); // Rate with % sign

        return rowIndex + 1; // Next row

    }

    private static Row summaryRow(Sheet sheet, int rowIndex, String label, Look look) {

        Row row = sheet.createRow(rowIndex); // New row
        row.setHeightInPoints(24); // Row height

        Cell labelCell = row.createCell(0); // Label cell
        labelCell.setCellValue(label); // Label text
        labelCell.setCellStyle(look.label); // Green label look

        return row; // Ready for the value

    }
}
