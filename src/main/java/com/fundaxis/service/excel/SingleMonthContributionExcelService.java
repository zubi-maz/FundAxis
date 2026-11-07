package com.fundaxis.service.excel; // Package of the Excel services

// The row and summary models, defined inside AdminContributionWorkspaceController
import com.fundaxis.controller.admin.AdminContributionWorkspaceController.SingleMonthRecord; // One table row
import com.fundaxis.controller.admin.AdminContributionWorkspaceController.SingleMonthSummary; // Totals of ALL records
// Apache POI: reads and writes Excel files (Workbook, Sheet, Row, Cell, CellStyle, Font ...)
import org.apache.poi.ss.usermodel.*; // POI sheet/cell/style types
import org.apache.poi.ss.util.CellRangeAddress; // Merge / filter ranges
import org.apache.poi.xssf.usermodel.XSSFCellStyle; // XLSX cell style
import org.apache.poi.xssf.usermodel.XSSFColor; // RGB colour
// XSSFWorkbook = the .xlsx file format
import org.apache.poi.xssf.usermodel.XSSFWorkbook; // XLSX workbook
// Spring class
import org.springframework.stereotype.Service; // Spring service bean

import java.io.ByteArrayOutputStream; // In-memory output
import java.io.IOException; // Write failure type
import java.math.BigDecimal; // Exact money type
import java.time.LocalDate; // Date without time
import java.time.ZoneId; // Time zone id
import java.time.ZonedDateTime; // Date-time with zone
import java.time.format.DateTimeFormatter; // Date formatting
import java.util.HashMap; // Style cache map
import java.util.List; // Record list
import java.util.Locale; // English month names
import java.util.Map; // Style cache type

///==================== Single Month Contribution Excel Service ====================///
// Dedicated XLSX formatter for Admin > Monthly Records > Single Month
// Creates one workbook with 2 sheets: "Monthly Records" and "Financial Summary"
// SECTIONS: MONTHLY (export) -> SHARED HELPERS (look, setText, setCount, setMoney)
// LOGIC IS UNCHANGED: same method, same inputs, same columns, same totals; only the look is new

// @Service = Spring creates one instance and injects it where needed
@Service // Register as Spring bean
public class SingleMonthContributionExcelService {

    ///==================== MONTHLY: Export ====================///

