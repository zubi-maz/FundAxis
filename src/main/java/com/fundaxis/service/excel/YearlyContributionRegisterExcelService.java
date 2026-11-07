package com.fundaxis.service.excel; // Package of the Excel services

import com.fundaxis.service.ContributionReportingService.AnnualDetail; // One employee-month line
import com.fundaxis.service.ContributionReportingService.AnnualReport; // Totals + employee rows

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
import java.time.LocalDate; // Date without time
import java.time.YearMonth; // Year + month
import java.time.ZoneId; // Time zone id
import java.time.ZonedDateTime; // Date-time with zone
import java.time.format.DateTimeFormatter; // Date formatting
import java.util.HashMap; // Style cache map
import java.util.List; // Detail list
import java.util.Locale; // English month names
import java.util.Map; // Style cache type

///==================== Yearly Contribution Register Excel Service ====================///
// Generates the Yearly Records workbook exclusively
// Financial totals and eligible monthly records come from ContributionReportingService
// Creates one workbook with 3 sheets: "Annual Summary", "Employee Annual Register", "Monthly Breakdown"
// SECTIONS: YEARLY (export + 3 sheets) -> SHARED HELPERS (look, cell writers, sheet frame)
// SELF-CONTAINED: no monthly export service is injected, called or imported
// LOGIC IS UNCHANGED: same method, same inputs, same figures; only the look is new

@Service // Register as Spring bean
public class YearlyContributionRegisterExcelService {

    ///==================== YEARLY: Export ====================///

    ///---------- Build The Yearly Excel File ----------
    // Exports all filtered employees, independently of the displayed page
    // Returns the file as bytes, the controller sends them to the browser as a download
    public byte[] export(int year, String employeeId, String employeeName, String status,
                         AnnualReport report, List<AnnualDetail> details) {

        // try-with-resources: the workbook and the output stream are closed automatically at the end
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {

            Look look = new Look(workbook); // Colours, fonts, borders

            // Filter text shown under each title (empty filters read as "All")
            String filterText = "Employee ID: " + orAll(employeeId)
                    + "  |  Name: " + orAll(employeeName)
                    + "  |  Status: " + orAll(status); // Same three filters as before

            writeSummary(workbook, look, year, filterText, report); // Sheet 1
            writeRegister(workbook, look, year, report); // Sheet 2
            writeMonthlyBreakdown(workbook, look, year, details); // Sheet 3

            workbook.setActiveSheet(0); // Open on the first sheet

            workbook.write(output); // Write the file

            return output.toByteArray(); // Return the .xlsx bytes

        } catch (IOException exception) {

            throw new IllegalStateException("Unable to generate annual workbook.", exception); // Same message

        }

    }


    ///---------- Sheet 1: Annual Summary ----------
    private void writeSummary(XSSFWorkbook workbook, Look look, int year,
                              String filterText, AnnualReport report) {

        Sheet sheet = workbook.createSheet("Annual Summary"); // Sheet 1
        sheet.setDisplayGridlines(false); // Clean look

        banners(sheet, "ANNUAL CONTRIBUTION SUMMARY - " + year, filterText, 3, look); // Top block

        headerRow(sheet, 4, look, "Summary Item", "Value", "What It Means"); // Heading row

        int row = 5; // First summary line

        row = summaryCount(sheet, row, "Total employees (filtered)", report.allRows().size(),
                "Employees matching the selected filters", look); // Employees
        row = summaryCount(sheet, row, "Active contributors (current ACTIVE with obligations)",
                (int) report.activeContributors(),
                "Currently ACTIVE employees that have contribution obligations", look); // Contributors
        row = summaryMoney(sheet, row, "Expected (BDT)", report.expected(),
                "Total contribution due for the year (canceled excluded)", look); // Expected
        row = summaryMoney(sheet, row, "Net cash received (BDT)", report.collected(),
                "Direct cash received against the year's contributions", look); // Cash
        row = summaryMoney(sheet, row, "Credit applied (BDT)", report.creditApplied(),
                "Member credit applied against the year's contributions", look); // Credit
        row = summaryMoney(sheet, row, "Total settled (BDT)",
                toDecimal(report.collected()).add(toDecimal(report.creditApplied())),
                "Net cash received + credit applied", look); // Settled
        row = summaryMoney(sheet, row, "Outstanding (BDT)", report.outstanding(),
                "Amount still unpaid", look); // Outstanding
        row = summaryPercent(sheet, row, "Cash collection rate", report.rate(),
                "Cash collection as a percentage of the expected amount", look); // Rate

        sheet.setColumnWidth(0, 58 * 256); // Item column
        sheet.setColumnWidth(1, 28 * 256); // Value column
        sheet.setColumnWidth(2, 62 * 256); // Meaning column

        sheet.createFreezePane(0, 5); // Freeze banner + heading

        printSetup(sheet, 4, 4, 2, row, PrintSetup.A4_PAPERSIZE); // Print layout

    }


