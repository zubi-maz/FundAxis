package com.fundaxis.service.excel;

import com.fundaxis.entity.Employee;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates the FundAxis Employee Master List as a print-ready XLSX report.
 *
 * Notes:
 *  - Column widths are fixed on purpose. sheet.autoSizeColumn() depends on AWT/fontconfig
 *    and breaks or mis-sizes in headless containers (Docker/K8s).
 *  - Dates are written as real Excel dates (not strings) so sorting/filtering works.
 */
@Service
public class EmployeeExcelService {

    // Regex pattern to extract Designation inside brackets e.g. (EO), (FEO), (AVP)
    private static final Pattern DESIGNATION_PATTERN = Pattern.compile("\\((FEO|AVP|EO|JO|SAVP|SPO|PO)\\)", Pattern.CASE_INSENSITIVE);

    private static final String BANK_NAME = "NATIONAL BANK PLC";
    private static final String REPORT_TITLE = "FundAxis  |  Employee Master List";
    private static final ZoneId BANK_ZONE = ZoneId.of("Asia/Dhaka");
    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private static final String[] COLUMNS = {
            "SL", "Employee ID", "Full Name", "Designation", "Status", "Fund Join Date", "Deactivation Reason"
    };
    // Width in characters
    private static final int[] WIDTHS = {7, 16, 34, 28, 14, 18, 40};

    private static final int HEADER_ROW = 5;
    private static final int FIRST_DATA_ROW = 6;

    // Palette
    private static final XSSFColor GREEN_DARK = rgb(2, 71, 49);      // NBL brand green
    private static final XSSFColor GREEN_LIGHT = rgb(226, 239, 232);
    private static final XSSFColor GOLD = rgb(191, 144, 0);
    private static final XSSFColor ZEBRA = rgb(244, 248, 246);
    private static final XSSFColor BORDER = rgb(166, 166, 166);
    private static final XSSFColor WHITE = rgb(255, 255, 255);
    private static final XSSFColor TEXT = rgb(33, 33, 33);
    private static final XSSFColor OK_GREEN = rgb(0, 118, 56);
    private static final XSSFColor ALERT_RED = rgb(192, 0, 0);
    private static final XSSFColor GREY_TEXT = rgb(110, 110, 110);

    /** Backward-compatible: no period selected -> "All Dates". */
    public ByteArrayInputStream exportEmployeesToExcel(List<Employee> employees) throws IOException {
        return exportEmployeesToExcel(employees, null, null);
    }

