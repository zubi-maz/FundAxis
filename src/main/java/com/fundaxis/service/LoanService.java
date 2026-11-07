package com.fundaxis.service;

import com.fundaxis.entity.Employee;
import com.fundaxis.entity.Loan;
import com.fundaxis.exception.DuplicateLoanException;
import com.fundaxis.exception.EmployeeNotFoundException;
import com.fundaxis.exception.LoanNotFoundException;
import com.fundaxis.repository.EmployeeRepository;
import com.fundaxis.repository.LoanRepository;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class LoanService {

    private final LoanRepository loanRepository;
    private final EmployeeRepository employeeRepository;

    @Value("${fundaxis.loans.approval-enabled:false}")
    private boolean loanApprovalEnabled;

    public LoanService(LoanRepository loanRepository, EmployeeRepository employeeRepository) {
        this.loanRepository = loanRepository;
        this.employeeRepository = employeeRepository;

    }


    ///==================== Create Loan ====================///

    ///---------- Create A New Loan For An Existing Employee ----------
    @Transactional
    public Loan createLoan(String employeeId, String guarantorEmployeeId,
                           String legacyGuarantorName, Loan loan, String authenticatedCreator) {

        if (authenticatedCreator == null || authenticatedCreator.isBlank()
                || authenticatedCreator.trim().length() > 100) {
            throw new IllegalArgumentException("Authenticated loan creator is required");
        }

        // Find the employee using the official Employee ID
        Employee employee = employeeRepository.findByEmployeeId(employeeId).orElseThrow(() -> new EmployeeNotFoundException("Employee with Employee ID: " + employeeId + " does not exist"));

        if (loan.getLoanApplicationId() == null || loan.getLoanApplicationId().isBlank()) {
            throw new IllegalArgumentException("Loan application ID is required");
        }
        loan.setLoanApplicationId(loan.getLoanApplicationId().trim());

        if (loan.getRequestedAmount() == null || loan.getRequestedAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Requested amount must be greater than zero");
        }
        if (loan.getTenureMonths() == null || loan.getTenureMonths() < 1) {
            throw new IllegalArgumentException("Loan tenure must be at least one month");
        }

        // Prevent duplicate Loan Application ID
        if (loanRepository.existsByLoanApplicationId(loan.getLoanApplicationId())) {

            throw new DuplicateLoanException("Loan Application ID already exists: " + loan.getLoanApplicationId());

        }

        // Connect the loan with the employee
        loan.setEmployee(employee);
        loan.setCreatedBy(authenticatedCreator.trim());
        loan.setRejectedBy(null);
        loan.setRejectedAt(null);

        applyGuarantorSelection(loan, employee, guarantorEmployeeId, legacyGuarantorName);

        // Every new loan starts as DRAFT
        loan.setLoanStatus(Loan.LoanStatus.DRAFT);

        // Eligibility must be checked before approval
        loan.setEligibilityStatus(Loan.EligibilityStatus.PENDING);

        // Approval-controlled fields must start empty
        loan.setApprovedAmount(null);
        loan.setApprovedTenureMonths(null);
        loan.setApprovalDate(null);
        loan.setApprovedBy(null);
        loan.setApprovedAt(null);
        loan.setInterestRate(null);

        // Rejection information must start empty
        loan.setRejectionReason(null);

        return loanRepository.save(loan);

    }


    ///==================== Read Loan ====================///




    ///---------- Get All Loans ----------
    @Transactional(readOnly = true)
    public List<Loan> getAllLoansList() {

        return loanRepository.findAll();

    }


    ///---------- Get Loan By Database ID (id) ----------
    @Transactional(readOnly = true)
    public Loan getLoanById(Long id) {

        return loanRepository.findById(id).orElseThrow(() -> new LoanNotFoundException("Loan with database ID: " + id + " does not exist"));

    }


    ///---------- Get Loan By Application ID ----------
    @Transactional(readOnly = true)
    public Loan getLoanByApplicationId(String loanApplicationId) {

        return loanRepository.findByLoanApplicationId(loanApplicationId).orElseThrow(() -> new LoanNotFoundException("Loan with Loan Application ID: " + loanApplicationId + " does not exist"));

    }


    ///---------- Get All Loans Of An Employee ----------
    @Transactional(readOnly = true)
    public List<Loan> getEmployeeLoans(String employeeId) {

        // Verify that the employee exists
        if (!employeeRepository.existsByEmployeeId(employeeId)) {

            throw new EmployeeNotFoundException("Employee with Employee ID: " + employeeId + " does not exist");

        }

        return loanRepository.findByEmployeeEmployeeIdOrderByApplicationDateDesc(employeeId);

    }


    ///---------- Get Loans By Loan Status ----------
    @Transactional(readOnly = true)
    public List<Loan> getLoansByLoanStatus(Loan.LoanStatus loanStatus) {

        return loanRepository.findByLoanStatusOrderByApplicationDateDesc(loanStatus);

    }


    ///---------- Get Loans By Eligibility Status ----------
    @Transactional(readOnly = true)
    public List<Loan> getLoansByEligibilityStatus(Loan.EligibilityStatus eligibilityStatus) {

        return loanRepository.findByEligibilityStatusOrderByApplicationDateDesc(eligibilityStatus);

    }


    ///---------- Get Loans By Loan Type ----------
    @Transactional(readOnly = true)
    public List<Loan> getLoansByType(Loan.LoanType loanType) {

        return loanRepository.findByLoanTypeOrderByApplicationDateDesc(loanType);

    }


    ///---------- Get Employee Loans By Status ----------
    @Transactional(readOnly = true)
    public List<Loan> getEmployeeLoansByStatus(String employeeId, Loan.LoanStatus loanStatus) {

        // Verify that the employee exists
        if (!employeeRepository.existsByEmployeeId(employeeId)) {

            throw new EmployeeNotFoundException("Employee with Employee ID: " + employeeId + " does not exist");

        }

        return loanRepository.findByEmployeeEmployeeIdAndLoanStatusOrderByApplicationDateDesc(employeeId, loanStatus);

    }


    ///---------- Get Employee Loans By Eligibility Status ----------
    @Transactional(readOnly = true)
    public List<Loan> getEmployeeLoansByEligibilityStatus(String employeeId, Loan.EligibilityStatus eligibilityStatus) {

        // Verify that the employee exists
        if (!employeeRepository.existsByEmployeeId(employeeId)) {

            throw new EmployeeNotFoundException("Employee with Employee ID: " + employeeId + " does not exist");

        }

        return loanRepository.findByEmployeeEmployeeIdAndEligibilityStatusOrderByApplicationDateDesc(employeeId, eligibilityStatus);

    }


    /// ==================== Update Loan ====================///

    // Update editable information of a DRAFT loan
    @Transactional
    public Loan updateLoan(Long id, Loan updatedLoan) {

        return updateLoan(id, updatedLoan, null, null);
    }

    @Transactional
    public Loan updateLoan(Long id, Loan updatedLoan, String guarantorEmployeeId,
                           String legacyGuarantorName) {

        // Find the existing loan
        Loan existingLoan = loanRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new LoanNotFoundException("Loan with database ID: " + id + " does not exist"));

        // Only DRAFT loans can be changed through normal update
        if (existingLoan.getLoanStatus() != Loan.LoanStatus.DRAFT) {
            throw new IllegalStateException("Only DRAFT loans can be updated: " + id);

        }

        applyGuarantorSelection(updatedLoan, existingLoan.getEmployee(),
                guarantorEmployeeId, legacyGuarantorName);

        // Copy editable fields while protecting system-controlled fields
        BeanUtils.copyProperties(
                updatedLoan,
                existingLoan,
                "id",
                "loanApplicationId",
                "employee",
                "createdBy",
                "approvedAmount",
                "approvedTenureMonths",
                "approvalDate",
                "approvedBy",
                "approvedAt",
                "rejectedBy",
                "rejectedAt",
                "interestRate",
                "loanStatus",
                "eligibilityStatus",
                "rejectionReason",
                "createdAt",
                "updatedAt"
        );

        return loanRepository.save(existingLoan);

    }


    ///==================== Loan Workflow ====================///

    ///---------- Submit A DRAFT Loan For Review ----------
    @Transactional
    public Loan submitLoan(Long id) {

        Loan existingLoan = findLoanForUpdate(id);

        // Only DRAFT loans can be submitted
        if (existingLoan.getLoanStatus() != Loan.LoanStatus.DRAFT) {
            throw new IllegalStateException("Only DRAFT loans can be submitted: " + id);

        }

        existingLoan.setLoanStatus(Loan.LoanStatus.SUBMITTED);

        return loanRepository.save(existingLoan);

    }


    ///---------- Move A Submitted Loan Into Review ----------
    @Transactional
    public Loan startLoanReview(Long id) {

        Loan existingLoan = findLoanForUpdate(id);

        // Only SUBMITTED loans can enter review
        if (existingLoan.getLoanStatus() != Loan.LoanStatus.SUBMITTED) {
            throw new IllegalStateException("Only SUBMITTED loans can enter review: " + id);

        }

        existingLoan.setLoanStatus(Loan.LoanStatus.UNDER_REVIEW);

        return loanRepository.save(existingLoan);

    }


    ///---------- Update Eligibility While The Loan Is Under Review ----------
    @Transactional
    public Loan updateEligibilityStatus(Long id, Loan.EligibilityStatus eligibilityStatus) {

        Loan existingLoan = findLoanForUpdate(id);

        // Eligibility is determined during loan review
        if (existingLoan.getLoanStatus() != Loan.LoanStatus.UNDER_REVIEW) {
            throw new IllegalStateException("Eligibility can only be updated while the loan is UNDER_REVIEW: " + id);

        }

        // PENDING is the initial system state, not a review result
        if (eligibilityStatus == Loan.EligibilityStatus.PENDING) {
            throw new IllegalArgumentException("Eligibility review must result in ELIGIBLE or NOT_ELIGIBLE");

        }

        existingLoan.setEligibilityStatus(eligibilityStatus);

        return loanRepository.save(existingLoan);

    }


    ///---------- Approve An Eligible Loan That Is Under Review ----------
    @Transactional
    public Loan approveLoan(Long id, BigDecimal approvedAmount, BigDecimal interestRate,
                            Integer approvedTenureMonths,
                            String authenticatedApprover) {

        Loan existingLoan = findLoanForUpdate(id);
        requireLoanApprovalEnabled();

        // Only loans under review can be approved
        if (existingLoan.getLoanStatus() != Loan.LoanStatus.UNDER_REVIEW) {
            throw new IllegalStateException("Only UNDER_REVIEW loans can be approved: " + id);

        }

        // Only eligible loans can be approved
        if (existingLoan.getEligibilityStatus() != Loan.EligibilityStatus.ELIGIBLE) {
            throw new IllegalStateException("Only ELIGIBLE loans can be approved: " + id);

        }

        // Approved amount is required
        if (approvedAmount == null) {
            throw new IllegalArgumentException("Approved amount is required");

        }

        // Approved amount must be greater than zero
        if (approvedAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Approved amount must be greater than zero");

        }

        // Approved amount cannot exceed the requested amount
        if (approvedAmount.compareTo(existingLoan.getRequestedAmount()) > 0) {
            throw new IllegalArgumentException("Approved amount cannot exceed the requested amount");

        }

        // Interest rate is required during approval
        if (interestRate == null) {
            throw new IllegalArgumentException("Interest rate is required");

        }

        // Interest rate cannot be negative
        if (interestRate.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Interest rate cannot be negative");

        }

        int approvedTerm = approvedTenureMonths == null
                ? existingLoan.getTenureMonths() : approvedTenureMonths;
        if (approvedTerm < 1) {
            throw new IllegalArgumentException("Approved tenure must be at least one month");
        }

        if (authenticatedApprover == null || authenticatedApprover.isBlank()) {
            throw new IllegalArgumentException("Authenticated approver is required");
        }
        if (existingLoan.getCreatedBy() == null || existingLoan.getCreatedBy().isBlank()) {
            throw new IllegalStateException("This legacy loan has no recorded creator; independent approval cannot be verified.");
        }
        if (existingLoan.getCreatedBy().equalsIgnoreCase(authenticatedApprover.trim())) {
            throw new IllegalStateException("The loan creator cannot approve their own loan.");
        }

        // Store approval information
        existingLoan.setApprovedAmount(approvedAmount);
        existingLoan.setInterestRate(interestRate);
        existingLoan.setApprovedTenureMonths(approvedTerm);
        existingLoan.setApprovalDate(LocalDate.now());
        existingLoan.setApprovedBy(authenticatedApprover.trim());
        existingLoan.setApprovedAt(LocalDateTime.now());
        existingLoan.setRejectedBy(null);
        existingLoan.setRejectedAt(null);

        // Clear any previous rejection information
        existingLoan.setRejectionReason(null);

        existingLoan.setLoanStatus(Loan.LoanStatus.APPROVED);

        return loanRepository.save(existingLoan);

    }


    ///---------- Reject A Loan That Is Currently Under Review ----------
    @Transactional
    public Loan rejectLoan(Long id, String rejectionReason, String authenticatedReviewer) {

        Loan existingLoan = findLoanForUpdate(id);
        requireLoanApprovalEnabled();

        // Only loans under review can be rejected
        if (existingLoan.getLoanStatus() != Loan.LoanStatus.UNDER_REVIEW) {
            throw new IllegalStateException("Only UNDER_REVIEW loans can be rejected: " + id);

        }

        // Rejection reason is required
        if (rejectionReason == null || rejectionReason.isBlank()) {
            throw new IllegalArgumentException("Rejection reason is required");

        }
        if (rejectionReason.trim().length() > 1000) {
            throw new IllegalArgumentException("Rejection reason cannot exceed 1000 characters");
        }
        if (authenticatedReviewer == null || authenticatedReviewer.isBlank()
                || authenticatedReviewer.trim().length() > 100) {
            throw new IllegalArgumentException("Authenticated reviewer is required");
        }
        if (existingLoan.getCreatedBy() == null || existingLoan.getCreatedBy().isBlank()) {
            throw new IllegalStateException("This legacy loan has no recorded creator; independent review cannot be verified.");
        }
        if (existingLoan.getCreatedBy().equalsIgnoreCase(authenticatedReviewer.trim())) {
            throw new IllegalStateException("The loan creator cannot reject their own loan.");
        }

        // Clear approval-controlled information
        existingLoan.setApprovedAmount(null);
        existingLoan.setApprovedTenureMonths(null);
        existingLoan.setApprovalDate(null);
        existingLoan.setApprovedBy(null);
        existingLoan.setApprovedAt(null);
        existingLoan.setInterestRate(null);

        existingLoan.setRejectionReason(rejectionReason.trim());
        existingLoan.setRejectedBy(authenticatedReviewer.trim());
        existingLoan.setRejectedAt(LocalDateTime.now());

        existingLoan.setLoanStatus(Loan.LoanStatus.REJECTED);

        return loanRepository.save(existingLoan);

    }


    ///---------- Cancel A Loan Before It Enters Formal Review ----------
    @Transactional
    public Loan cancelLoan(Long id) {

        Loan existingLoan = findLoanForUpdate(id);

        // Only DRAFT or SUBMITTED loans can be canceled here
        if (existingLoan.getLoanStatus() != Loan.LoanStatus.DRAFT && existingLoan.getLoanStatus() != Loan.LoanStatus.SUBMITTED) {
            throw new IllegalStateException("Only DRAFT or SUBMITTED loans can be canceled: " + id);

        }

        existingLoan.setLoanStatus(Loan.LoanStatus.CANCELLED);

        return loanRepository.save(existingLoan);


    }

    private Loan findLoanForUpdate(Long id) {
        return loanRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new LoanNotFoundException(
                        "Loan with database ID: " + id + " does not exist"));
    }

    public boolean isLoanApprovalEnabled() {
        return loanApprovalEnabled;
    }

    private void requireLoanApprovalEnabled() {
        if (!loanApprovalEnabled) {
            throw new IllegalStateException(
                    "Loan approval and rejection are disabled until independent checker authority and bank policy are approved and configured.");
        }
    }

    private void applyGuarantorSelection(Loan target, Employee borrower,
                                         String requestedGuarantorEmployeeId,
                                         String requestedLegacyName) {
        boolean explicitSelection = requestedGuarantorEmployeeId != null
                || requestedLegacyName != null;
        String employeeId = explicitSelection
                ? requestedGuarantorEmployeeId
                : (target.getGuarantorEmployee() == null
                    ? null : target.getGuarantorEmployee().getEmployeeId());
        String legacyName = explicitSelection
                ? requestedLegacyName : target.getLegacyGuarantorName();

        Employee guarantor = null;
        if (employeeId != null && !employeeId.isBlank()) {
            guarantor = employeeRepository.findByEmployeeId(employeeId.trim())
                    .orElseThrow(() -> new EmployeeNotFoundException(
                            "Guarantor employee ID does not exist: " + employeeId.trim()));
            if (borrower.getId().equals(guarantor.getId())) {
                throw new IllegalArgumentException("Borrower cannot be their own guarantor");
            }
            if (legacyName != null && !legacyName.isBlank()) {
                throw new IllegalArgumentException(
                        "Enter either an internal guarantor Employee ID or a legacy guarantor name");
            }
        } else if (legacyName != null && !legacyName.isBlank()) {
            legacyName = legacyName.trim();
            if (legacyName.length() > 200) {
                throw new IllegalArgumentException("Legacy guarantor name cannot exceed 200 characters");
            }
        } else {
            legacyName = null;
        }

        target.setGuarantorEmployee(guarantor);
        target.setLegacyGuarantorName(legacyName);
    }
}
