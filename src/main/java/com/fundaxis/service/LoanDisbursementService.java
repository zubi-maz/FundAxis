package com.fundaxis.service;

import com.fundaxis.entity.Loan;
import com.fundaxis.entity.LoanDisbursement;
import com.fundaxis.exception.LoanDisbursementNotFoundException;
import com.fundaxis.exception.LoanNotFoundException;
import com.fundaxis.repository.LoanDisbursementRepository;
import com.fundaxis.repository.LoanRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
public class LoanDisbursementService {

    private final LoanDisbursementRepository loanDisbursementRepository;
    private final LoanRepository loanRepository;

    ///---------- Inject Required Repositories ----------
    public LoanDisbursementService(LoanDisbursementRepository loanDisbursementRepository, LoanRepository loanRepository) {
        this.loanDisbursementRepository = loanDisbursementRepository;
        this.loanRepository = loanRepository;

    }


    ///==================== Create Loan Disbursement ====================///

    ///---------- Create A New Disbursement Request For An Approved Loan ----------
    @Transactional
    public LoanDisbursement createDisbursement(Long loanId, LoanDisbursement disbursement) {

        Loan loan = findLoanByIdForUpdate(loanId);

        // Only approved loans can enter the disbursement workflow
        if (loan.getLoanStatus() != Loan.LoanStatus.APPROVED) {
            throw new IllegalStateException("Only approved loans can be disbursed: " + loanId);

        }

        // Approved amount must exist before disbursement
        if (loan.getApprovedAmount() == null) {
            throw new IllegalStateException("Loan does not have an approved amount: " + loanId);

        }

        // Protect service from invalid amount when called outside controller validation
        if (disbursement.getDisbursementAmount() == null || disbursement.getDisbursementAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Disbursement amount must be greater than zero");

        }

        // Prevent multiple active disbursement workflows for the same loan
        boolean pendingExists = loanDisbursementRepository.existsByLoanIdAndDisbursementStatus(loanId, LoanDisbursement.DisbursementStatus.PENDING);

        boolean processingExists = loanDisbursementRepository.existsByLoanIdAndDisbursementStatus(loanId, LoanDisbursement.DisbursementStatus.PROCESSING);

        if (pendingExists || processingExists) {
            throw new IllegalStateException("Loan already has a pending or processing disbursement: " + loanId);

        }

        BigDecimal remainingAmount = getRemainingApprovedAmount(loanId);

        // New disbursement cannot exceed the remaining approved amount
        if (disbursement.getDisbursementAmount().compareTo(remainingAmount) > 0) {
            throw new IllegalArgumentException("Disbursement amount exceeds remaining approved amount. Remaining amount: " + remainingAmount);

        }

        // No further amount remains available
        if (remainingAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Loan has already been fully disbursed: " + loanId);

        }

        // Connect the disbursement to the loan
        disbursement.setLoan(loan);

        // Every new disbursement starts as PENDING
        disbursement.setDisbursementStatus(LoanDisbursement.DisbursementStatus.PENDING);

        // Workflow-controlled fields must not be supplied during creation
        disbursement.setDisbursementDate(null);
        disbursement.setProcessingDate(null);
        disbursement.setProcessedBy(null);
        disbursement.setReferenceNumber(null);
        disbursement.setFailureReason(null);
        disbursement.setCancellationReason(null);

        return loanDisbursementRepository.save(disbursement);

    }


    ///==================== Disbursement Amount Calculation ====================///

    ///---------- Calculate Total Amount Successfully Disbursed For A Loan ----------
    public BigDecimal getTotalDisbursedAmount(Long loanId) {

        // Ensure the parent loan exists
        findLoanById(loanId);

        List<LoanDisbursement> disbursements = loanDisbursementRepository.findByLoanIdAndDisbursementStatusOrderByCreatedAtDesc(loanId, LoanDisbursement.DisbursementStatus.DISBURSED);

        BigDecimal totalDisbursed = BigDecimal.ZERO;

        for (LoanDisbursement disbursement : disbursements) {

            totalDisbursed = totalDisbursed.add(disbursement.getDisbursementAmount());

        }

        return totalDisbursed;

    }


    ///---------- Calculate The Approved Amount That Has Not Yet Been Disbursed ----------
    public BigDecimal getRemainingApprovedAmount(Long loanId) {

        Loan loan = findLoanById(loanId);

        if (loan.getApprovedAmount() == null) {
            throw new IllegalStateException("Loan does not have an approved amount: " + loanId);

        }

        BigDecimal alreadyDisbursed = getTotalDisbursedAmount(loanId);

        return loan.getApprovedAmount().subtract(alreadyDisbursed);

    }


    ///==================== Read Loan Disbursement ====================///