    ///---------- Sheet 2: Employee Annual Register ----------
    private void writeRegister(XSSFWorkbook workbook, Look look, int year, AnnualReport report) {

        Sheet sheet = workbook.createSheet("Employee Annual Register"); // Sheet 2
        sheet.setDisplayGridlines(false); // Clean look

        int columns = 11; // Column count
        int groupRowIndex = 4; // Group heading row
        int headerRowIndex = 5; // Column heading row
        int firstData = 6; // First employee row

        banners(sheet, "ANNUAL EMPLOYEE REGISTER - " + year,
                "Complete filtered dataset; contribution-period basis, current settlements",
                columns, look); // Top block

        Row group = sheet.createRow(groupRowIndex); // Group row
        group.setHeightInPoints(24); // Row height
        for (int i = 0; i < columns; i++) { // Style every cell

            group.createCell(i).setCellStyle(look.group); // Green with border

        }
        group(sheet, group, 0, 3, "EMPLOYEE INFORMATION"); // SL to Designation
        group(sheet, group, 4, 8, "YEARLY CONTRIBUTION (BDT)"); // Five money columns
        group(sheet, group, 9, 10, "COLLECTION"); // Rate and status

        headerRow(sheet, headerRowIndex, look, "SL No.", "Employee ID", "Employee Full Name", "Designation",
                "Expected (BDT)", "Cash Received (BDT)", "Credit Applied (BDT)", "Total Settled (BDT)",
                "Outstanding (BDT)", "Collection Rate (%)", "Status"); // Same headings + SL

        int row = firstData; // Row counter
        int serial = 1; // SL counter

        for (var employee : report.allRows()) { // Every filtered employee

            boolean alt = serial % 2 == 0; // Zebra stripe flag

            Row r = sheet.createRow(row++); // New row
            r.setHeightInPoints(23); // Row height

            writeNumber(r, 0, serial++, look.body(Kind.COUNT, alt)); // SL No.
            writeText(r, 1, employee.employeeId(), look.body(Kind.CENTER, alt)); // Employee ID
            writeText(r, 2, employee.fullName(), look.body(Kind.TEXT, alt)); // Name
            writeText(r, 3, employee.designation(), look.body(Kind.TEXT, alt)); // Designation
            writeMoney(r, 4, employee.expected(), look.body(Kind.MONEY, alt)); // Expected
            writeMoney(r, 5, employee.collected(), look.body(Kind.MONEY, alt)); // Cash
            writeMoney(r, 6, employee.creditApplied(), look.body(Kind.MONEY, alt)); // Credit
            writeMoney(r, 7, employee.totalSettled(), look.body(Kind.MONEY, alt)); // Settled
            writeMoney(r, 8, employee.outstanding(), look.body(Kind.MONEY, alt)); // Outstanding
            writeMoney(r, 9, employee.rate(), look.body(Kind.MONEY, alt)); // Rate
            writeText(r, 10, String.valueOf(employee.status()), look.statusStyle(String.valueOf(employee.status()), alt)); // Status

        }

        int totalIndex = row; // Total row index

        Row total = sheet.createRow(totalIndex); // Total row
        total.setHeightInPoints(28); // Row height
        for (int i = 0; i < columns; i++) { // Green total look first

            total.createCell(i).setCellStyle(look.totalText); // Green with border

        }
        total.getCell(0).setCellValue("TOTAL"); // Same label
        sheet.addMergedRegion(new CellRangeAddress(totalIndex, totalIndex, 0, 3)); // Merge A:D
        writeMoney(total, 4, report.expected(), look.totalMoney); // Expected total
        writeMoney(total, 5, report.collected(), look.totalMoney); // Cash total
        writeMoney(total, 6, report.creditApplied(), look.totalMoney); // Credit total
        writeMoney(total, 7, toDecimal(report.collected()).add(toDecimal(report.creditApplied())), look.totalMoney); // Settled total
        writeMoney(total, 8, report.outstanding(), look.totalMoney); // Outstanding total
        writeMoney(total, 9, report.rate(), look.totalMoney); // Rate

        legend(sheet, totalIndex + 2, columns, look,
                "Total Settled = Cash Received + Credit Applied.  Outstanding above zero is shown in soft red.  "
                        + "Use the filter buttons on the heading row to sort or narrow the list."); // Plain-words note

        finishSheet(sheet, new int[]{8, 17, 34, 26, 21, 21, 21, 21, 21, 19, 16}, 3,
                groupRowIndex, headerRowIndex, firstData, row - 1, totalIndex + 2, 8, PrintSetup.A4_PAPERSIZE); // Freeze, filter, print

    }


