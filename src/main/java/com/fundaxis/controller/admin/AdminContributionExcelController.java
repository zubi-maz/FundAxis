
package com.fundaxis.controller.admin;

import com.fundaxis.service.excel.ShareContributionExcelService;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

@RestController
@RequestMapping("/admin/contributions/export")
@PreAuthorize("hasRole('ADMIN')")
public class AdminContributionExcelController {

    private final ShareContributionExcelService excelService;

    private static final MediaType EXCEL_MEDIA_TYPE =
            MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument." +
                            "spreadsheetml.sheet"
            );

    public AdminContributionExcelController(
            ShareContributionExcelService excelService) {
        this.excelService = excelService;
    }

    // ==================== FULL YEAR ====================

    // Example:
    // GET /admin/contributions/export/year?year=2026

    @GetMapping("/year")
    public ResponseEntity<byte[]> exportFullYear(
            @RequestParam int year) {

        if (year < 1900 || year > 9999) {
            throw new IllegalArgumentException(
                    "Invalid reporting year");
        }

        byte[] excel = excelService.exportYear(year);

        String filename =
                "FundAxis_Contribution_Share_" +
                        year + ".xlsx";

        return download(excel, filename);
    }

    // ==================== MONTH RANGE ====================

    // Example:
    // GET /admin/contributions/export/range
    //     ?from=2025-09&to=2026-06

    @GetMapping("/range")
    public ResponseEntity<byte[]> exportRange(
            @RequestParam String from,
            @RequestParam String to) {

        YearMonth start;
        YearMonth end;

        try {
            start = YearMonth.parse(from);
            end = YearMonth.parse(to);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                    "Month format must be YYYY-MM");
        }

        if (start.isAfter(end)) {
            throw new IllegalArgumentException(
                    "Start month cannot be after end month");
        }

        byte[] excel = excelService.exportRange(start, end);

        String filename =
                "FundAxis_Contribution_Share_" +
                        start + "_to_" + end + ".xlsx";

        return download(excel, filename);
    }

    // ==================== DOWNLOAD RESPONSE ====================

    private ResponseEntity<byte[]> download(
            byte[] file,
            String filename) {

        return ResponseEntity.ok()
                .contentType(EXCEL_MEDIA_TYPE)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" +
                                filename + "\""
                )
                .cacheControl(CacheControl.noStore())
                .contentLength(file.length)
                .body(file);
    }
}