    /**
     * @param startDate selected Fund Join Date range start (inclusive), nullable
     * @param endDate   selected Fund Join Date range end (inclusive), nullable
     *                  The range is printed in the report banner. The list passed in is
     *                  expected to be already filtered by the same range.
     */
    public ByteArrayInputStream exportEmployeesToExcel(List<Employee> employees,
                                                       LocalDate startDate, LocalDate endDate) throws IOException {

        String periodText = periodLabel(startDate, endDate);

        long active = employees.stream()
                .filter(e -> e.getEmployeeStatus() != null && "ACTIVE".equalsIgnoreCase(e.getEmployeeStatus().name()))
                .count();
        long total = employees.size();
        long inactive = total - active;

        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            XSSFSheet sheet = wb.createSheet("Employee List");
            sheet.setDisplayGridlines(false);
            for (int i = 0; i < WIDTHS.length; i++) {
                sheet.setColumnWidth(i, WIDTHS[i] * 256);
            }
            int lastCol = COLUMNS.length - 1;

            // Document properties
            wb.getProperties().getCoreProperties().setTitle("FundAxis Employee Master List");
            wb.getProperties().getCoreProperties().setCreator("National Bank PLC - FundAxis");

            // ---------- Styles ----------
            XSSFCellStyle bankStyle = style(wb, font(wb, 20, true, WHITE), GREEN_DARK,
                    HorizontalAlignment.CENTER, false, false);
            XSSFCellStyle subtitleStyle = style(wb, font(wb, 13, true, WHITE), GREEN_DARK,
                    HorizontalAlignment.CENTER, false, false);
            XSSFCellStyle metaStyle = style(wb, font(wb, 10, false, TEXT), GREEN_LIGHT,
                    HorizontalAlignment.LEFT, false, false);
            metaStyle.setIndention((short) 1);

            XSSFCellStyle headerStyle = style(wb, font(wb, 11, true, WHITE), GREEN_DARK,
                    HorizontalAlignment.CENTER, true, true);
            // Gold accent under the header row
            headerStyle.setBorderBottom(BorderStyle.MEDIUM);
            headerStyle.setBottomBorderColor(GOLD);

            XSSFFont bodyFont = font(wb, 10, false, TEXT);
            XSSFCellStyle[] center = new XSSFCellStyle[2];
            XSSFCellStyle[] left = new XSSFCellStyle[2];
            XSSFCellStyle[] wrap = new XSSFCellStyle[2];
            XSSFCellStyle[] date = new XSSFCellStyle[2];
            XSSFCellStyle[] statusOk = new XSSFCellStyle[2];
            XSSFCellStyle[] statusBad = new XSSFCellStyle[2];
            short dateFmt = wb.createDataFormat().getFormat("dd-mmm-yyyy");
            for (int z = 0; z < 2; z++) {
                XSSFColor fill = (z == 1) ? ZEBRA : WHITE;
                center[z] = style(wb, bodyFont, fill, HorizontalAlignment.CENTER, true, false);
                left[z] = style(wb, bodyFont, fill, HorizontalAlignment.LEFT, true, false);
                left[z].setIndention((short) 1);
                wrap[z] = style(wb, bodyFont, fill, HorizontalAlignment.LEFT, true, true);
                wrap[z].setIndention((short) 1);
                date[z] = style(wb, bodyFont, fill, HorizontalAlignment.CENTER, true, false);
                date[z].setDataFormat(dateFmt);
                statusOk[z] = style(wb, font(wb, 10, true, OK_GREEN), fill, HorizontalAlignment.CENTER, true, false);
                statusBad[z] = style(wb, font(wb, 10, true, ALERT_RED), fill, HorizontalAlignment.CENTER, true, false);
            }

            XSSFCellStyle totalValue = style(wb, font(wb, 10, true, WHITE), GREEN_DARK,
                    HorizontalAlignment.LEFT, false, false);
            totalValue.setIndention((short) 1);
            XSSFFont noteFont = font(wb, 9, false, GREY_TEXT);
            noteFont.setItalic(true);
            XSSFCellStyle noteStyle = style(wb, noteFont, WHITE, HorizontalAlignment.LEFT, false, false);

            // ---------- Banner ----------
            banner(sheet, 0, 34, BANK_NAME, bankStyle, lastCol);
            banner(sheet, 1, 24, REPORT_TITLE, subtitleStyle, lastCol);

            // Selected period (from the UI filter) - prominent, so management sees it at a glance
            XSSFFont periodFont = font(wb, 12, true, GREEN_DARK);
            XSSFCellStyle periodStyle = style(wb, periodFont, GREEN_LIGHT,
                    HorizontalAlignment.CENTER, false, false);
            periodStyle.setBorderBottom(BorderStyle.MEDIUM);
            periodStyle.setBottomBorderColor(GOLD);
            banner(sheet, 2, 24, "Reporting Period (Fund Join Date):  " + periodText, periodStyle, lastCol);

            String generated = ZonedDateTime.now(BANK_ZONE)
                    .format(DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.ENGLISH));
            banner(sheet, 3, 20,
                    "Generated On: " + generated
                            + "     |     Total Employees: " + total
                            + "     |     Active: " + active
                            + "     |     Inactive: " + inactive,
                    metaStyle, lastCol);
            sheet.createRow(4).setHeightInPoints(8);

            // ---------- Header ----------
            Row header = sheet.createRow(HEADER_ROW);
            header.setHeightInPoints(28);
            for (int i = 0; i < COLUMNS.length; i++) {
                Cell c = header.createCell(i);
                c.setCellValue(COLUMNS[i]);
                c.setCellStyle(headerStyle);
            }

