package com.fundaxis.reporting;

import com.fundaxis.controller.admin.AdminShareWithdrawalProfitController;
import com.fundaxis.entity.ShareHolding;
import com.fundaxis.entity.ShareWithdrawal;
import com.fundaxis.service.ShareWithdrawalService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShareWithdrawalRegisterExportTest {
    @Test
    void exportsStoredWithdrawalValuesAndLeavesUnknownAmountsBlank() throws Exception {
        ShareWithdrawalService service = mock(ShareWithdrawalService.class);
        when(service.isEnabled()).thenReturn(true);
        var row = new ShareWithdrawalService.WithdrawalView(
                42L, 3L, "EMP-42", "Example Employee", "Officer", ShareHolding.ShareCategory.NEW,
                2, 4, 2, LocalDate.of(2026, 11, 4), LocalDate.of(2026, 11, 1), "Request reason",
                ShareWithdrawal.WithdrawalStatus.PENDING,
                null, null, null, null,
                "maker", LocalDateTime.of(2026, 11, 4, 10, 0),
                null, null, null, null, null, null, null, null, null, null, null, null);
        when(service.history("", null, null, "", "", 0, 100, "requestDate"))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 100), 1));

        var response = new AdminShareWithdrawalProfitController(service)
                .withdrawalRegister("", null, null, "", "");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getBody()))) {
            var sheet = workbook.getSheet("Share Withdrawals");
            assertNotNull(sheet);
            assertEquals("Request ID", sheet.getRow(3).getCell(0).getStringCellValue());
            assertEquals("42", sheet.getRow(4).getCell(0).getStringCellValue());
            assertEquals("PENDING", sheet.getRow(4).getCell(7).getStringCellValue());
            assertEquals("maker", sheet.getRow(4).getCell(12).getStringCellValue());
            assertNull(sheet.getRow(4).getCell(9), "Unrecorded gross amount must not be fabricated");
        }
    }
}
