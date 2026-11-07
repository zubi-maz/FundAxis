package com.fundaxis.service;

import com.fundaxis.entity.*;
import com.fundaxis.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.fundaxis.entity.ShareWithdrawal.WithdrawalStatus.*;

/** Request/reservation workflow. Unapproved financial policy fails closed. */
@Service
@PreAuthorize("hasRole('ADMIN')")
public class ShareWithdrawalService {
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;
    private static final ZoneId BANK_ZONE = ZoneId.of("Asia/Dhaka");
    private final ShareWithdrawalRepository withdrawals;
    private final ShareHoldingRepository holdings;
    private final ShareService shares;
    private final boolean enabled;
    private final boolean approvalEnabled;
    private final boolean postingEnabled;

    public ShareWithdrawalService(ShareWithdrawalRepository withdrawals, ShareHoldingRepository holdings,
            ShareService shares, @Value("${fundaxis.withdrawals.enabled:false}") boolean enabled,
            @Value("${fundaxis.withdrawals.approval-enabled:false}") boolean approvalEnabled,
            @Value("${fundaxis.withdrawals.posting-enabled:false}") boolean postingEnabled) {
        this.withdrawals=withdrawals; this.holdings=holdings; this.shares=shares;
        this.enabled=enabled; this.approvalEnabled=approvalEnabled; this.postingEnabled=postingEnabled;
    }
    public boolean isEnabled() { return enabled; }
    public boolean isApprovalEnabled() { return enabled && approvalEnabled; }
    public boolean isPostingEnabled() { return isApprovalEnabled() && postingEnabled; }
    public String requestConfigurationMessage() {
        return enabled ? null : "Withdrawal request intake is disabled by fundaxis.withdrawals.enabled=false. Verify and apply the manual SQL Server setup before enabling request storage.";
    }
    public String approvalConfigurationMessage() {
        return enabled && !approvalEnabled
                ? "Requests may be recorded, but approval is disabled until bank policy and independently assigned WITHDRAWAL_CHECKER authority are approved. A requester can never review their own request."
                : null;
    }
    public String postingConfigurationMessage() {
        return isApprovalEnabled() && !postingEnabled
                ? "Approval is enabled, but share posting remains disabled until its posting event, effective-date handling and authority are approved."
                : null;
    }
    public record HoldingView(ShareHolding.ShareCategory category, int quantity, long reserved,
            long available, String recordedValue) {}
    public record Portfolio(String employeeId, String fullName, String designation, String status,
            LocalDate joined, LocalDate exited, long pendingRequests, List<HoldingView> holdings) {
        public long availableOld() { return available("OLD"); }
        public long availableNew() { return available("NEW"); }
        public long reservedShares() { return holdings.stream().mapToLong(HoldingView::reserved).sum(); }
        private long available(String category) { return holdings.stream().filter(h -> h.category().name().equals(category)).mapToLong(HoldingView::available).findFirst().orElse(0); }
    }
    public record Dashboard(long holdings, long quantity, long reserved, long available, long pending,
            long approved, long awaitingPosting, BigDecimal disbursed) {}
    public record WithdrawalView(Long id, Long version, String employeeId, String fullName, String designation,
            ShareHolding.ShareCategory category, int quantity, int holdingQuantity, long unreserved,
            LocalDate requestDate, LocalDate effectiveDate, String reason, ShareWithdrawal.WithdrawalStatus status,
            BigDecimal recordedValue, BigDecimal gross, BigDecimal deduction, BigDecimal net,
            String requestedBy, LocalDateTime createdAt, String approvedBy, LocalDateTime approvedAt,
            String postedBy, LocalDateTime postedAt, String transactionReference,
            String resolvedBy, LocalDateTime resolvedAt, String rejectionReason, String cancellationReason,
            LocalDateTime disbursedAt, String disbursedBy, String paymentReference) {}
    public record ValuationPreview(Long requestId, String employeeId, String employeeName,
            ShareHolding.ShareCategory category, int requestedQuantity, long availableQuantity,
            String referenceValuePerShare, String proposedValuePerShare, String grossAmount,
            String proposedDeduction, String netAmount, LocalDate valuationDate, String valuationReason,
            boolean previewOnly) {}