            // ---------- Data ----------
            int rowIdx = FIRST_DATA_ROW;
            int sl = 1;
            for (Employee emp : employees) {
                int z = (sl % 2 == 0) ? 1 : 0;
                Row row = sheet.createRow(rowIdx++);
                row.setHeightInPoints(21);

                Cell c0 = row.createCell(0);
                c0.setCellValue(sl++);
                c0.setCellStyle(center[z]);

                Cell c1 = row.createCell(1);
                c1.setCellValue(nvl(emp.getEmployeeId()));
                c1.setCellStyle(center[z]);

                Cell c2 = row.createCell(2);
                c2.setCellValue(nvl(emp.getFullName()));
                c2.setCellStyle(left[z]);

                Cell c3 = row.createCell(3);
                c3.setCellValue(emp.getEmployeeDesignation() != null ? emp.getEmployeeDesignation().name() : "-");
                c3.setCellStyle(left[z]);

                Cell c4 = row.createCell(4);
                String status = emp.getEmployeeStatus() != null ? emp.getEmployeeStatus().name() : "";
                c4.setCellValue(pretty(status));
                c4.setCellStyle("ACTIVE".equalsIgnoreCase(status) ? statusOk[z] : statusBad[z]);

                Cell c5 = row.createCell(5);
                if (emp.getFundJoinDate() != null) {
                    c5.setCellValue(Date.from(emp.getFundJoinDate().atStartOfDay(BANK_ZONE).toInstant()));
                }
                c5.setCellStyle(date[z]);

                Cell c6 = row.createCell(6);
                String reason = emp.getDeactivationReason();
                c6.setCellValue(reason != null && !reason.isBlank() ? reason : "-");
                c6.setCellStyle(wrap[z]);
            }

            // ---------- Footer block ----------
            int lastData = rowIdx - 1;
            Row totalRow = sheet.createRow(rowIdx++);
            totalRow.setHeightInPoints(22);
            for (int i = 0; i <= lastCol; i++) {
                totalRow.createCell(i).setCellStyle(totalValue);
            }
            totalRow.getCell(0).setCellValue("Total Employees: " + total
                    + "   (Active: " + active + "  |  Inactive: " + inactive + ")");
            sheet.addMergedRegion(new CellRangeAddress(totalRow.getRowNum(), totalRow.getRowNum(), 0, lastCol));

            sheet.createRow(rowIdx++).setHeightInPoints(6);
            banner(sheet, rowIdx, 16,
                    "CONFIDENTIAL - For internal use of National Bank PLC only. "
                            + "This is a system-generated report from FundAxis; no signature is required.",
                    noteStyle, lastCol);

            // ---------- Usability ----------
            sheet.createFreezePane(0, FIRST_DATA_ROW);
            if (lastData >= FIRST_DATA_ROW) {
                sheet.setAutoFilter(new CellRangeAddress(HEADER_ROW, lastData, 0, lastCol));
            }

            // ---------- Print setup (A4 landscape, fit to 1 page wide) ----------
            PrintSetup ps = sheet.getPrintSetup();
            ps.setPaperSize(PrintSetup.A4_PAPERSIZE);
            ps.setLandscape(true);
            ps.setFitWidth((short) 1);
            ps.setFitHeight((short) 0);
            sheet.setFitToPage(true);
            sheet.setHorizontallyCenter(true);
            sheet.setRepeatingRows(CellRangeAddress.valueOf((HEADER_ROW + 1) + ":" + (HEADER_ROW + 1)));
            sheet.getHeader().setCenter("&B" + BANK_NAME + " - FundAxis Employee Master List");
            sheet.getFooter().setLeft("Confidential");
            sheet.getFooter().setCenter("Page &P of &N");
            sheet.getFooter().setRight("Printed: &D &T");

            wb.write(out);
            return new ByteArrayInputStream(out.toByteArray());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void banner(XSSFSheet sheet, int rowNum, float height, String text,
                               XSSFCellStyle style, int lastCol) {
        Row row = sheet.createRow(rowNum);
        row.setHeightInPoints(height);
        for (int i = 0; i <= lastCol; i++) {
            row.createCell(i).setCellStyle(style);
        }
        row.getCell(0).setCellValue(text);
        sheet.addMergedRegion(new CellRangeAddress(rowNum, rowNum, 0, lastCol));
    }

    /**
     * both null -> "All Dates"; same day -> "22 Oct 2026";
     * range -> "01 Oct 2026  to  22 Oct 2026"; one-sided -> "From ..." / "Up to ..."
     */
    private static String periodLabel(LocalDate start, LocalDate end) {
        if (start == null && end == null) return "All Dates";
        if (start != null && end != null) {
            return start.equals(end)
                    ? start.format(PERIOD_FMT)
                    : start.format(PERIOD_FMT) + "  to  " + end.format(PERIOD_FMT);
        }
        return start != null ? "From " + start.format(PERIOD_FMT) : "Up to " + end.format(PERIOD_FMT);
    }