    ///---------- Build The Single Month Excel File ----------
    // Returns the file as bytes, the controller sends them to the browser as a download
    // month = the contribution month, records = the table rows, summary = the totals of ALL records
    public byte[] export(LocalDate month, List<SingleMonthRecord> records, SingleMonthSummary summary) {

        // All three inputs are required
        if (month == null || records == null || summary == null) { // Check inputs

            // Stop with a clear message
            throw new IllegalArgumentException("Month, records and summary are required."); // Tell the caller

        }

        // try-with-resources: the workbook and the output stream are closed automatically at the end
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {

            // ----- Look (all styles, created once) -----

            Look look = new Look(workbook); // Colours, fonts, borders

            // Month text used by both sheets, for example "October 2026"
            String monthText = month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)); // Full month
            // Generated line shown under the title
            String generated = "Currency: BDT  |  Generated (Dhaka): " + ZonedDateTime.now(ZoneId.of("Asia/Dhaka"))
                    .format(DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.ENGLISH))
                    + "  |  Internal / Confidential"; // Timestamp line

            // ----- Sheet 1: Monthly Records -----

            // Create the first sheet
            Sheet sheet = workbook.createSheet("Monthly Records"); // Sheet 1
            // Hide the grey grid lines for a clean look
            sheet.setDisplayGridlines(false); // Clean look
            // Column headings (16 columns)
            String[] headings = {"SL", "Employee ID", "Employee Name", "Designation",
                    "OLD Shares", "NEW Shares", "Total Shares",
                    "Monthly Contribution Due (BDT)", "Share Value (BDT)", "Amount Paid (BDT)", "Member Credit Used (BDT)",
                    "Total Contribution Settled (BDT)", "Remaining Due (BDT)", "Contribution Status",
                    "Payment Method", "Remarks"};
            // Rows 0 to 3: bank name, report title, month, currency line
            banner(sheet, 0, "NATIONAL BANK PLC", headings.length, look.title, 34); // Bank name
            banner(sheet, 1, "FUNDAXIS | SINGLE MONTH CONTRIBUTION RECORDS", headings.length, look.subtitle, 26); // Title
            banner(sheet, 2, "Contribution month: " + monthText, headings.length, look.info, 22); // Month
            banner(sheet, 3, generated, headings.length, look.info, 22); // Generated line
            // Row 4: heading row, a bit taller for wrapped text
            Row h = sheet.createRow(4); // Heading row
            h.setHeightInPoints(36); // Taller row
            // Write each heading with the header style
            for (int col = 0; col < headings.length; col++) { // Each heading

                // New cell in the heading row
                Cell cell = h.createCell(col); // Header cell
                // Heading text
                cell.setCellValue(headings[col]); // Same text as before
                // Bank green header look
                cell.setCellStyle(look.header); // Header style

            }

            // Data starts at row 5 (rows 0 to 4 are banner, month, currency and headings)
            int firstData = 5; // First data row
            int rowNo = firstData; // Row counter
            // One Excel row per record
            for (SingleMonthRecord r : records) { // Each record

                // Create the row, then move the counter to the next row
                Row row = sheet.createRow(rowNo++); // New row
                // Taller rows are easier to read
                row.setHeightInPoints(22); // Row height
                // Zebra stripe: every second row is light green
                boolean alt = (rowNo - 1 - firstData) % 2 == 1; // Stripe flag
                // Serial number 1, 2, 3 ... (rowNo is already one ahead, so subtract firstData)
                setCount(row, 0, rowNo - firstData, look.body(Kind.CENTER, alt)); // SL
                // Employee details
                setText(row, 1, r.employeeId(), look.body(Kind.CENTER, alt)); // Employee ID
                setText(row, 2, r.employeeFullName(), look.body(Kind.TEXT, alt)); // Name
                setText(row, 3, r.designation(), look.body(Kind.TEXT, alt)); // Designation
                // Share counts
                setCount(row, 4, r.oldShares(), look.body(Kind.COUNT, alt)); // OLD shares
                setCount(row, 5, r.newShares(), look.body(Kind.COUNT, alt)); // NEW shares
                setCount(row, 6, r.totalShares(), look.body(Kind.COUNT, alt)); // Total shares
                // Money columns (formatted with the money style)
                setMoney(row, 7, r.monthlyDue(), look.body(Kind.MONEY, alt)); // Monthly due
                if (r.contributionShareValue() == null) {
                    setText(row, 8, "—", look.body(Kind.MONEY, alt)); // Missing informational value
                } else {
                    setMoney(row, 8, r.contributionShareValue(), look.body(Kind.MONEY, alt)); // Informational value only
                }
                setMoney(row, 9, r.cashReceived(), look.body(Kind.MONEY, alt)); // Direct payment
                setMoney(row, 10, r.memberCreditApplied(), look.body(Kind.MONEY, alt)); // Credit applied
                setMoney(row, 11, r.totalPaid(), look.body(Kind.MONEY, alt)); // Total settled
                setMoney(row, 12, r.remainingDue(), look.body(Kind.MONEY, alt)); // Outstanding
                // Status and remark text (status gets its own colour)
                setText(row, 13, r.paymentStatus(), look.statusStyle(r.paymentStatus())); // Coloured status
                setText(row, 14, r.paymentMethod(), look.body(Kind.TEXT, alt)); // Active direct methods
                setText(row, 15, r.contributionRemarks() == null || r.contributionRemarks().isBlank()
                        ? "—" : r.contributionRemarks(), look.body(Kind.TEXT, alt)); // Administrator remarks

            }

            // The total row comes right after the last record
            int totalIndex = rowNo; // Total row index
            Row totals = sheet.createRow(totalIndex); // Total row
            totals.setHeightInPoints(28); // Row height
            // Every cell of the row gets the green total look first
            for (int col = 0; col < headings.length; col++) { // Each column

                totals.createCell(col).setCellStyle(look.totalText); // Green with border

            }
            // Label in the first cell, merged across the identity and share columns
            totals.getCell(0).setCellValue("MONTH TOTAL (ALL RECORDS)"); // Same label
            sheet.addMergedRegion(new CellRangeAddress(totalIndex, totalIndex, 0, 6)); // Merge A:G
            // Totals come from the summary object, in bold
            setMoney(totals, 7, summary.totalDue(), look.totalMoney); // Total due
            setMoney(totals, 9, summary.totalCash(), look.totalMoney); // Total cash
            setMoney(totals, 10, summary.totalCredit(), look.totalMoney); // Total credit
            setMoney(totals, 11, summary.totalSettled(), look.totalMoney); // Total settled
            setMoney(totals, 12, summary.totalRemaining(), look.totalMoney); // Total outstanding
            // A short note under the table explains the money columns
            Row note = sheet.createRow(totalIndex + 2); // Note row
            note.setHeightInPoints(32); // Room for wrapped text
            for (int col = 0; col < headings.length; col++) { // Each column

                note.createCell(col).setCellStyle(look.legend); // Plain note look

            }
            note.getCell(0).setCellValue("Monthly Contribution Due = this month's recorded obligation.  Total Contribution Settled = Amount Paid + "
                    + "Member Credit Used.  Remaining Due = Monthly Contribution Due - Total Contribution Settled.  "
                    + "Total row covers ALL records of the month."); // Plain-words note
            sheet.addMergedRegion(new CellRangeAddress(totalIndex + 2, totalIndex + 2, 0, headings.length - 1)); // Merge row
            // Freeze the banner + heading rows and the first 3 columns, so they stay visible while scrolling
            sheet.createFreezePane(3, firstData); // Freeze pane
            // Filter buttons on the heading row (the range covers the data rows, not the total row)
            sheet.setAutoFilter(new CellRangeAddress(4, Math.max(4, rowNo - 1), 0, headings.length - 1)); // Filter
            // Outstanding above zero turns soft red (follows edits in Excel)
            highlightPositive(sheet, 11, firstData, rowNo - 1); // Soft red highlight
            // Column widths in characters (multiplied by 256, the unit POI uses)
            int[] widths = {8, 18, 30, 24, 13, 13, 15, 29, 20, 20, 24, 30, 22, 20, 25, 30};
            // Apply each width
            for (int i = 0; i < widths.length; i++) { // Each column

                sheet.setColumnWidth(i, widths[i] * 256); // Width in 1/256 chars

            }
            // Print setup: A3 landscape, one page wide, heading repeated on each page
            printSetup(sheet, 4, 4, headings.length - 1, totalIndex + 2, PrintSetup.A3_PAPERSIZE); // Print layout

            // ----- Sheet 2: Financial Summary -----

            // Create the second sheet
            Sheet summarySheet = workbook.createSheet("Financial Summary"); // Sheet 2
            // Hide the grey grid lines
            summarySheet.setDisplayGridlines(false); // Clean look
            // Rows 0 to 3: bank name, report title, month, currency line
            banner(summarySheet, 0, "NATIONAL BANK PLC", 2, look.title, 34); // Bank name
            banner(summarySheet, 1, "FUNDAXIS | MONTHLY FINANCIAL SUMMARY", 2, look.subtitle, 26); // Title
            banner(summarySheet, 2, "Contribution month: " + monthText, 2, look.info, 22); // Month
            banner(summarySheet, 3, "Currency: BDT", 2, look.info, 22); // Currency line
            // Row 4: small heading row
            Row sh = summarySheet.createRow(4); // Heading row
            sh.setHeightInPoints(26); // Row height
            Cell shLabel = sh.createCell(0); // Left heading
            shLabel.setCellValue("Summary Item"); // Heading text
            shLabel.setCellStyle(look.header); // Header style
            Cell shValue = sh.createCell(1); // Right heading
            shValue.setCellValue("Value"); // Heading text
            shValue.setCellStyle(look.header); // Header style
            // Labels of the 10 summary lines (the order matches the switch below)
            String[] names = {"Contribution Records", "Total Monthly Due (BDT)",
                    "Direct Payment Received (BDT)", "Member Credit Applied (BDT)",
                    "Total Settled (BDT)", "Total Outstanding (BDT)",
                    "PAID Records", "PENDING Records", "CANCELED Records", "Settlement Rate (%)"}; // Same labels
            // Settlement rate = settled / due x 100 (zero when nothing is due, which avoids dividing by zero)
            BigDecimal percentage = summary.totalDue().signum() == 0 ? BigDecimal.ZERO : summary.totalSettled().multiply(BigDecimal.valueOf(100)).divide(summary.totalDue(), 2, java.math.RoundingMode.HALF_UP); // Same maths
            // One row per summary line, starting at row 5
            for (int i = 0; i < names.length; i++) { // Each line

                // Row for this line
                Row row = summarySheet.createRow(i + 5); // Summary row
                row.setHeightInPoints(24); // Row height
                // Left cell: the label, in the green label style
                Cell label = row.createCell(0); // Label cell
                label.setCellValue(names[i]); // Label text
                label.setCellStyle(look.label); // Label style
                // Right cell: the value
                Cell value = row.createCell(1); // Value cell
                // Pick the value by line number (the same order as the "names" array)
                switch (i) { // Which line

                    // Number of contribution records
                    case 0 -> { value.setCellValue(summary.totalMembers()); value.setCellStyle(look.body(Kind.COUNT, false)); } // Record count
                    // Money lines
                    case 1 -> setMoney(row, 1, summary.totalDue(), look.body(Kind.MONEY, false)); // Total due
                    case 2 -> setMoney(row, 1, summary.totalCash(), look.body(Kind.MONEY, false)); // Total cash
                    case 3 -> setMoney(row, 1, summary.totalCredit(), look.body(Kind.MONEY, false)); // Total credit
                    case 4 -> setMoney(row, 1, summary.totalSettled(), look.body(Kind.MONEY, false)); // Total settled
                    case 5 -> setMoney(row, 1, summary.totalRemaining(), look.body(Kind.MONEY, false)); // Outstanding
                    // Counts per status (each in its status colour)
                    case 6 -> { value.setCellValue(summary.paidCount()); value.setCellStyle(look.statusPaid); } // Paid count
                    case 7 -> { value.setCellValue(summary.pendingCount()); value.setCellStyle(look.statusPending); } // Pending count
                    case 8 -> { value.setCellValue(summary.canceledCount()); value.setCellStyle(look.statusCanceled); } // Canceled count
                    // Settlement rate (shown with 2 decimals, for example 85.50 %)
                    case 9 -> setMoney(row, 1, percentage, look.percent); // Rate
                    // Nothing else to do
                    default -> { }

                }

            }
            // Column widths of the summary sheet
            summarySheet.setColumnWidth(0, 40 * 256); // Label column
            summarySheet.setColumnWidth(1, 26 * 256); // Value column
            // Freeze the banner and heading rows
            summarySheet.createFreezePane(0, 5); // Freeze pane
            // Print setup: A4 portrait-friendly landscape, one page wide
            printSetup(summarySheet, -1, 4, 1, names.length + 4, PrintSetup.A4_PAPERSIZE); // Print layout

            // ----- Finish -----

            // Open on the first sheet
            workbook.setActiveSheet(0); // First sheet
            // Write the workbook into the output stream
            workbook.write(output); // Write the file
            // Return the .xlsx file as bytes
            return output.toByteArray(); // File bytes

        } catch (IOException exception) {

            // Writing failed: wrap the error in a clear message
            throw new IllegalStateException("Unable to generate single-month Excel report.", exception); // Same message

        }

    }


    ///==================== SHARED HELPERS ====================///

    ///---------- Kinds Of Body Cells ----------
    private enum Kind { TEXT, CENTER, COUNT, MONEY } // Left text, centered text, integer, BDT


    ///---------- Brand Colours (0xRRGGBB) ----------
    private static final int BRAND_GREEN = 0x024731; // Titles, headers, totals
    private static final int ROW_TINT = 0xF2F8F5; // Zebra stripe
    private static final int ROW_WHITE = 0xFFFFFF; // Normal row
    private static final int SOFT_GREEN = 0xDFF3E6; // Paid / info band
    private static final int SOFT_AMBER = 0xFFF3CD; // Pending
    private static final int SOFT_RED = 0xFDE8E7; // Canceled / outstanding


    ///---------- All Styles Of The Workbook ----------
    // One object holds every style, so the export code above stays short
    private static final class Look {

        final CellStyle title; // Bank name band
        final CellStyle subtitle; // Report title band
        final CellStyle info; // Month / currency band
        final CellStyle header; // Column headings
        final CellStyle label; // Summary labels
        final CellStyle legend; // Note under the table
        final CellStyle totalText; // Total row text
        final CellStyle totalMoney; // Total row money
        final CellStyle percent; // Settlement rate
        final CellStyle statusPaid; // PAID
        final CellStyle statusPending; // PENDING
        final CellStyle statusCanceled; // CANCELED

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

            statusPaid = style(workbook, bodyFont, SOFT_GREEN, true, true); // Soft green
            statusPending = style(workbook, bodyFont, SOFT_AMBER, true, true); // Soft amber
            statusCanceled = style(workbook, bodyFont, SOFT_RED, true, true); // Soft red

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

        // Status cell colour by text (unknown text keeps the normal look)
        CellStyle statusStyle(String status) { // Status style

            if ("PAID".equalsIgnoreCase(status)) { // Paid
                return statusPaid; // Green
            }
            if ("PENDING".equalsIgnoreCase(status)) { // Pending
                return statusPending; // Amber
            }
            if ("CANCELED".equalsIgnoreCase(status) || "CANCELLED".equalsIgnoreCase(status)) { // Canceled
                return statusCanceled; // Red
            }

            return body(Kind.CENTER, false); // Normal look

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


    ///---------- Soft Red For Values Above Zero ----------
    private static void highlightPositive(Sheet sheet, int col, int firstRow, int lastRow) {

        if (lastRow < firstRow) { // No data rows
            return; // Nothing to colour
        }

        SheetConditionalFormatting formatting = sheet.getSheetConditionalFormatting(); // Conditional formats
        String letter = org.apache.poi.ss.util.CellReference.convertNumToColString(col); // Column letter
        ConditionalFormattingRule rule = formatting.createConditionalFormattingRule("$" + letter + (firstRow + 1) + ">0"); // Value above zero
        PatternFormatting fill = rule.createPatternFormatting(); // Fill settings
        fill.setFillBackgroundColor(new XSSFColor(new byte[]{(byte) 0xFD, (byte) 0xE8, (byte) 0xE7}, null)); // Soft red
        fill.setFillPattern(PatternFormatting.SOLID_FOREGROUND); // Solid fill
        FontFormatting fontFormat = rule.createFontFormatting(); // Font settings
        fontFormat.setFontColorIndex(IndexedColors.DARK_RED.getIndex()); // Dark red text
        formatting.addConditionalFormatting(new CellRangeAddress[]{new CellRangeAddress(firstRow, lastRow, col, col)}, rule); // Data cells only

    }


    ///---------- Print Setup ----------
    // headerRow = row repeated on every page (-1 = none)
    private static void printSetup(Sheet sheet, int headerRow, int firstRepeat, int lastCol, int lastRow, int paper) {

        PrintSetup print = sheet.getPrintSetup(); // Print options
        print.setPaperSize((short) paper); // A3 or A4
        print.setLandscape(true); // Landscape
        print.setFitWidth((short) 1); // One page wide
        print.setFitHeight((short) 0); // Any pages tall
        sheet.setFitToPage(true); // Enable fit-to-page
        sheet.setHorizontallyCenter(true); // Center on page
        sheet.getWorkbook().setPrintArea(sheet.getWorkbook().getSheetIndex(sheet), 0, lastCol, 0, lastRow); // Print area

        if (headerRow >= 0) { // Repeat the heading rows
            sheet.setRepeatingRows(CellRangeAddress.valueOf((firstRepeat + 1) + ":" + (headerRow + 1))); // Repeat heading
        }

        sheet.getFooter().setCenter("NATIONAL BANK PLC | CONFIDENTIAL | Page &P of &N"); // Footer text

    }


    ///---------- Write A Text Cell ----------
    // A null value is written as empty text (Excel does not like null cells)
    private static void setText(Row row, int index, String value, CellStyle style) {

        // Create the cell and set its text
        Cell cell = row.createCell(index); // New cell
        cell.setCellValue(value == null ? "" : value); // Text or empty
        cell.setCellStyle(style); // Apply the look

    }


    ///---------- Write A Whole-Number Cell ----------
    // Accepts int, long or Integer values (they widen to double)
    private static void setCount(Row row, int index, double value, CellStyle style) {

        Cell cell = row.createCell(index); // New cell
        cell.setCellValue(value); // Number
        cell.setCellStyle(style); // Apply the look

    }


    ///---------- Write A Money Cell ----------
    // Reuses the cell if it already exists, a null value is written as 0
    private static void setMoney(Row row, int index, BigDecimal value, CellStyle style) {

        // Existing cell, or a new one when there is none yet
        Cell cell = row.getCell(index) == null ? row.createCell(index) : row.getCell(index); // Reuse or create
        // Excel stores money as a double
        cell.setCellValue(value == null ? 0.0 : value.doubleValue()); // Same conversion
        // Apply the money format
        cell.setCellStyle(style); // Apply the look

    }
}