    private static LocalDate today() { return LocalDate.now(BANK_ZONE); }
    private static LocalDateTime now() { return LocalDateTime.now(BANK_ZONE).truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
    private void requireEnabled() {
        if (!enabled) throw new IllegalStateException("Withdrawal setup is not enabled. Review the schema and workflow configuration first.");
    }
    private static String actor() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if (auth==null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())
                || auth.getName().length()>100) throw new IllegalStateException("An authenticated administrator is required.");
        return auth.getName();
    }
    public boolean isCheckerAuthorized() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        return auth!=null && auth.isAuthenticated() && auth.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_WITHDRAWAL_CHECKER".equals(authority.getAuthority()));
    }
    public String currentActorName() { return actor(); }
    private static String employeeId(String value) {
        if (value==null || value.isBlank() || value.trim().length()>50) throw new IllegalArgumentException("Enter a valid business Employee ID.");
        return value.trim();
    }
    private static String reason(String value) {
        if (value==null || value.isBlank() || value.trim().length()>500) throw new IllegalArgumentException("Enter a reason of 1 to 500 characters.");
        return value.trim();
    }
    private static BigDecimal moneyInput(BigDecimal value, String field) {
        if (value==null || value.signum()<0) throw new IllegalArgumentException(field+" cannot be negative and is required.");
        try { value=value.setScale(2, java.math.RoundingMode.UNNECESSARY); }
        catch (ArithmeticException ex) { throw new IllegalArgumentException(field+" must be entered to two decimal places without rounding."); }
        if (value.precision()>20) throw new IllegalArgumentException(field+" exceeds the supported BDT amount range.");
        return value;
    }

    /** Calculates a proposed valuation only. This method never persists or changes the withdrawal. */
    @Transactional(readOnly=true)
    public ValuationPreview previewValuation(Long id, Long expectedVersion, BigDecimal proposedValuePerShare,
            BigDecimal proposedDeduction, LocalDate valuationDate, String valuationReason) {
        requireEnabled();
        if (id==null || expectedVersion==null) throw new IllegalArgumentException("Refresh the withdrawal request and try again.");
        var withdrawal=withdrawals.details(id).orElseThrow(() -> new IllegalArgumentException("Withdrawal request not found."));
        if (!expectedVersion.equals(withdrawal.getVersion())) throw new IllegalStateException("This withdrawal changed. Refresh the request before calculating a new preview.");
        if (withdrawal.getStatus()!=PENDING || withdrawal.getSharesPostedAt()!=null || withdrawal.getDisbursedAt()!=null)
            throw new IllegalStateException("A valuation preview is available only for an unposted pending request.");
        if (withdrawal.getRequestedQuantity()==null || withdrawal.getRequestedQuantity()<=0)
            throw new IllegalStateException("The withdrawal quantity is invalid. Review the request before valuation.");
        if (valuationDate==null) throw new IllegalArgumentException("Select an applicable valuation date.");
        String notes=reason(valuationReason);
        BigDecimal unit=moneyInput(proposedValuePerShare,"Proposed withdrawal value per share");
        BigDecimal deduction=moneyInput(proposedDeduction,"Proposed deduction");
        BigDecimal gross=moneyInput(unit.multiply(BigDecimal.valueOf(withdrawal.getRequestedQuantity())),"Gross withdrawal amount");
        if (deduction.compareTo(gross)>0) throw new IllegalArgumentException("Proposed deduction cannot exceed the gross withdrawal amount.");
        BigDecimal net=gross.subtract(deduction).setScale(2,java.math.RoundingMode.UNNECESSARY);
        var holding=withdrawal.getShareHolding();
        long quantityAvailableForThisRequest=shares.getCurrentShareQuantity(holding.getId())-withdrawals.reserved(holding.getId(),withdrawal.getId());
        if (quantityAvailableForThisRequest<withdrawal.getRequestedQuantity())
            throw new IllegalStateException("The request no longer has enough available shares. Refresh and reconcile the reservation.");
        BigDecimal reference=shares.shareValuePerShareAsOf(holding.getId(),valuationDate);
        return new ValuationPreview(withdrawal.getId(),withdrawal.getEmployee().getEmployeeId(),withdrawal.getEmployee().getFullName(),
                holding.getShareCategory(),withdrawal.getRequestedQuantity(),quantityAvailableForThisRequest,
                reference==null?null:reference.toPlainString(),unit.toPlainString(),gross.toPlainString(),deduction.toPlainString(),
                net.toPlainString(),valuationDate,notes,true);
    }
    private static void membership(Employee employee) {
        if (employee.getFundJoinDate()!=null && today().isBefore(employee.getFundJoinDate()))
            throw new IllegalArgumentException("The employee has not joined the fund yet.");
        if (employee.getFundExitDate()!=null && today().isAfter(employee.getFundExitDate()))
            throw new IllegalArgumentException("The employee has exited the fund. An approved exit-withdrawal policy is required.");
    }
    @Transactional(readOnly=true)
    public Portfolio portfolio(String businessId) {
        requireEnabled(); String id=employeeId(businessId);
        Employee e=shares.getShareEmployee(id);
        Map<Long,Long> reserved=new HashMap<>();
        for (var row:withdrawals.employeeReservations(id)) reserved.put((Long)row[0],((Number)row[1]).longValue());
        List<HoldingView> rows=shares.getEmployeeShares(id).stream()
                .sorted(Comparator.comparing(h -> h.getShareCategory().name()))
                .map(h -> { int current=h.getShareQuantity(); return new HoldingView(h.getShareCategory(),current,reserved.getOrDefault(h.getId(),0L),
                        current-reserved.getOrDefault(h.getId(),0L),h.getShareValuePerShare()==null?null:h.getShareValuePerShare().toPlainString()); }).toList();
        return new Portfolio(e.getEmployeeId(),e.getFullName(),e.getEmployeeDesignation()==null?"Not recorded":e.getEmployeeDesignation().name(),
                e.getEmployeeStatus()==null?"Not recorded":e.getEmployeeStatus().name(),e.getFundJoinDate(),e.getFundExitDate(),
                withdrawals.countByEmployeeEmployeeIdAndStatus(id, PENDING), rows);
    }
    @Transactional(readOnly=true)
    public Dashboard dashboard() {
        requireEnabled(); var holdingRows=holdings.findAll(); var s=withdrawals.summary();
        long quantity=holdingRows.stream().mapToLong(h -> shares.getCurrentShareQuantity(h.getId())).sum();
        return new Dashboard(holdingRows.size(),quantity,s.getReserved(),quantity-s.getReserved(),
                s.getPending(),s.getApproved(),s.getAwaitingPosting(),s.getUnknownDisbursements()>0?null:
                (s.getDisbursed()==null?BigDecimal.ZERO:s.getDisbursed()));
    }
    @Transactional(readOnly=true)
    public Page<WithdrawalView> history(String id, LocalDate from, LocalDate to, String category,
            String status, int page, int size, String sort) {
        requireEnabled();
        if (page<0 || !Set.of(10,25,50,100).contains(size)) throw new IllegalArgumentException("Invalid page or page size.");
        if (from!=null && to!=null && from.isAfter(to)) throw new IllegalArgumentException("Start date cannot follow end date.");
        String field=switch(sort) { case "requestDate" -> "requestDate"; case "employeeId" -> "employee.employeeId";
            case "quantity" -> "requestedQuantity"; case "status" -> "status"; default -> throw new IllegalArgumentException("Invalid sort field."); };
        ShareHolding.ShareCategory selectedCategory=category==null || category.isBlank()?null:ShareHolding.ShareCategory.valueOf(category);
        ShareWithdrawal.WithdrawalStatus selectedStatus=status==null || status.isBlank()?null:ShareWithdrawal.WithdrawalStatus.valueOf(status);
        String selectedId=id==null || id.isBlank()?null:employeeId(id);
        Specification<ShareWithdrawal> filter=(root,query,cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates=new ArrayList<>();
            if (selectedId!=null) predicates.add(cb.equal(root.get("employee").get("employeeId"),selectedId));
            if (from!=null) predicates.add(cb.greaterThanOrEqualTo(root.get("requestDate"),from));
            if (to!=null) predicates.add(cb.lessThanOrEqualTo(root.get("requestDate"),to));
            if (selectedCategory!=null) predicates.add(cb.equal(root.get("shareHolding").get("shareCategory"),selectedCategory));
            if (selectedStatus!=null) predicates.add(cb.equal(root.get("status"),selectedStatus));
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        return withdrawals.findAll(filter,PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,field).and(Sort.by(Sort.Direction.DESC,"id"))))
                .map(w -> view(w, null));
    }
    @Transactional(readOnly=true)
    public List<WithdrawalView> attention() {
        requireEnabled();
        return withdrawals.findAll((root,q,cb) -> root.get("status").in(PENDING,APPROVED),PageRequest.of(0,8,Sort.by("requestDate","id")))
                .stream().map(w -> view(w,null)).toList();
    }
    @Transactional(readOnly=true)
    public List<WithdrawalView> employeeHistory(String businessId) {
        requireEnabled(); String id=employeeId(businessId);
        return withdrawals.findByEmployeeEmployeeIdOrderByRequestDateDescIdDesc(id).stream()
                .map(w -> view(w, null)).toList();
    }
    @Transactional(readOnly=true)
    public WithdrawalView details(Long id) {
        requireEnabled(); var w=withdrawals.details(id).orElseThrow(() -> new IllegalArgumentException("Withdrawal request not found."));
        return view(w,withdrawals.reserved(w.getShareHolding().getId(),-1L));
    }
    private WithdrawalView view(ShareWithdrawal w, Long reserved) {
        var e=w.getEmployee();var h=w.getShareHolding();
        int currentQuantity=shares.getCurrentShareQuantity(h.getId());
        return new WithdrawalView(w.getId(),w.getVersion(),e.getEmployeeId(),e.getFullName(),
                e.getEmployeeDesignation()==null?"Not recorded":e.getEmployeeDesignation().name(),h.getShareCategory(),w.getRequestedQuantity(),
                currentQuantity,reserved==null?-1:currentQuantity-reserved,w.getRequestDate(),w.getEffectiveDate(),w.getReason(),w.getStatus(),
                h.getShareValuePerShare(),w.getGrossAmount(),w.getDeductionAmount(),w.getNetAmount(),w.getRequestedBy(),w.getCreatedAt(),
                w.getApprovedBy(),w.getApprovedAt(),w.getSharesPostedBy(),w.getSharesPostedAt(),
                w.getShareTransaction()==null?null:"STX-"+w.getShareTransaction().getId(),w.getResolvedBy(),w.getResolvedAt(),
                w.getRejectionReason(),w.getCancellationReason(),w.getDisbursedAt(),w.getDisbursedBy(),w.getPaymentReference());
    }
    @Transactional
    public Long request(String businessId, ShareHolding.ShareCategory category, Integer quantity, String explanation, String token) {
        return request(businessId, category, quantity, today(), explanation, token);
    }
    @Transactional
    public Long request(String businessId, ShareHolding.ShareCategory category, Integer quantity, LocalDate effectiveDate, String explanation, String token) {
        requireEnabled();String id=employeeId(businessId);String maker=actor();String notes=reason(explanation);
        if (category==null || quantity==null || quantity<=0) throw new IllegalArgumentException("Select a holding and enter a positive whole quantity.");
        if (effectiveDate==null) throw new IllegalArgumentException("Select a withdrawal effective date.");
        try { token=UUID.fromString(token).toString(); } catch (RuntimeException ex) { throw new IllegalArgumentException("Invalid request token. Reload the form."); }
        var reference=holdings.findByEmployeeEmployeeIdAndShareCategory(id,category)
                .orElseThrow(() -> new IllegalArgumentException("The selected holding does not belong to this employee."));
        var holding=lockHolding(reference.getId());
        var duplicate=withdrawals.findByRequestToken(token);
        if (duplicate.isPresent()) {
            var w=duplicate.get();
            if (!maker.equals(w.getRequestedBy()) || !holding.getId().equals(w.getShareHolding().getId())
                    || !quantity.equals(w.getRequestedQuantity()) || !notes.equals(w.getReason())
                    || !effectiveDate.equals(w.getEffectiveDate()))
                throw new IllegalArgumentException("This submission token was already used for another request.");
            return w.getId();
        }
        membership(holding.getEmployee());
        if (holding.getAllocationDate()!=null && effectiveDate.isBefore(holding.getAllocationDate()))
            throw new IllegalArgumentException("Effective date cannot precede the holding allocation date.");
        if (holding.getEmployee().getFundJoinDate()!=null && effectiveDate.isBefore(holding.getEmployee().getFundJoinDate()))
            throw new IllegalArgumentException("Effective date cannot precede fund membership.");
        if (holding.getEmployee().getFundExitDate()!=null && effectiveDate.isAfter(holding.getEmployee().getFundExitDate()))
            throw new IllegalArgumentException("Effective date cannot follow fund exit.");
        if (quantity > shares.getCurrentShareQuantity(holding.getId())-withdrawals.reserved(holding.getId(),-1L))
            throw new IllegalArgumentException("Insufficient unreserved shares. Refresh the employee holdings.");
        ShareWithdrawal w=new ShareWithdrawal();w.setEmployee(holding.getEmployee());w.setShareHolding(holding);
        w.setRequestedQuantity(quantity);w.setReason(notes);w.setRequestDate(today());w.setEffectiveDate(effectiveDate);w.setRequestedBy(maker);w.setRequestToken(token);
        w.setStatus(PENDING);return withdrawals.saveAndFlush(w).getId();
    }
    private ShareHolding lockHolding(Long id) {
        var holding=holdings.findWithLockById(id).orElseThrow();
        entityManager.refresh(holding, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        return holding;
    }
    // Consistent lock order across transitions and share adjustments: holding, then request.
    private ShareWithdrawal locked(Long id) {
        var reference=withdrawals.findById(id).orElseThrow(() -> new IllegalArgumentException("Withdrawal request not found."));
        Long holdingId=reference.getShareHolding().getId();
        lockHolding(holdingId);
        // Refresh directly: a locking query may reject the stale managed version before refresh can run.
        entityManager.refresh(reference, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        var w=reference;
        if (!holdingId.equals(w.getShareHolding().getId()) || !w.getEmployee().getId().equals(w.getShareHolding().getEmployee().getId()))
            throw new IllegalStateException("Employee/holding relationship requires reconciliation.");
        return w;
    }
    private void version(ShareWithdrawal w, Long expected) {
        if (expected==null || !expected.equals(w.getVersion())) throw new IllegalStateException("This request changed. Refresh its details before acting.");
    }
    private void checker(ShareWithdrawal w) {
        if (!isApprovalEnabled()) throw new IllegalStateException("Approval authority and maker-checker policy await confirmation.");
        if (w.getRequestedBy()==null) throw new IllegalStateException("Legacy requester identity is unknown; audit reconciliation is required.");
        if (!isCheckerAuthorized()) throw new IllegalStateException("This action requires the separately assigned WITHDRAWAL_CHECKER authority.");
        if (actor().equals(w.getRequestedBy())) throw new IllegalStateException("The request creator cannot approve or reject their own request. Use cancellation for your pending request.");
    }
    @Transactional
    public void approve(Long id, Long expectedVersion) {
        requireEnabled();
        if (!isApprovalEnabled()) throw new IllegalStateException("Approval authority and maker-checker policy await confirmation.");
        var w=locked(id);checker(w);
        if (w.getStatus()==APPROVED) throw new IllegalStateException("This withdrawal request has already been approved.");
        version(w,expectedVersion);
        if (w.getStatus()!=PENDING) throw new IllegalStateException("Only pending requests can be approved.");
        membership(w.getEmployee());
        if (w.getRequestedQuantity()>shares.getCurrentShareQuantity(w.getShareHolding().getId())-withdrawals.reserved(w.getShareHolding().getId(),w.getId()))
            throw new IllegalStateException("Insufficient shares for this approval.");
        w.setStatus(APPROVED);w.setApprovedBy(actor());w.setApprovedAt(now());withdrawals.saveAndFlush(w);
    }
    @Transactional
    public void reject(Long id, Long expectedVersion, String explanation) {
        requireEnabled();
        if (!isApprovalEnabled()) throw new IllegalStateException("Approval authority and maker-checker policy await confirmation.");
        var w=locked(id);checker(w);version(w,expectedVersion);
        if (w.getStatus()!=PENDING) throw new IllegalStateException("Only pending requests can be rejected.");
        w.setRejectionReason(reason(explanation));w.setResolvedBy(actor());w.setResolvedAt(now());w.setStatus(REJECTED);withdrawals.saveAndFlush(w);
    }
    @Transactional
    public void cancel(Long id, Long expectedVersion, String explanation) {
        requireEnabled();var w=locked(id);version(w,expectedVersion);
        if (w.getStatus()!=PENDING || !actor().equals(w.getRequestedBy()))
            throw new IllegalStateException("Only the request creator can cancel an unapproved request.");
        w.setCancellationReason(reason(explanation));w.setResolvedBy(actor());w.setResolvedAt(now());w.setStatus(CANCELLED);withdrawals.saveAndFlush(w);
    }
    @Transactional
    public void recordValuation(Long id, Long expectedVersion, BigDecimal gross, BigDecimal deduction, BigDecimal net, String reason) {
        requireEnabled();
        var w = locked(id);
        version(w, expectedVersion);
        if (w.getStatus() != PENDING || w.getSharesPostedAt() != null || w.getDisbursedAt() != null)
            throw new IllegalStateException("Valuation can only be recorded for a pending, unposted, undisbursed request.");

        w.setGrossAmount(moneyInput(gross, "Gross amount"));
        w.setDeductionAmount(moneyInput(deduction, "Deduction amount"));
        if (w.getDeductionAmount().compareTo(w.getGrossAmount()) > 0)
            throw new IllegalArgumentException("Deduction cannot exceed gross amount.");
        w.setNetAmount(moneyInput(net, "Net amount"));
        if (w.getGrossAmount().subtract(w.getDeductionAmount()).compareTo(w.getNetAmount()) != 0)
            throw new IllegalArgumentException("Net amount must be gross minus deduction.");

        w.setReason(reason(reason));
        withdrawals.saveAndFlush(w);
    }

    @Transactional
    public void postShares(Long id, Long expectedVersion) {
        requireEnabled();
        if (!isPostingEnabled()) throw new IllegalStateException("Share posting awaits confirmation of the posting policy.");
        var w=locked(id);
        if (w.getSharesPostedAt()!=null && w.getShareTransaction()!=null) return;
        checker(w);
        version(w,expectedVersion);
        if (w.getStatus()!=APPROVED || w.getApprovedAt()==null || w.getApprovedBy()==null || w.getRequestedBy()==null
                || w.getRequestedBy().equals(w.getApprovedBy()) || w.getShareTransaction()!=null || w.getSharesPostedAt()!=null)
            throw new IllegalStateException("Valid independent approval is required before posting.");
        if (w.getRequestedQuantity()<=0) throw new IllegalStateException("Invalid withdrawal quantity.");
        if (w.getEffectiveDate()!=null && w.getEffectiveDate().isAfter(today()))
            throw new IllegalStateException("Future-effective shares can be posted on or after the effective date.");
        var transaction=shares.postWithdrawalShares(w);
        w.setShareTransaction(transaction);w.setSharesPostedAt(now());w.setSharesPostedBy(actor());withdrawals.saveAndFlush(w);
    }
    @Transactional
    public void confirmDisbursement(Long id, Long expectedVersion, String paymentReference) {
        requireEnabled();
        var w = locked(id);
        version(w, expectedVersion);
        if (w.getStatus() == DISBURSED || w.getDisbursedAt() != null)
            throw new IllegalStateException("This withdrawal has already been disbursed.");
        if (w.getStatus() != APPROVED || w.getApprovedBy() == null)
            throw new IllegalStateException("Only an approved request can be disbursed.");
        if (w.getSharesPostedAt() == null || w.getShareTransaction() == null)
            throw new IllegalStateException("Shares must be posted before disbursement.");
        if (w.getGrossAmount() == null || w.getNetAmount() == null)
            throw new IllegalStateException("A confirmed valuation is required before disbursement.");
        if (w.getGrossAmount().subtract(w.getDeductionAmount() == null ? BigDecimal.ZERO : w.getDeductionAmount()).compareTo(w.getNetAmount()) != 0)
            throw new IllegalStateException("The recorded valuation does not reconcile.");

        String ref = paymentReference == null ? "" : paymentReference.trim();
        if (ref.isEmpty() || ref.length() > 150)
            throw new IllegalArgumentException("A valid payment reference (1-150 characters) is required.");

        w.setStatus(DISBURSED);
        w.setDisbursedAt(now());
        w.setDisbursedBy(actor());
        w.setPaymentReference(ref);
        w.setResolvedBy(actor());
        w.setResolvedAt(now());
        withdrawals.saveAndFlush(w);
    }
}