    private static XSSFColor rgb(int r, int g, int b) {
        return new XSSFColor(new byte[]{(byte) r, (byte) g, (byte) b}, null);
    }

    private static XSSFFont font(XSSFWorkbook wb, int size, boolean bold, XSSFColor color) {
        XSSFFont f = wb.createFont();
        f.setFontName("Calibri");
        f.setFontHeightInPoints((short) size);
        f.setBold(bold);
        f.setColor(color);
        return f;
    }

    private static XSSFCellStyle style(XSSFWorkbook wb, XSSFFont font, XSSFColor fill,
                                       HorizontalAlignment h, boolean bordered, boolean wrap) {
        XSSFCellStyle s = wb.createCellStyle();
        s.setFont(font);
        s.setFillForegroundColor(fill);
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setAlignment(h);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        s.setWrapText(wrap);
        if (bordered) {
            s.setBorderTop(BorderStyle.THIN);
            s.setBorderBottom(BorderStyle.THIN);
            s.setBorderLeft(BorderStyle.THIN);
            s.setBorderRight(BorderStyle.THIN);
            s.setTopBorderColor(BORDER);
            s.setBottomBorderColor(BORDER);
            s.setLeftBorderColor(BORDER);
            s.setRightBorderColor(BORDER);
        }
        return s;
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }

