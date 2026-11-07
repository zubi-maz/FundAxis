package com.fundaxis.service.excel;

import com.fundaxis.controller.admin.AdminContributionWorkspaceController.SingleMonthRecord;
import com.fundaxis.controller.admin.AdminContributionWorkspaceController.SingleMonthSummary;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SingleMonthContributionExcelServiceTest {
    @Test
    void usesClearSettlementHeadersAndKeepsPaymentMethodAndRemarksAsSeparateColumns() throws Exception {
        SingleMonthContributionExcelService service = new SingleMonthContributionExcelService();
        SingleMonthRecord row = new SingleMonthRecord(1, 7L, "EMP-TEST", "Test Employee", "Officer",
                1, 2, 3, null, null, new BigDecimal("4500.00"), new BigDecimal("3500.00"),
                new BigDecimal("1000.00"), new BigDecimal("4500.00"), BigDecimal.ZERO,
                "PAID", "Bank FT, HO-Hand", "Paid in full", new BigDecimal("8000.00"), "HO-Hand");
        SingleMonthSummary totals = new SingleMonthSummary(1, new BigDecimal("4500.00"),
                new BigDecimal("3500.00"), new BigDecimal("1000.00"), new BigDecimal("4500.00"),
                BigDecimal.ZERO, 1, 0, 0);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(
                service.export(LocalDate.of(2026, 11, 1), List.of(row), totals)))) {
            var sheet = workbook.getSheet("Monthly Records");
            assertEquals("Monthly Contribution Due (BDT)", sheet.getRow(4).getCell(7).getStringCellValue());
            assertEquals("Share Value (BDT)", sheet.getRow(4).getCell(8).getStringCellValue());
            assertEquals("Amount Paid (BDT)", sheet.getRow(4).getCell(9).getStringCellValue());
            assertEquals("Member Credit Used (BDT)", sheet.getRow(4).getCell(10).getStringCellValue());
            assertEquals("Total Contribution Settled (BDT)", sheet.getRow(4).getCell(11).getStringCellValue());
            assertEquals("Remaining Due (BDT)", sheet.getRow(4).getCell(12).getStringCellValue());
            assertEquals("Contribution Status", sheet.getRow(4).getCell(13).getStringCellValue());
            assertEquals("Payment Method", sheet.getRow(4).getCell(14).getStringCellValue());
            assertEquals("Remarks", sheet.getRow(4).getCell(15).getStringCellValue());
            assertEquals("Bank FT, HO-Hand", sheet.getRow(5).getCell(14).getStringCellValue());
            assertEquals("HO-Hand", sheet.getRow(5).getCell(15).getStringCellValue());
            assertEquals(CellType.NUMERIC, sheet.getRow(5).getCell(8).getCellType());
            assertEquals(8000d, sheet.getRow(5).getCell(8).getNumericCellValue());
            assertEquals(CellType.NUMERIC, sheet.getRow(5).getCell(9).getCellType());
            assertEquals(3500d, sheet.getRow(5).getCell(9).getNumericCellValue());
            assertEquals("A5:P6", sheet.getCTWorksheet().getAutoFilter().getRef());
        }
    }

    @Test
    void absentShareValueIsDashAndInformationalValuesAreNotSummed() throws Exception {
        SingleMonthContributionExcelService service = new SingleMonthContributionExcelService();
        SingleMonthRecord valued = new SingleMonthRecord(1, 7L, "EMP-A", "A", "Officer",
                1, 0, 1, null, null, new BigDecimal("100.00"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100.00"),
                "PENDING", "", "Unpaid", BigDecimal.ZERO, null);
        SingleMonthRecord absent = new SingleMonthRecord(2, 8L, "EMP-B", "B", "Officer",
                0, 1, 1, null, null, new BigDecimal("100.00"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100.00"),
                "PENDING", "", "Unpaid", null, null);
        SingleMonthSummary totals = new SingleMonthSummary(2, new BigDecimal("200.00"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("200.00"), 0, 2, 0);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(
                service.export(LocalDate.of(2026, 11, 1), List.of(valued, absent), totals)))) {
            var sheet = workbook.getSheet("Monthly Records");
            assertEquals(CellType.NUMERIC, sheet.getRow(5).getCell(8).getCellType());
            assertEquals(0d, sheet.getRow(5).getCell(8).getNumericCellValue());
            assertEquals(CellType.STRING, sheet.getRow(6).getCell(8).getCellType());
            assertEquals("—", sheet.getRow(6).getCell(8).getStringCellValue());
            assertEquals(CellType.BLANK, sheet.getRow(7).getCell(8).getCellType());
        }
    }
}