    ///---------- Sheet 3: Monthly Breakdown ----------
    private void writeMonthlyBreakdown(XSSFWorkbook workbook, Look look, int year,
                                       List<AnnualDetail> details) {

        Sheet sheet = workbook.createSheet("Monthly Breakdown"); // Sheet 3
        sheet.setDisplayGridlines(false); // Clean look

        int columns = 9; // Column count
        int headerRowIndex = 4; // Column heading row
        int firstData = 5; // First detail row

        banners(sheet, "MONTHLY BREAKDOWN - " + year,
                "Missing months are NO RECORD. Canceled obligations are excluded from financial totals.",
                columns, look); // Top block

        headerRow(sheet, headerRowIndex, look, "SL No.", "Employee ID", "Employee Full Name", "Contribution Month",
                "Expected (BDT)", "Cash Received (BDT)", "Credit Applied (BDT)", "Outstanding (BDT)", "Status"); // Same headings + SL

        int row = firstData; // Row counter
        int serial = 1; // SL counter

        for (var detail : details) { // Every employee-month line

            var month = detail.month(); // The month figures

            boolean alt = serial % 2 == 0; // Zebra stripe flag

            Row r = sheet.createRow(row++); // New row
            r.setHeightInPoints(23); // Row height

            writeNumber(r, 0, serial++, look.body(Kind.COUNT, alt)); // SL No.
            writeText(r, 1, detail.employeeId(), look.body(Kind.CENTER, alt)); // Employee ID
            writeText(r, 2, detail.fullName(), look.body(Kind.TEXT, alt)); // Name
            writeText(r, 3, monthText(month.month()), look.body(Kind.CENTER, alt)); // Month
            writeMoney(r, 4, month.expected(), look.body(Kind.MONEY, alt)); // Expected
            writeMoney(r, 5, month.collected(), look.body(Kind.MONEY, alt)); // Cash
            writeMoney(r, 6, month.creditApplied(), look.body(Kind.MONEY, alt)); // Credit
            writeMoney(r, 7, month.outstanding(), look.body(Kind.MONEY, alt)); // Outstanding
            writeText(r, 8, String.valueOf(month.status()), look.statusStyle(String.valueOf(month.status()), alt)); // Status

        }

        legend(sheet, row + 1, columns, look,
                "One row per employee and contribution month.  NO RECORD = no contribution due for that month.  "
                        + "Outstanding above zero is shown in soft red."); // Plain-words note

        finishSheet(sheet, new int[]{8, 17, 34, 20, 21, 21, 21, 21, 16}, 3,
                -1, headerRowIndex, firstData, row - 1, row + 1, 7, PrintSetup.A4_PAPERSIZE); // Freeze, filter, print

    }


