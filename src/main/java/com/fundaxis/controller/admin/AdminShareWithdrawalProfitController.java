package com.fundaxis.controller.admin;

import com.fundaxis.entity.ShareHolding;
import com.fundaxis.service.ShareWithdrawalService;
import org.springframework.dao.DataAccessException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.math.BigDecimal;

@Controller
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/admin/share-withdrawal-profit")
public class AdminShareWithdrawalProfitController {
    private final ShareWithdrawalService service;
    public AdminShareWithdrawalProfitController(ShareWithdrawalService service) { this.service=service; }

    @GetMapping
    public String workspace(@RequestParam(defaultValue="overview") String tab,
            @RequestParam(defaultValue="") String employeeId, @RequestParam(defaultValue="") String category,
            @RequestParam(defaultValue="") String status, @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="10") int size, @RequestParam(defaultValue="requestDate") String sort,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required=false) Long requestId, java.security.Principal principal, Model model) {
        if (!Set.of("overview","withdrawals","profit","history").contains(tab)) tab="overview";
        model.addAttribute("actor",principal==null?"":principal.getName());
        model.addAttribute("tab",tab);model.addAttribute("employeeId",employeeId);model.addAttribute("category",category);
        model.addAttribute("status",status);model.addAttribute("pageSize",size);model.addAttribute("sort",sort);
        model.addAttribute("from",from);model.addAttribute("to",to);model.addAttribute("requestId",requestId);
        model.addAttribute("requestToken",UUID.randomUUID().toString());
        model.addAttribute("enabled",service.isEnabled());model.addAttribute("approvalEnabled",service.isApprovalEnabled());
        model.addAttribute("checkerAuthorized",service.isCheckerAuthorized());
        model.addAttribute("postingEnabled",service.isPostingEnabled());model.addAttribute("storageReady",service.isEnabled());
        if (service.isEnabled()) {
            try {
                if ("overview".equals(tab)) {
                    model.addAttribute("summary",service.dashboard());model.addAttribute("attention",service.attention());
                    model.addAttribute("recent",service.history("",null,null,"","",0,10,"requestDate").getContent());
                }
                if ("withdrawals".equals(tab) && !employeeId.isBlank()) model.addAttribute("portfolio",service.portfolio(employeeId));
                if ("withdrawals".equals(tab) || "history".equals(tab))
                    model.addAttribute("history",service.history(employeeId,from,to,category,status,page,size,sort));
                if (requestId!=null && ("withdrawals".equals(tab) || "history".equals(tab))) model.addAttribute("detail",service.details(requestId));
            } catch (IllegalArgumentException | IllegalStateException exception) { model.addAttribute("moduleError",exception.getMessage()); }
            catch (DataAccessException exception) {
                model.addAttribute("storageReady",false);
                model.addAttribute("moduleError","Withdrawal storage is unavailable. Apply the reviewed schema setup before enabling this module.");
            }
        }
        return "admin/share-withdrawal-profit";
    }
    @GetMapping("/employee")
    @ResponseBody
    public ResponseEntity<?> lookup(@RequestParam String employeeId) {
        try { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.portfolio(employeeId)); }
        catch (IllegalArgumentException ex) { return ResponseEntity.badRequest().body(java.util.Map.of("message",ex.getMessage())); }
        catch (IllegalStateException ex) { return ResponseEntity.status(HttpStatus.CONFLICT).body(java.util.Map.of("message",ex.getMessage())); }
        catch (DataAccessException ex) { return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(java.util.Map.of("message","Withdrawal storage is unavailable. Contact support.")); }
    }
    /** Exports the actual withdrawal register. Missing financial values remain blank; this is not a cash or balance-sheet report. */
    @GetMapping("/withdrawals.xlsx")
    public ResponseEntity<byte[]> withdrawalRegister(
            @RequestParam(defaultValue="") String employeeId,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue="") String category,
            @RequestParam(defaultValue="") String status) throws IOException {
        if (!service.isEnabled()) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        var first = service.history(employeeId, from, to, category, status, 0, 100, "requestDate");
        if (first.getTotalElements() > 50_000) return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Share Withdrawals");
            sheet.createFreezePane(0, 4);
            CellStyle heading = workbook.createCellStyle();
            Font bold = workbook.createFont(); bold.setBold(true); heading.setFont(bold);
            CellStyle quantityStyle = workbook.createCellStyle();
            quantityStyle.setDataFormat(workbook.createDataFormat().getFormat("#,##0"));
            CellStyle moneyStyle = workbook.createCellStyle();
            moneyStyle.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00;[Red](#,##0.00)"));
            Row title = sheet.createRow(0); title.createCell(0).setCellValue("FundAxis — Share Withdrawal Register");
            Row generated = sheet.createRow(1); generated.createCell(0).setCellValue("Generated (Asia/Dhaka): " + LocalDateTime.now(ZoneId.of("Asia/Dhaka")));
            String[] columns = {"Request ID", "Employee ID", "Employee Name", "Share Category", "Quantity", "Request Date", "Effective Date", "Status", "Reason", "Gross (BDT)", "Deduction (BDT)", "Net (BDT)", "Requested By", "Approved By", "Approved At", "Share Posted By", "Share Posted At", "Share Transaction", "Disbursed By", "Disbursed At", "Payment Reference", "Resolved By", "Resolved At", "Rejection Reason", "Cancellation Reason"};
            Row header = sheet.createRow(3);
            for (int c=0;c<columns.length;c++) { Cell cell=header.createCell(c); cell.setCellValue(columns[c]); cell.setCellStyle(heading); }
            int rowIndex=4;
            for (int page=0; page<first.getTotalPages(); page++) {
                var rows = page==0 ? first : service.history(employeeId,from,to,category,status,page,100,"requestDate");
                for (var item : rows.getContent()) {
                    Row row=sheet.createRow(rowIndex++); int c=0;
                    text(row,c++,item.id()); text(row,c++,item.employeeId()); text(row,c++,item.fullName());
                    text(row,c++,item.category()==null?null:item.category().name()); number(row,c++,item.quantity(),quantityStyle);
                    text(row,c++,item.requestDate()); text(row,c++,item.effectiveDate());
                    text(row,c++,item.status()==null?null:item.status().name()); text(row,c++,item.reason());
                    money(row,c++,item.gross(),moneyStyle); money(row,c++,item.deduction(),moneyStyle); money(row,c++,item.net(),moneyStyle);
                    text(row,c++,item.requestedBy()); text(row,c++,item.approvedBy()); text(row,c++,item.approvedAt());
                    text(row,c++,item.postedBy()); text(row,c++,item.postedAt()); text(row,c++,item.transactionReference());
                    text(row,c++,item.disbursedBy()); text(row,c++,item.disbursedAt()); text(row,c++,item.paymentReference());
                    text(row,c++,item.resolvedBy()); text(row,c++,item.resolvedAt()); text(row,c++,item.rejectionReason()); text(row,c,item.cancellationReason());
                }
            }
            sheet.setRepeatingRows(CellRangeAddress.valueOf("4:4"));
            sheet.getPrintSetup().setLandscape(true);
            sheet.getPrintSetup().setFitWidth((short)1);
            sheet.setFitToPage(true);
            for (int c=0;c<columns.length;c++) sheet.setColumnWidth(c, Math.min(18_000, Math.max(3_500, columns[c].length()*320)));
            workbook.write(output);
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"FundAxis_Share_Withdrawal_Register.xlsx\"")
                    .body(output.toByteArray());
        }
    }
    private static void text(Row row,int column,Object value) { if(value!=null) row.createCell(column).setCellValue(value.toString()); }
    private static void number(Row row,int column,Number value,CellStyle style) { if(value!=null) { Cell cell=row.createCell(column); cell.setCellValue(value.doubleValue()); cell.setCellStyle(style); } }
    private static void money(Row row,int column,BigDecimal value,CellStyle style) { if(value!=null) { Cell cell=row.createCell(column); cell.setCellValue(value.doubleValue()); cell.setCellStyle(style); } }
    @PostMapping("/requests")
    public String request(@RequestParam String employeeId, @RequestParam ShareHolding.ShareCategory category,
            @RequestParam Integer quantity, @RequestParam String reason, @RequestParam String requestToken,
            RedirectAttributes redirect) {
        redirect.addAttribute("tab","withdrawals");redirect.addAttribute("employeeId",employeeId);
        try {
            Long id=service.request(employeeId,category,quantity,reason,requestToken);
            redirect.addAttribute("requestId",id);redirect.addFlashAttribute("moduleSuccess","Withdrawal request recorded. Shares and money have not been deducted.");
        } catch (IllegalArgumentException | IllegalStateException ex) { redirect.addFlashAttribute("moduleError",ex.getMessage()); }
        catch (DataAccessException ex) { redirect.addFlashAttribute("moduleError","The request could not be saved. Refresh and check for an existing request before retrying."); }
        return "redirect:/admin/share-withdrawal-profit";
    }
    @PostMapping("/requests/{id}/{action}")
    public String transition(@PathVariable Long id, @PathVariable String action, @RequestParam Long version,
            @RequestParam(defaultValue="") String reason, @RequestParam(defaultValue="false") boolean confirmed,
            @RequestParam(defaultValue="") String paymentReference,
            RedirectAttributes redirect) {
        redirect.addAttribute("tab","withdrawals");redirect.addAttribute("requestId",id);
        try {
            if (!confirmed) throw new IllegalArgumentException("Confirm this specific withdrawal action before submitting.");
            switch(action) {
                case "approve" -> service.approve(id,version);
                case "reject" -> service.reject(id,version,reason);
                case "cancel" -> service.cancel(id,version,reason);
                case "post-shares" -> service.postShares(id,version);
                case "disburse" -> service.confirmDisbursement(id, version, paymentReference);
                default -> throw new IllegalArgumentException("Unsupported withdrawal action.");
            }
            redirect.addFlashAttribute("moduleSuccess","The withdrawal action was recorded. Refresh details show the authoritative current state.");
        } catch (IllegalArgumentException | IllegalStateException ex) { redirect.addFlashAttribute("moduleError",ex.getMessage()); }
        catch (DataAccessException ex) { redirect.addFlashAttribute("moduleError","A concurrent change or storage issue prevented this action. Refresh the request before retrying."); }
        return "redirect:/admin/share-withdrawal-profit";
    }
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    @ResponseBody
    public ResponseEntity<String> invalidParameter() { return ResponseEntity.badRequest().body("Invalid withdrawal parameter. Check quantities, dates and filters."); }
}