    ///---------- Get All Disbursement Records ----------
    public List<LoanDisbursement> getAllDisbursements() {

        return loanDisbursementRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));

    }


    ///---------- Get One Disbursement By Database ID (id) ----------
    public LoanDisbursement getDisbursementById(Long id) {

        return findDisbursementById(id);

    }


    ///---------- Get All Disbursements For A Specific Loan ----------
    public List<LoanDisbursement> getDisbursementsByLoanId(Long loanId) {

        // Confirm that the loan exists
        findLoanById(loanId);

        return loanDisbursementRepository.findByLoanIdOrderByCreatedAtDesc(loanId);

    }


    ///---------- Get All Disbursements Belonging To An Employee's Loans ----------
    public List<LoanDisbursement> getEmployeeDisbursements(String employeeId) {

        return loanDisbursementRepository.findByLoanEmployeeEmployeeIdOrderByCreatedAtDesc(employeeId);

    }


    ///---------- Get All Disbursements With A Specific Status ----------
    public List<LoanDisbursement> getDisbursementsByStatus(LoanDisbursement.DisbursementStatus disbursementStatus) {

        return loanDisbursementRepository.findByDisbursementStatusOrderByCreatedAtDesc(disbursementStatus);

    }


    ///---------- Get All Disbursements Using A Specific Payment Method ----------
    public List<LoanDisbursement> getDisbursementsByPaymentMethod(LoanDisbursement.PaymentMethod paymentMethod) {

        return loanDisbursementRepository.findByPaymentMethodOrderByCreatedAtDesc(paymentMethod);

    }


    ///---------- Find One Disbursement Using Its Transaction Reference ----------
    public LoanDisbursement getDisbursementByReferenceNumber(String referenceNumber) {

        if (referenceNumber == null || referenceNumber.isBlank()) {
            throw new IllegalArgumentException("Reference number is required");

        }

        return loanDisbursementRepository.findByReferenceNumber(referenceNumber.trim()).orElseThrow(() -> new LoanDisbursementNotFoundException("Disbursement with reference number: " + referenceNumber + " does not exist"));

    }


    ///---------- Get An Employee's Disbursements With A Specific Status ----------
    public List<LoanDisbursement> getEmployeeDisbursementsByStatus(String employeeId, LoanDisbursement.DisbursementStatus disbursementStatus) {

        return loanDisbursementRepository.findByLoanEmployeeEmployeeIdAndDisbursementStatusOrderByCreatedAtDesc(employeeId, disbursementStatus);

    }


    ///---------- Get A Loan's Disbursements With A Specific Status ----------
    public List<LoanDisbursement> getLoanDisbursementsByStatus(Long loanId, LoanDisbursement.DisbursementStatus disbursementStatus) {

        // Confirm that the parent loan exists
        findLoanById(loanId);

        return loanDisbursementRepository.findByLoanIdAndDisbursementStatusOrderByCreatedAtDesc(loanId, disbursementStatus);

    }


    ///==================== Loan Disbursement Workflow ====================///

    ///---------- Start Processing A Pending Disbursement ----------
    @Transactional
    public LoanDisbursement startProcessing(Long id) {

        LoanDisbursement disbursement = findDisbursementByIdForUpdate(id);

        // Only PENDING disbursements can start processing
        if (disbursement.getDisbursementStatus() != LoanDisbursement.DisbursementStatus.PENDING) {
            throw new IllegalStateException("Only pending disbursements can start processing: " + id);

        }

        // Parent loan must still be approved
        if (disbursement.getLoan().getLoanStatus() != Loan.LoanStatus.APPROVED) {
            throw new IllegalStateException("The parent loan is not approved for disbursement: " + disbursement.getLoan().getId());

        }

        disbursement.setDisbursementStatus(LoanDisbursement.DisbursementStatus.PROCESSING);

        disbursement.setProcessingDate(LocalDate.now());

        return loanDisbursementRepository.save(disbursement);

    }


    ///---------- Complete A Processing Disbursement ----------
    @Transactional
    public LoanDisbursement completeDisbursement(Long id, String processedBy, String referenceNumber) {

        LoanDisbursement disbursement = findDisbursementByIdForUpdate(id);

        // Only PROCESSING disbursements can be completed
        if (disbursement.getDisbursementStatus() != LoanDisbursement.DisbursementStatus.PROCESSING) {
            throw new IllegalStateException("Only processing disbursements can be completed: " + id);

        }

        // Processor information is required
        if (processedBy == null || processedBy.isBlank()) {
            throw new IllegalArgumentException("Processed by is required");

        }

        // Transaction reference is required when money is released
        if (referenceNumber == null || referenceNumber.isBlank()) {
            throw new IllegalArgumentException("Reference number is required");

        }

        String normalizedReferenceNumber = referenceNumber.trim();

        // Reference number must be unique
        if (loanDisbursementRepository.existsByReferenceNumber(normalizedReferenceNumber)) {
            throw new IllegalStateException("Reference number already exists: " + normalizedReferenceNumber);

        }

        Loan loan = disbursement.getLoan();

        if (loan.getLoanStatus() != Loan.LoanStatus.APPROVED) {
            throw new IllegalStateException("The parent loan is not approved for disbursement: " + loan.getId());

        }

        // Recheck the remaining approved amount before releasing money
        BigDecimal alreadyDisbursed = getTotalDisbursedAmount(loan.getId());

        BigDecimal remainingAmount = loan.getApprovedAmount().subtract(alreadyDisbursed);

        if (disbursement.getDisbursementAmount().compareTo(remainingAmount) > 0) {
            throw new IllegalStateException("Disbursement amount exceeds remaining approved amount. Remaining amount: " + remainingAmount);

        }

        disbursement.setProcessedBy(processedBy.trim());

        disbursement.setReferenceNumber(normalizedReferenceNumber);

        disbursement.setDisbursementDate(LocalDate.now());

        disbursement.setFailureReason(null);
        disbursement.setCancellationReason(null);

        disbursement.setDisbursementStatus(LoanDisbursement.DisbursementStatus.DISBURSED);

        LoanDisbursement savedDisbursement = loanDisbursementRepository.save(disbursement);

        // Calculate total including the disbursement just completed
        BigDecimal totalDisbursed = alreadyDisbursed.add(savedDisbursement.getDisbursementAmount());

        // Parent loan becomes DISBURSED only when the full approved amount has been successfully released
        if (totalDisbursed.compareTo(loan.getApprovedAmount()) == 0) {

            loan.setLoanStatus(Loan.LoanStatus.DISBURSED);

            loanRepository.save(loan);

        }

        return savedDisbursement;

    }


    ///---------- Mark A Processing Disbursement As Failed ----------
    @Transactional
    public LoanDisbursement failDisbursement(Long id, String failureReason) {

        LoanDisbursement disbursement = findDisbursementByIdForUpdate(id);

        // Only PROCESSING disbursements can fail
        if (disbursement.getDisbursementStatus() != LoanDisbursement.DisbursementStatus.PROCESSING) {
            throw new IllegalStateException("Only processing disbursements can be marked as failed: " + id);

        }

        if (failureReason == null || failureReason.isBlank()) {

            throw new IllegalArgumentException("Failure reason is required");

        }

        disbursement.setFailureReason(failureReason.trim());

        disbursement.setDisbursementDate(null);
        disbursement.setProcessedBy(null);
        disbursement.setReferenceNumber(null);
        disbursement.setCancellationReason(null);

        disbursement.setDisbursementStatus(LoanDisbursement.DisbursementStatus.FAILED);

        return loanDisbursementRepository.save(disbursement);

    }


    ///---------- Cancel A Pending Disbursement ----------
    @Transactional
    public LoanDisbursement cancelDisbursement(Long id, String cancellationReason) {

        LoanDisbursement disbursement = findDisbursementByIdForUpdate(id);

        // Only PENDING disbursements can be cancelled
        if (disbursement.getDisbursementStatus() != LoanDisbursement.DisbursementStatus.PENDING) {
            throw new IllegalStateException("Only pending disbursements can be cancelled: " + id);

        }

        if (cancellationReason == null || cancellationReason.isBlank()) {
            throw new IllegalArgumentException("Cancellation reason is required");

        }

        disbursement.setCancellationReason(cancellationReason.trim());

        disbursement.setFailureReason(null);
        disbursement.setDisbursementDate(null);
        disbursement.setProcessingDate(null);
        disbursement.setProcessedBy(null);
        disbursement.setReferenceNumber(null);

        disbursement.setDisbursementStatus(LoanDisbursement.DisbursementStatus.CANCELLED);

        return loanDisbursementRepository.save(disbursement);

    }


    ///==================== Helper Methods ====================///

    ///---------- Find A Loan Or Throw The Established Loan Not-Found Exception ----------
    private Loan findLoanById(Long loanId) {

        return loanRepository.findById(loanId).orElseThrow(() -> new LoanNotFoundException("Loan with database ID: " + loanId + " does not exist"));

    }

    private Loan findLoanByIdForUpdate(Long loanId) {
        return loanRepository.findByIdForUpdate(loanId)
                .orElseThrow(() -> new LoanNotFoundException(
                        "Loan with database ID: " + loanId + " does not exist"));
    }


    ///---------- Find A Disbursement Or Throw A Disbursement Not-Found Exception ----------
    private LoanDisbursement findDisbursementById(Long id) {

        return loanDisbursementRepository.findById(id).orElseThrow(() -> new LoanDisbursementNotFoundException("Loan disbursement with ID: " + id + " does not exist"));

    }

    /** Always lock the parent loan first so create/complete operations serialize
     * their approved-amount check, then lock and re-read the workflow row. */
    private LoanDisbursement findDisbursementByIdForUpdate(Long id) {
        LoanDisbursement observed = findDisbursementById(id);
        Long loanId = observed.getLoan().getId();
        findLoanByIdForUpdate(loanId);
        return loanDisbursementRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new LoanDisbursementNotFoundException(
                        "Loan disbursement with ID: " + id + " does not exist"));
    }



}