    ///==================== SHARED HELPERS ====================///

    ///---------- Kinds Of Body Cells ----------
    private enum Kind { TEXT, CENTER, COUNT, MONEY } // Left text, centered text, integer, BDT


    ///---------- Brand Colours (0xRRGGBB) ----------
    private static final int BRAND_GREEN = 0x024731; // Titles, headers, totals
    private static final int ROW_TINT = 0xF2F8F5; // Zebra stripe
    private static final int ROW_WHITE = 0xFFFFFF; // Normal row
    private static final int SOFT_GREEN = 0xDFF3E6; // Info band / good status
    private static final int SOFT_AMBER = 0xFFF3CD; // Pending status
    private static final int SOFT_RED = 0xFDE8E7; // Canceled status
    private static final int SOFT_GREY = 0xE9E9E9; // No record / inactive


    ///---------- All Styles Of The Workbook ----------
    // One object holds every style, so the export code above stays short
    private static final class Look {

        final CellStyle title; // Bank name band
        final CellStyle subtitle; // Report title band
        final CellStyle info; // Filter / generated band
        final CellStyle group; // Group heading
        final CellStyle header; // Column headings
        final CellStyle label; // Summary labels
        final CellStyle note; // Summary meaning text
        final CellStyle legend; // Note under the table
        final CellStyle totalText; // Total row text
        final CellStyle totalMoney; // Total row money
        final CellStyle percent; // Collection rate

        private final XSSFWorkbook workbook; // Owning workbook
        private final Font bodyFont; // Normal font
        private final Map<String, CellStyle> cache = new HashMap<>(); // Reused styles

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

            note = style(workbook, bodyFont, ROW_WHITE, false, true); // Meaning text, bordered
            note.setWrapText(true); // Wrap long text

            legend = style(workbook, bodyFont, ROW_WHITE, false, false); // Note, no border
            legend.setWrapText(true); // Wrap long note

            totalText = style(workbook, totalFont, BRAND_GREEN, true, true); // Total text
            totalMoney = style(workbook, totalFont, BRAND_GREEN, true, true); // Total money
            totalMoney.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00")); // 1,234.50

            percent = style(workbook, bodyFont, ROW_WHITE, true, true); // Rate cell
            percent.setDataFormat(workbook.createDataFormat().getFormat("0.00\"%\"")); // 85.50 %

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

        // Status cell colour by text (unknown text keeps the zebra look)
        CellStyle statusStyle(String status, boolean alternate) { // Status style

            String text = status == null ? "" : status.trim().toUpperCase(Locale.ENGLISH); // Clean text

            int rgb; // Chosen colour

            if (text.equals("PAID") || text.equals("ACTIVE")) { // Good
                rgb = SOFT_GREEN; // Soft green
            } else if (text.equals("PENDING")) { // Waiting
                rgb = SOFT_AMBER; // Soft amber
            } else if (text.equals("CANCELED") || text.equals("CANCELLED")) { // Canceled
                rgb = SOFT_RED; // Soft red
            } else if (text.equals("NO_RECORD") || text.equals("NO RECORD") || text.equals("INACTIVE")) { // Nothing / inactive
                rgb = SOFT_GREY; // Soft grey
            } else { // Anything else
                return body(Kind.CENTER, alternate); // Normal look
            }

            return cache.computeIfAbsent("status|" + rgb, k -> style(workbook, bodyFont, rgb, true, true)); // Create once

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


    ///---------- Top Block Of Every Sheet (Rows 0 To 3) ----------
    private static void banners(Sheet sheet, String reportName, String detailLine, int columns, Look look) {

        String generated = "Currency: BDT  |  Generated (Dhaka): " + ZonedDateTime.now(ZoneId.of("Asia/Dhaka"))
                .format(DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.ENGLISH))
                + "  |  Internal / Confidential"; // Timestamp line

        banner(sheet, 0, "NATIONAL BANK PLC | FUNDAXIS", columns, look.title, 34); // Bank name
        banner(sheet, 1, reportName, columns, look.subtitle, 26); // Report title
        banner(sheet, 2, detailLine, columns, look.info, 22); // Filters / basis
        banner(sheet, 3, generated, columns, look.info, 22); // Generated line

    }