    /** SENIOR_PRINCIPAL_OFFICER -> Senior Principal Officer */
    private static String pretty(String enumName) {
        if (enumName == null || enumName.isBlank()) return "-";
        StringBuilder sb = new StringBuilder();
        for (String part : enumName.toLowerCase(Locale.ENGLISH).split("_")) {
            if (part.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    // ================================================================== import

    // Header names the parser recognises. Compared after normalising (lower-case, letters and digits only),
    // so "Applicant Name", "applicant_name" and "APPLICANT NAME" all match "applicantname".
    private static final Set<String> NAME_HEADERS = Set.of(
            "applicationname", "contributionname", "employeename", "applicantname", "name", "fullname");
    private static final Set<String> ID_HEADERS = Set.of(
            "employeeid", "empid", "employeeno", "staffid", "id");
    private static final Set<String> JOIN_DATE_HEADERS = Set.of(
            "fundjoindate", "joindate", "dateofjoining");
    // Last-resort fallback only: a disbursement date is not strictly a fund join date.
    private static final Set<String> FALLBACK_DATE_HEADERS = Set.of("disbursementdate");

    /** The header row is searched for in the first rows of the sheet (title rows above it are skipped). */
    private static final int HEADER_SCAN_ROWS = 15;

    // Day-first formats (Bangladesh convention). Numeric Excel dates never reach these.
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            dateFormat("d/M/uuuu"), dateFormat("d-M-uuuu"), dateFormat("d.M.uuuu"),
            dateFormat("d-MMM-uuuu"), dateFormat("d MMM uuuu"), dateFormat("d MMMM uuuu"),
            dateFormat("uuuu-M-d"));

    /** Where the interesting columns are. A column index of -1 means "not present in this sheet". */
    private record Layout(int headerRow, int idCol, int nameCol, int dateCol, boolean headerFound) {}

    /**
     * Reads an uploaded Excel file into Employee objects.
     *
     * Supported layouts:
     *  - A header row containing a name column ("Application Name", "Contribution Name", "Employee Name",
     *    "Applicant Name", "Name", case-insensitive), optionally an Employee ID column and a join-date column
     *    ("Fund Join Date", "Join Date"; "Disbursement Date" only as a fallback).
     *  - Legacy layout with no recognisable header: column A = Employee ID, column B = name.
     *
     * The name cell may carry the designation, e.g. "Md. Mojadded Alam (EO)": the code is mapped to
     * EmployeeDesignation and removed from the stored name.
     *
     * If the sheet has no Employee ID column (or the cell is empty) the ID is left unset (null). IDs are
     * NOT invented: they are official bank Employee IDs, so the admin completes them in the Add Employees form.
     */
    public List<Employee> parseEmployeesFromExcel(MultipartFile file) throws IOException {
        List<Employee> employeeList = new ArrayList<>();

        try (InputStream inputStream = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(inputStream)) {

            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            Layout layout = detectLayout(sheet, formatter);

            for (int i = layout.headerRow() + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue; // Skip empty rows

                String employeeId = layout.idCol() >= 0 ? cellText(row, layout.idCol(), formatter) : "";
                String rawNameWithDesignation = cellText(row, layout.nameCol(), formatter);

                // Nothing useful on this row
                if (rawNameWithDesignation.isEmpty() && employeeId.isEmpty()) continue;
                // Legacy layout (no header found): an Employee ID is mandatory, as before
                if (!layout.headerFound() && employeeId.isEmpty()) continue;

                String cleanFullName = rawNameWithDesignation;
                Employee.EmployeeDesignation extractedDesignation = Employee.EmployeeDesignation.JO;

                // Match and extract designation code, e.g. "(EO)", from the raw name string
                Matcher matcher = DESIGNATION_PATTERN.matcher(rawNameWithDesignation);
                if (matcher.find()) {
                    String designationStr = matcher.group(1).toUpperCase(Locale.ROOT);
                    try {
                        extractedDesignation = Employee.EmployeeDesignation.valueOf(designationStr);
                    } catch (IllegalArgumentException e) {
                        extractedDesignation = Employee.EmployeeDesignation.JO;
                    }

                    // Remove ONLY the bracketed designation, never text inside the name itself
                    cleanFullName = matcher.replaceAll("")
                            .replaceAll("\\([^)]*\\)", "")   // remove any other parenthesized notes
                            .replaceAll("\\s+", " ")         // collapse the gap left behind
                            .trim();
                }

                Employee employee = new Employee();
                if (!employeeId.isEmpty()) {
                    employee.setEmployeeId(employeeId);      // otherwise left unset: the admin fills it in
                }
                employee.setFullName(cleanFullName);
                employee.setEmployeeDesignation(extractedDesignation);
                employee.setEmployeeStatus(Employee.EmployeeStatus.ACTIVE);

                if (layout.dateCol() >= 0) {
                    LocalDate joinDate = readDate(row.getCell(layout.dateCol()), formatter);
                    if (joinDate != null) {
                        employee.setFundJoinDate(joinDate);  // unreadable dates stay unset for the admin to fill
                    }
                }

                employeeList.add(employee);
            }
        }

        return employeeList;
    }

    /** Finds the header row and the columns we need; falls back to the legacy A=ID, B=name layout. */
    private static Layout detectLayout(Sheet sheet, DataFormatter formatter) {
        int lastScanRow = Math.min(sheet.getLastRowNum(), HEADER_SCAN_ROWS);

        for (int r = sheet.getFirstRowNum(); r <= lastScanRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;

            int idCol = -1, nameCol = -1, dateCol = -1, fallbackDateCol = -1;
            for (Cell cell : row) {
                String key = normalizeHeader(formatter.formatCellValue(cell));
                if (key.isEmpty()) continue;
                int col = cell.getColumnIndex();

                if (nameCol < 0 && NAME_HEADERS.contains(key)) nameCol = col;
                else if (idCol < 0 && ID_HEADERS.contains(key)) idCol = col;
                else if (dateCol < 0 && JOIN_DATE_HEADERS.contains(key)) dateCol = col;
                else if (fallbackDateCol < 0 && FALLBACK_DATE_HEADERS.contains(key)) fallbackDateCol = col;
            }

            if (nameCol >= 0) {
                return new Layout(r, idCol, nameCol, dateCol >= 0 ? dateCol : fallbackDateCol, true);
            }
        }

        // No recognisable header: legacy layout, header in the first row, A = Employee ID, B = name
        return new Layout(0, 0, 1, -1, false);
    }

    private static String normalizeHeader(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Cell content as plain text, regardless of numeric formatting in Excel. */
    private static String cellText(Row row, int col, DataFormatter formatter) {
        Cell cell = row.getCell(col);
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }

    /** Reads a date from a real Excel date cell, or from day-first text such as 15/03/2024 or 15-Mar-2024. */
    private static LocalDate readDate(Cell cell, DataFormatter formatter) {
        if (cell == null) return null;

        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        if (type == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate();
        }

        String text = formatter.formatCellValue(cell).trim();
        if (text.isEmpty()) return null;
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(text, format);
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        return null;
    }

    private static DateTimeFormatter dateFormat(String pattern) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(Locale.ENGLISH);
    }
}