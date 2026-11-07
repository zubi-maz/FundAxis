package com.fundaxis.service;

import com.fundaxis.entity.Contribution;
import com.fundaxis.entity.Employee;
import com.fundaxis.entity.ContributionPayment;
import com.fundaxis.exception.DuplicateContributionException;
import com.fundaxis.exception.EmployeeNotFoundException;
import com.fundaxis.repository.ContributionRepository;
import com.fundaxis.repository.EmployeeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContributionServiceBatchTest {

    @Mock private ContributionRepository contributionRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private ContributionPaymentService paymentService;

    private ContributionService service;
    private Employee employee;

    @BeforeEach
    void setUp() {
        service = new ContributionService(
                contributionRepository, employeeRepository, paymentService);
        employee = new Employee();
        employee.setEmployeeId("EMP-85");
        employee.setFullName("Iffat ara mimu");
    }

    @Test
    void createsOnePendingContributionWithoutRecordingPayment() {
        when(employeeRepository.findByEmployeeId("EMP-85"))
                .thenReturn(Optional.of(employee));
        when(contributionRepository
                .existsByEmployeeEmployeeIdAndContributionMonth(
                        "EMP-85", LocalDate.of(2026, 11, 1)))
                .thenReturn(false);
        when(contributionRepository.saveAllAndFlush(any(Iterable.class)))
                .thenAnswer(invocation -> {
                    List<Contribution> rows = new java.util.ArrayList<>();
                    for (Object row : (Iterable<?>) invocation.getArgument(0)) {
                        rows.add((Contribution) row);
                    }
                    return rows;
                });

        int savedCount = service.createContributionBatch(
                LocalDate.of(2026, 11, 1),
                List.of(new ContributionService.BatchContributionInput(
                        " EMP-85 ", new BigDecimal("3000.00"))));

        assertEquals(1, savedCount);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Iterable<Contribution>> savedRows =
                org.mockito.ArgumentCaptor.forClass(Iterable.class);
        verify(contributionRepository).saveAllAndFlush(savedRows.capture());
        Contribution contribution = savedRows.getValue().iterator().next();
        assertSame(employee, contribution.getEmployee());
        assertEquals(LocalDate.of(2026, 11, 1), contribution.getContributionMonth());
        assertEquals(0, new BigDecimal("3000.00")
                .compareTo(contribution.getContributionAmount()));
        assertEquals(Contribution.ContributionStatus.PENDING,
                contribution.getContributionStatus());
        assertNull(contribution.getPaymentDate());
        verifyNoInteractions(paymentService);
    }

    @Test
    void storesOptionalIntendedMethodOnDueWithoutCreatingPayment() {
        when(employeeRepository.findByEmployeeId("EMP-85")).thenReturn(Optional.of(employee));
        when(contributionRepository.existsByEmployeeEmployeeIdAndContributionMonth("EMP-85", LocalDate.of(2026, 11, 1))).thenReturn(false);
        when(contributionRepository.saveAllAndFlush(any(Iterable.class))).thenAnswer(invocation -> {
            List<Contribution> rows = new java.util.ArrayList<>();
            for (Object row : (Iterable<?>) invocation.getArgument(0)) rows.add((Contribution) row);
            return rows;
        });

        service.createContributionBatch(LocalDate.of(2026, 11, 1), List.of(
                new ContributionService.BatchContributionInput("EMP-85", new BigDecimal("3000.00"), ContributionPayment.PaymentMethod.BANK_FT)));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Iterable<Contribution>> saved = org.mockito.ArgumentCaptor.forClass(Iterable.class);
        verify(contributionRepository).saveAllAndFlush(saved.capture());
        Contribution due = saved.getValue().iterator().next();
        assertEquals(ContributionPayment.PaymentMethod.BANK_FT, due.getIntendedCollectionMethod());
        assertEquals(Contribution.ContributionStatus.PENDING, due.getContributionStatus());
        assertNull(due.getPaymentDate());
        verifyNoInteractions(paymentService);
    }

    @Test
    void storesOptionalShareValueAndTrimsAdministratorRemarkWithoutTreatingThemAsPayment() {
        when(employeeRepository.findByEmployeeId("EMP-85")).thenReturn(Optional.of(employee));
        when(contributionRepository.existsByEmployeeEmployeeIdAndContributionMonth("EMP-85", LocalDate.of(2026, 11, 1))).thenReturn(false);
        when(contributionRepository.saveAllAndFlush(any(Iterable.class))).thenAnswer(invocation -> {
            List<Contribution> rows = new java.util.ArrayList<>();
            for (Object row : (Iterable<?>) invocation.getArgument(0)) rows.add((Contribution) row);
            return rows;
        });

        service.createContributionBatch(LocalDate.of(2026, 11, 1), List.of(
                new ContributionService.BatchContributionInput("EMP-85", new BigDecimal("3000.00"),
                        null, BigDecimal.ZERO, "  HO-Hand  ")));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Iterable<Contribution>> saved = org.mockito.ArgumentCaptor.forClass(Iterable.class);
        verify(contributionRepository).saveAllAndFlush(saved.capture());
        Contribution due = saved.getValue().iterator().next();
        assertEquals(0, BigDecimal.ZERO.compareTo(due.getShareValue()));
        assertEquals("HO-Hand", due.getRemarks());
        assertNull(due.getIntendedCollectionMethod());
        assertEquals(Contribution.ContributionStatus.PENDING, due.getContributionStatus());
        verifyNoInteractions(paymentService);
    }

    @Test
    void rejectsInvalidOptionalShareValueAndOverlongRemarksBeforeDatabaseAccess() {
        assertThrows(IllegalArgumentException.class, () -> service.createContributionBatch(
                LocalDate.of(2026, 11, 1), List.of(new ContributionService.BatchContributionInput(
                        "EMP-85", new BigDecimal("3000.00"), null, new BigDecimal("-0.01"), null))));
        assertThrows(IllegalArgumentException.class, () -> service.createContributionBatch(
                LocalDate.of(2026, 11, 1), List.of(new ContributionService.BatchContributionInput(
                        "EMP-85", new BigDecimal("3000.00"), null, new BigDecimal("10.001"), null))));
        assertThrows(IllegalArgumentException.class, () -> service.createContributionBatch(
                LocalDate.of(2026, 11, 1), List.of(new ContributionService.BatchContributionInput(
                        "EMP-85", new BigDecimal("3000.00"), null, null, "x".repeat(501)))));
        verifyNoInteractions(employeeRepository, contributionRepository, paymentService);
    }

    @Test
    void rejectsLegacyActualPaymentMethodAsIntendedBatchMethod() {
        assertThrows(IllegalArgumentException.class, () -> service.createContributionBatch(
                LocalDate.of(2026, 11, 1), List.of(new ContributionService.BatchContributionInput(
                        "EMP-85", new BigDecimal("3000.00"), ContributionPayment.PaymentMethod.CASH))));
        verifyNoInteractions(employeeRepository, contributionRepository, paymentService);
    }

    @Test
    void rejectsExistingMonthBeforeAnyBatchWrite() {
        when(employeeRepository.findByEmployeeId("EMP-85"))
                .thenReturn(Optional.of(employee));
        when(contributionRepository
                .existsByEmployeeEmployeeIdAndContributionMonth(
                        "EMP-85", LocalDate.of(2026, 11, 1)))
                .thenReturn(true);

        assertThrows(DuplicateContributionException.class,
                () -> service.createContributionBatch(
                        LocalDate.of(2026, 11, 19),
                        List.of(new ContributionService.BatchContributionInput(
                                "EMP-85", new BigDecimal("3000.00")))));

        verify(contributionRepository, never()).saveAllAndFlush(any(Iterable.class));
    }

    @Test
    void rejectsDuplicateEmployeesWithinBatchBeforeAnyWrite() {
        assertThrows(DuplicateContributionException.class,
                () -> service.createContributionBatch(
                        LocalDate.of(2026, 11, 1),
                        List.of(
                                new ContributionService.BatchContributionInput(
                                        "EMP-85", new BigDecimal("3000.00")),
                                new ContributionService.BatchContributionInput(
                                        "emp-85", new BigDecimal("3000.00")))));

        verifyNoInteractions(employeeRepository);
        verify(contributionRepository, never()).saveAllAndFlush(any(Iterable.class));
    }

    @Test
    void rejectsUnknownEmployeeWithoutSavingAnyRows() {
        when(employeeRepository.findByEmployeeId("EMP-85"))
                .thenReturn(Optional.of(employee));
        when(contributionRepository
                .existsByEmployeeEmployeeIdAndContributionMonth(anyString(), any()))
                .thenReturn(false);
        when(employeeRepository.findByEmployeeId("EMP-999"))
                .thenReturn(Optional.empty());

        assertThrows(EmployeeNotFoundException.class,
                () -> service.createContributionBatch(
                        LocalDate.of(2026, 11, 1),
                        List.of(
                                new ContributionService.BatchContributionInput(
                                        "EMP-85", new BigDecimal("3000.00")),
                                new ContributionService.BatchContributionInput(
                                        "EMP-999", new BigDecimal("3000.00")))));

        verify(contributionRepository, never()).saveAllAndFlush(any(Iterable.class));
    }

    @Test
    void translatesDatabaseUniqueConstraintRaceToSafeDuplicateError() {
        when(employeeRepository.findByEmployeeId("EMP-85"))
                .thenReturn(Optional.of(employee));
        when(contributionRepository
                .existsByEmployeeEmployeeIdAndContributionMonth(
                        "EMP-85", LocalDate.of(2026, 11, 1)))
                .thenReturn(false);
        when(contributionRepository.saveAllAndFlush(any(Iterable.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint"));

        DuplicateContributionException error = assertThrows(
                DuplicateContributionException.class,
                () -> service.createContributionBatch(
                        LocalDate.of(2026, 11, 1),
                        List.of(new ContributionService.BatchContributionInput(
                                "EMP-85", new BigDecimal("3000.00")))));

        assertTrue(error.getMessage().contains("No rows from this batch were saved"));
        assertInstanceOf(DataIntegrityViolationException.class, error.getCause());
    }
}
