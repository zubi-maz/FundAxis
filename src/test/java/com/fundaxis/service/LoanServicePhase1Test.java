package com.fundaxis.service;

import com.fundaxis.entity.Employee;
import com.fundaxis.entity.Loan;
import com.fundaxis.exception.EmployeeNotFoundException;
import com.fundaxis.repository.EmployeeRepository;
import com.fundaxis.repository.LoanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoanServicePhase1Test {

    @Mock private LoanRepository loanRepository;
    @Mock private EmployeeRepository employeeRepository;
    @InjectMocks private LoanService loanService;

    private Employee borrower;
    private Employee guarantor;

    @BeforeEach
    void setUp() {
        borrower = employee(11L, "EMP-11");
        guarantor = employee(12L, "EMP-12");
        ReflectionTestUtils.setField(loanService, "loanApprovalEnabled", true);
    }

    @Test
    void approvalAndRejectionRemainBlockedWhenPolicyFlagIsOff() {
        ReflectionTestUtils.setField(loanService, "loanApprovalEnabled", false);
        Loan loan = loanUnderReview();
        when(loanRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(loan));

        assertFalse(loanService.isLoanApprovalEnabled());
        assertThrows(IllegalStateException.class, () -> loanService.approveLoan(
                7L, new BigDecimal("900.00"), new BigDecimal("10.00"), 10, "checker.user"));
        assertThrows(IllegalStateException.class, () -> loanService.rejectLoan(
                7L, "Review reason", "checker.user"));
        verify(loanRepository, never()).save(any());
    }

    @Test
    void approvalUsesAuthenticatedApproverAndLocksLoan() {
        Loan loan = loanUnderReview();
        when(loanRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(loan));
        when(loanRepository.save(loan)).thenReturn(loan);

        Loan result = loanService.approveLoan(7L, new BigDecimal("900.00"),
                new BigDecimal("10.00"), 10, "checker.user");

        assertEquals(Loan.LoanStatus.APPROVED, result.getLoanStatus());
        assertEquals("checker.user", result.getApprovedBy());
        assertNotNull(result.getApprovedAt());
        assertEquals(new BigDecimal("900.00"), result.getApprovedAmount());
        assertEquals(10, result.getApprovedTenureMonths());
        verify(loanRepository).findByIdForUpdate(7L);
    }

    @Test
    void approvalRejectsAmountAboveRequestWithoutSaving() {
        Loan loan = loanUnderReview();
        when(loanRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(loan));

        assertThrows(IllegalArgumentException.class, () -> loanService.approveLoan(
                7L, new BigDecimal("1000.01"), new BigDecimal("10.00"), 12, "checker.user"));
        verify(loanRepository, never()).save(any());
    }

    @Test
    void creatorCannotApproveOwnLoan() {
        Loan loan = loanUnderReview();
        when(loanRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(loan));

        assertThrows(IllegalStateException.class, () -> loanService.approveLoan(
                7L, new BigDecimal("900.00"), new BigDecimal("10.00"), 12, "maker.user"));
        verify(loanRepository, never()).save(any());
    }

    @Test
    void rejectionRequiresIndependentReviewerAndPreservesAudit() {
        Loan loan = loanUnderReview();
        when(loanRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(loan));
        when(loanRepository.save(loan)).thenReturn(loan);

        Loan result = loanService.rejectLoan(7L, "Eligibility was not met", "checker.user");

        assertEquals(Loan.LoanStatus.REJECTED, result.getLoanStatus());
        assertEquals("Eligibility was not met", result.getRejectionReason());
        assertEquals("checker.user", result.getRejectedBy());
        assertNotNull(result.getRejectedAt());
    }

    @Test
    void createsMultipleLoansForSameBorrowerAndResolvesInternalGuarantorByBusinessId() {
        when(employeeRepository.findByEmployeeId("EMP-11"))
                .thenReturn(Optional.of(borrower));
        when(employeeRepository.findByEmployeeId("EMP-12"))
                .thenReturn(Optional.of(guarantor));
        when(loanRepository.existsByLoanApplicationId(any())).thenReturn(false);
        when(loanRepository.save(any(Loan.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Loan first = loanDraft("APP-001");
        Loan second = loanDraft("APP-002");
        Loan created = loanService.createLoan("EMP-11", "EMP-12", null, first, "maker.user");
        loanService.createLoan("EMP-11", null, "Legacy External Guarantor", second, "maker.user");

        assertSame(borrower, created.getEmployee());
        assertSame(guarantor, created.getGuarantorEmployee());
        assertEquals("Legacy External Guarantor", second.getLegacyGuarantorName());
        assertEquals("maker.user", created.getCreatedBy());
        verify(loanRepository, times(2)).save(any(Loan.class));
    }

    @Test
    void rejectsUnknownAndSelfGuarantor() {
        when(employeeRepository.findByEmployeeId("EMP-11"))
                .thenReturn(Optional.of(borrower));
        when(employeeRepository.findByEmployeeId("MISSING"))
                .thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> loanService.createLoan(
                "EMP-11", "EMP-11", null, loanDraft("APP-003"), "maker.user"));
        assertThrows(EmployeeNotFoundException.class, () -> loanService.createLoan(
                "EMP-11", "MISSING", null, loanDraft("APP-004"), "maker.user"));
        verify(loanRepository, never()).save(any());
    }

    private static Employee employee(Long id, String employeeId) {
        Employee employee = new Employee();
        employee.setId(id);
        employee.setEmployeeId(employeeId);
        return employee;
    }

    private static Loan loanUnderReview() {
        Loan loan = loanDraft("APP-REVIEW");
        loan.setLoanStatus(Loan.LoanStatus.UNDER_REVIEW);
        loan.setEligibilityStatus(Loan.EligibilityStatus.ELIGIBLE);
        loan.setCreatedBy("maker.user");
        return loan;
    }

    private static Loan loanDraft(String applicationId) {
        Loan loan = new Loan();
        loan.setLoanApplicationId(applicationId);
        loan.setRequestedAmount(new BigDecimal("1000.00"));
        loan.setTenureMonths(12);
        loan.setApplicationDate(LocalDate.now());
        loan.setLoanPurpose("Test purpose");
        loan.setLoanType(Loan.LoanType.PERSONAL);
        return loan;
    }
}