    ///---------- Merged Banner Row ----------
    private static void banner(Sheet sheet, int rowIndex, String text, int columns, CellStyle style, int height) {

        Row row = sheet.createRow(rowIndex); // New row
        row.setHeightInPoints(height); // Row height

        for (int col = 0; col < columns; col++) { // Style every cell

            row.createCell(col).setCellStyle(style); // Band colour

        }

        row.getCell(0).setCellValue(text); // Text in the first cell

        if (columns > 1) { // More than one column
            sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 0, columns - 1)); // Merge the band
        }

    }


    ///---------- Group Heading Over Several Columns ----------
    private static void group(Sheet sheet, Row row, int first, int last, String text) {

        row.getCell(first).setCellValue(text); // Group label

        if (last > first) { // More than one column

            sheet.addMergedRegion(new CellRangeAddress(row.getRowNum(), row.getRowNum(), first, last)); // Merge the group only

        }

    }


    ///---------- Column Heading Row ----------
    private static void headerRow(Sheet sheet, int rowIndex, Look look, String... headings) {

        Row row = sheet.createRow(rowIndex); // Heading row
        row.setHeightInPoints(rowIndex == 4 && headings.length == 3 ? 26 : 42); // Summary heading is slimmer

        for (int i = 0; i < headings.length; i++) { // Each heading

            Cell cell = row.createCell(i); // New cell
            cell.setCellValue(headings[i]); // Heading text
            cell.setCellStyle(look.header); // Header style

        }

    }


    ///---------- Note Under A Table ----------
    private static void legend(Sheet sheet, int rowIndex, int columns, Look look, String text) {

        Row row = sheet.createRow(rowIndex); // Note row
        row.setHeightInPoints(32); // Room for wrapped text

        for (int col = 0; col < columns; col++) { // Plain note look

            row.createCell(col).setCellStyle(look.legend); // No border

        }

        row.getCell(0).setCellValue(text); // Note text

        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 0, columns - 1)); // Merge row

    }


    ///---------- Finish A Table Sheet ----------
    // groupRow = -1 when the sheet has no group heading row, moneyCol = Outstanding column (soft red when above zero)
    private static void finishSheet(Sheet sheet, int[] widths, int freezeColumns, int groupRow, int headerRow,
                                    int firstData, int lastData, int lastUsedRow, int moneyCol, int paper) {

        for (int i = 0; i < widths.length; i++) { // Each column

            sheet.setColumnWidth(i, widths[i] * 256); // Width in 1/256 chars

        }

        sheet.createFreezePane(freezeColumns, firstData); // Freeze headings + identity columns

        sheet.setAutoFilter(new CellRangeAddress(headerRow, Math.max(headerRow, lastData), 0, widths.length - 1)); // Filter, not the total row

        highlightPositive(sheet, moneyCol, firstData, lastData); // Outstanding above zero turns soft red

        printSetup(sheet, groupRow >= 0 ? groupRow : headerRow, headerRow, widths.length - 1, lastUsedRow, paper); // Print layout

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
    // firstRepeat..headerRow = rows repeated on every page
    private static void printSetup(Sheet sheet, int firstRepeat, int headerRow, int lastCol, int lastRow, int paper) {

        PrintSetup print = sheet.getPrintSetup(); // Print options
        print.setPaperSize((short) paper); // A3 or A4
        print.setLandscape(true); // Landscape
        print.setFitWidth((short) 1); // One page wide
        print.setFitHeight((short) 0); // Any pages tall
        sheet.setFitToPage(true); // Enable fit-to-page
        sheet.setHorizontallyCenter(true); // Center on page
        sheet.getWorkbook().setPrintArea(sheet.getWorkbook().getSheetIndex(sheet), 0, lastCol, 0, lastRow); // Print area
        sheet.setRepeatingRows(CellRangeAddress.valueOf((firstRepeat + 1) + ":" + (headerRow + 1))); // Repeat heading
        sheet.getFooter().setCenter("NATIONAL BANK PLC | CONFIDENTIAL | Page &P of &N"); // Footer text

    }


    ///---------- Small Value Helpers ----------

    // Empty filter text reads as "All"
    private static String orAll(String value) {

        return value == null || value.isBlank() ? "All" : value.trim(); // Show All when empty

    }

    // Any number-like value becomes a BigDecimal (null becomes zero)
    private static BigDecimal toDecimal(Object value) {

        if (value == null) { // Nothing there
            return BigDecimal.ZERO; // Zero
        }

        if (value instanceof BigDecimal decimal) { // Already exact
            return decimal; // Keep it
        }

        try {
            return new BigDecimal(value.toString()); // Parse from text
        } catch (NumberFormatException ignored) {
            return BigDecimal.ZERO; // Not a number
        }

    }

    // Month value (YearMonth, LocalDate or text such as 2026-10) shown as "Oct 2026"
    private static String monthText(Object month) {

        DateTimeFormatter format = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH); // e.g. Oct 2026

        if (month == null) { // Nothing there
            return ""; // Empty
        }

        if (month instanceof YearMonth yearMonth) { // YearMonth
            return yearMonth.format(format); // Oct 2026
        }

        if (month instanceof LocalDate date) { // LocalDate
            return date.format(format); // Oct 2026
        }

        try {
            return YearMonth.parse(month.toString()).format(format); // Text like 2026-10
        } catch (RuntimeException ignored) {
            return month.toString(); // Show as it is
        }

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
    private static void writeMoney(Row row, int column, Object value, CellStyle style) {

        Cell cell = row.getCell(column) == null ? row.createCell(column) : row.getCell(column); // Reuse or create
        cell.setCellValue(toDecimal(value).doubleValue()); // Excel stores money as a double
        cell.setCellStyle(style); // Apply the look

    }


    ///---------- Summary Sheet Lines ----------
    // Each helper writes label + value + meaning and returns the next row index

    private static int summaryCount(Sheet sheet, int rowIndex, String label, int value, String meaning, Look look) {

        Row row = summaryRow(sheet, rowIndex, label, meaning, look); // Label + meaning
        writeNumber(row, 1, value, look.body(Kind.COUNT, false)); // Whole number

        return rowIndex + 1; // Next row

    }

    private static int summaryMoney(Sheet sheet, int rowIndex, String label, Object value, String meaning, Look look) {

        Row row = summaryRow(sheet, rowIndex, label, meaning, look); // Label + meaning
        writeMoney(row, 1, value, look.body(Kind.MONEY, false)); // BDT amount

        return rowIndex + 1; // Next row

    }

    private static int summaryPercent(Sheet sheet, int rowIndex, String label, Object value, String meaning, Look look) {

        Row row = summaryRow(sheet, rowIndex, label, meaning, look); // Label + meaning
        writeMoney(row, 1, value, look.percent); // Rate with % sign

        return rowIndex + 1; // Next row

    }

    private static Row summaryRow(Sheet sheet, int rowIndex, String label, String meaning, Look look) {

        Row row = sheet.createRow(rowIndex); // New row
        row.setHeightInPoints(26); // Row height

        Cell labelCell = row.createCell(0); // Label cell
        labelCell.setCellValue(label); // Label text
        labelCell.setCellStyle(look.label); // Green label look

        Cell meaningCell = row.createCell(2); // Meaning cell
        meaningCell.setCellValue(meaning); // Plain-words meaning
        meaningCell.setCellStyle(look.note); // Bordered note look

        return row; // Ready for the value

    }
}