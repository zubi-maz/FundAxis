package com.fundaxis.service;

import com.fundaxis.entity.Contribution;
import com.fundaxis.entity.Employee;
import com.fundaxis.exception.ContributionNotFoundException;
import com.fundaxis.exception.DuplicateContributionException;
import com.fundaxis.exception.EmployeeNotFoundException;
import com.fundaxis.repository.ContributionRepository;
import com.fundaxis.repository.EmployeeRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class ContributionService {

    /** Business-facing input for an atomic monthly contribution batch. */
    public record BatchContributionInput(String employeeId, BigDecimal amount,
                                         com.fundaxis.entity.ContributionPayment.PaymentMethod intendedCollectionMethod,
                                         BigDecimal shareValue, String remarks) {
        public BatchContributionInput(String employeeId, BigDecimal amount,
                                      com.fundaxis.entity.ContributionPayment.PaymentMethod intendedCollectionMethod) {
            this(employeeId, amount, intendedCollectionMethod, null, null);
        }
        public BatchContributionInput(String employeeId, BigDecimal amount) {
            this(employeeId, amount, null, null, null);
        }
    }

    private final ContributionRepository contributionRepository;
    private final EmployeeRepository employeeRepository;
    private final ContributionPaymentService paymentService;

    // ---------- Constructor Dependency Injection ----------
    public ContributionService(
            ContributionRepository contributionRepository,
            EmployeeRepository employeeRepository,
            ContributionPaymentService paymentService) {

        this.contributionRepository = contributionRepository;
        this.employeeRepository = employeeRepository;
        this.paymentService = paymentService;
    }

    // ---------- Read ----------

    // Get all contributions
    @Transactional(readOnly = true)
    public List<Contribution> getAllContributionsList() {
        return contributionRepository.findAll();
    }

    // Get one contribution by ID
    @Transactional(readOnly = true)
    public Contribution getContributionById(Long id) {
        return findContributionById(id);
    }

    // Get all contributions belonging to an employee
    @Transactional(readOnly = true)
    public List<Contribution> getEmployeeContributionsByEmployeeId(
            String employeeId) {

        return contributionRepository.findByEmployeeEmployeeId(employeeId);
    }

    // Get all contributions belonging to an employee
    // Retained for compatibility with existing callers
    @Transactional(readOnly = true)
    public List<Contribution> getContributionsByEmployeeId(
            String employeeId) {

        return contributionRepository.findByEmployeeEmployeeId(employeeId);
    }

    // Find a contribution by ID
    @Transactional(readOnly = true)
    public Contribution findContributionById(Long id) {

        return contributionRepository.findById(id)
                .orElseThrow(() ->
                        new ContributionNotFoundException(
                                "Contribution with ID: " + id
                                        + " does not exist"));
    }

    // ---------- Create ----------

    @Transactional
    public Contribution createContribution(
            String employeeId,
            Contribution contribution) {

        // Validate required contribution fields
        if (contribution == null
                || contribution.getContributionMonth() == null
                || contribution.getContributionAmount() == null
                || contribution.getContributionAmount().signum() <= 0) {

            throw new IllegalArgumentException(
                    "Valid contribution month and positive amount are required");
        }

        // Find the employee using the official Employee ID
        Employee employee = employeeRepository.findByEmployeeId(employeeId)
                .orElseThrow(() ->
                        new EmployeeNotFoundException(
                                "Employee with Employee ID: "
                                        + employeeId + " does not exist"));

        // Normalize the contribution month to the first day
        LocalDate month = contribution.getContributionMonth()
                .withDayOfMonth(1);

        // Prevent duplicate contributions for the same employee and month
        if (contributionRepository
                .existsByEmployeeEmployeeIdAndContributionMonth(
                        employeeId, month)) {

            throw new DuplicateContributionException(
                    "Contribution already exists for Employee ID "
                            + employeeId + " for month " + month);
        }

        // Create a new contribution using only permitted input fields
        Contribution created = new Contribution();
        created.setEmployee(employee);
        created.setContributionMonth(month);
        created.setContributionAmount(
                contribution.getContributionAmount());
        validateOptionalContributionFields(contribution.getShareValue(), contribution.getRemarks());
        created.setShareValue(contribution.getShareValue());
        created.setRemarks(cleanRemarks(contribution.getRemarks()));

        // New contributions always start as pending
        created.setContributionStatus(
                Contribution.ContributionStatus.PENDING);

        // Pending contributions do not have a payment date
        created.setPaymentDate(null);

        return contributionRepository.save(created);
    }

    /**
     * Validates a complete batch before writing, then saves every due in one
     * transaction. Payments are intentionally not created by this workflow.
     */
    @Transactional
    public int createContributionBatch(
            LocalDate contributionMonth,
            List<BatchContributionInput> entries) {

        if (contributionMonth == null) {
            throw new IllegalArgumentException("Contribution month is required.");
        }
        if (entries == null || entries.isEmpty() || entries.size() > 50) {
            throw new IllegalArgumentException(
                    "A batch must contain between 1 and 50 contribution rows.");
        }

        LocalDate normalizedMonth = contributionMonth.withDayOfMonth(1);
        Set<String> batchEmployeeIds = new HashSet<>();
        List<Contribution> contributions = new ArrayList<>(entries.size());

        // Validate the complete request shape and in-batch duplicates before queries.
        for (BatchContributionInput entry : entries) {
            if (entry == null || entry.employeeId() == null
                    || entry.employeeId().isBlank()) {
                throw new IllegalArgumentException("Employee ID is required for every row.");
            }
            if (entry.amount() == null || entry.amount().signum() <= 0
                    || entry.amount().stripTrailingZeros().scale() > 2) {
                throw new IllegalArgumentException(
                        "Each contribution amount must be positive and use no more than two decimal places.");
            }
            validateOptionalContributionFields(entry.shareValue(), entry.remarks());
            if (entry.intendedCollectionMethod() != null
                    && !java.util.Set.of(
                            com.fundaxis.entity.ContributionPayment.PaymentMethod.HO_HAND,
                            com.fundaxis.entity.ContributionPayment.PaymentMethod.BANK_FT,
                            com.fundaxis.entity.ContributionPayment.PaymentMethod.ITD_HAND)
                    .contains(entry.intendedCollectionMethod())) {
                throw new IllegalArgumentException("Select a supported intended collection method.");
            }

            String employeeId = entry.employeeId().trim();
            if (!batchEmployeeIds.add(employeeId.toUpperCase(Locale.ROOT))) {
                throw new DuplicateContributionException(
                        "Employee ID " + employeeId + " appears more than once in this batch.");
            }
        }

        // Resolve employees and verify stored obligations before the first write.
        for (BatchContributionInput entry : entries) {
            String employeeId = entry.employeeId().trim();
            Employee employee = employeeRepository.findByEmployeeId(employeeId)
                    .orElseThrow(() -> new EmployeeNotFoundException(
                            "Employee with Employee ID: " + employeeId + " does not exist."));

            if (contributionRepository
                    .existsByEmployeeEmployeeIdAndContributionMonth(
                            employeeId, normalizedMonth)) {
                throw new DuplicateContributionException(
                        "Contribution already exists for Employee ID " + employeeId
                                + " for month " + normalizedMonth + ".");
            }

            Contribution created = new Contribution();
            created.setEmployee(employee);
            created.setContributionMonth(normalizedMonth);
            created.setContributionAmount(entry.amount());
            created.setContributionStatus(Contribution.ContributionStatus.PENDING);
            created.setPaymentDate(null);
            created.setIntendedCollectionMethod(entry.intendedCollectionMethod());
            created.setShareValue(entry.shareValue());
            created.setRemarks(cleanRemarks(entry.remarks()));
            contributions.add(created);
        }

        try {
            return contributionRepository.saveAllAndFlush(contributions).size();
        } catch (DataIntegrityViolationException exception) {
            // The unique employee/month constraint also protects concurrent saves.
            throw new DuplicateContributionException(
                    "One or more contribution rows conflict with an existing record. "
                            + "No rows from this batch were saved.", exception);
        }
    }

    private static void validateOptionalContributionFields(BigDecimal shareValue, String remarks) {
        if (shareValue != null && (shareValue.signum() < 0
                || shareValue.stripTrailingZeros().scale() > 2
                || shareValue.precision() - shareValue.scale() > 18)) {
            throw new IllegalArgumentException(
                    "Share Value must be zero or greater and use no more than two decimal places.");
        }
        if (remarks != null && remarks.length() > 500) {
            throw new IllegalArgumentException("Remarks must be 500 characters or fewer.");
        }
    }

    private static String cleanRemarks(String remarks) {
        return remarks == null || remarks.isBlank() ? null : remarks.trim();
    }

    // ---------- Mark Contribution as Paid ----------

    // Preserves the existing API and Admin Controller method signature.
    // Delegates payment processing to record the payment ledger entry.
    @Transactional
    public Contribution markContributionAsPaid(
            Long id,
            LocalDate paymentDate) {

        return paymentService.payRemainingLegacy(id, paymentDate);
    }

    // ---------- Update ----------

    @Transactional
    public Contribution updateContribution(
            Long id,
            Contribution updatedContribution) {

        // Find the existing contribution
        Contribution existing = findContributionById(id);

        // Only pending contributions can be updated
        if (existing.getContributionStatus()
                != Contribution.ContributionStatus.PENDING) {

            throw new IllegalStateException(
                    "Only pending contributions can be updated: " + id);
        }

        // Prevent editing contributions that have payment history
        if (paymentService.hasActivePayments(id)
                || !paymentService.getSummary(id).payments().isEmpty()) {

            throw new IllegalStateException(
                    "Cannot edit a contribution with payment history: " + id);
        }

        // Validate the updated fields
        if (updatedContribution == null
                || updatedContribution.getContributionMonth() == null
                || updatedContribution.getContributionAmount() == null
                || updatedContribution.getContributionAmount().signum() <= 0) {

            throw new IllegalArgumentException(
                    "Valid contribution month and positive amount are required");
        }

        // Normalize the contribution month
        LocalDate month = updatedContribution.getContributionMonth()
                .withDayOfMonth(1);

        // Keep the contribution associated with its original employee
        String employeeId = existing.getEmployee().getEmployeeId();

        // Prevent duplicate contributions for the same employee and month
        if (contributionRepository
                .existsByEmployeeEmployeeIdAndContributionMonthAndIdNot(
                        employeeId, month, id)) {

            throw new DuplicateContributionException(
                    "Contribution already exists for Employee ID "
                            + employeeId + " for month " + month);
        }

        // Update only the permitted fields
        existing.setContributionMonth(month);
        existing.setContributionAmount(
                updatedContribution.getContributionAmount());

        return contributionRepository.save(existing);
    }

    // ---------- Cancel Contribution ----------

    @Transactional
    public Contribution cancelContribution(Long id) {

        // Find the existing contribution
        Contribution existing = findContributionById(id);

        // Only pending contributions can be canceled
        if (existing.getContributionStatus()
                != Contribution.ContributionStatus.PENDING) {

            throw new IllegalStateException(
                    "Only pending contributions can be canceled: " + id);
        }

        // Do not cancel contributions with payment history
        if (!paymentService.getSummary(id).payments().isEmpty()) {

            throw new IllegalStateException(
                    "Cannot cancel contribution with payment history: " + id);
        }

        // Update the contribution status
        existing.setContributionStatus(
                Contribution.ContributionStatus.CANCELED);

        // Canceled contributions do not have a payment date
        existing.setPaymentDate(null);

        return contributionRepository.save(existing);
    }



    @Transactional(readOnly = true)
    public List<Contribution> getContributionsForMonth(LocalDate month) {

        if (month == null) {
            throw new IllegalArgumentException(
                    "Contribution month is required."
            );
        }

        return contributionRepository.findByContributionMonth(
                month.withDayOfMonth(1)
        );
    }
}
