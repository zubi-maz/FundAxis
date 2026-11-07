package com.fundaxis.service;

import com.fundaxis.entity.Loan;
import com.fundaxis.entity.RepaymentSchedule;
import com.fundaxis.exception.DuplicateRepaymentScheduleException;
import com.fundaxis.repository.LoanRepository;
import com.fundaxis.repository.RepaymentScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RepaymentScheduleServicePhase2Test {

    @Mock private RepaymentScheduleRepository schedules;
    @Mock private LoanRepository loans;
    private RepaymentScheduleService service;
    private Loan loan;

    @BeforeEach
    void setUp() {
        service = new RepaymentScheduleService(schedules, loans);
        loan = new Loan();
        loan.setId(7L);
    }

    @Test
    void createsManualInstallmentAndInitializesSettlementFields() {
        when(loans.findByIdForUpdate(7L)).thenReturn(Optional.of(loan));
        when(schedules.existsByLoanIdAndInstallmentNumber(7L, 1)).thenReturn(false);
        when(schedules.save(any(RepaymentSchedule.class))).thenAnswer(invocation -> invocation.getArgument(0));
        RepaymentSchedule input = schedule(1, "4166.67", "229.33", "4396.00");
        input.setPaidAmount(new BigDecimal("120"));
        input.setRemainingAmount(new BigDecimal("10"));
        input.setInstallmentStatus(RepaymentSchedule.InstallmentStatus.PAID);
        input.setPaymentDate(LocalDate.now());

        RepaymentSchedule created = service.createRepaymentSchedule(7L, input);

        assertSame(loan, created.getLoan());
        assertEquals(RepaymentSchedule.InstallmentStatus.PENDING, created.getInstallmentStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(created.getPaidAmount()));
        assertEquals(0, new BigDecimal("4396.00").compareTo(created.getRemainingAmount()));
        assertNull(created.getPaymentDate());
        verify(loans).findByIdForUpdate(7L);
        verify(schedules).save(input);
    }

    @Test
    void rejectsMissingOrInvalidInstallmentIdentity() {
        when(loans.findByIdForUpdate(7L)).thenReturn(Optional.of(loan));
        RepaymentSchedule noNumber = schedule(0, "1", "0", "1");
        assertThrows(IllegalArgumentException.class, () -> service.createRepaymentSchedule(7L, noNumber));

        RepaymentSchedule noDate = schedule(1, "1", "0", "1");
        noDate.setDueDate(null);
        assertThrows(IllegalArgumentException.class, () -> service.createRepaymentSchedule(7L, noDate));

        RepaymentSchedule unsupportedSqlServerDate = schedule(1, "1", "0", "1");
        unsupportedSqlServerDate.setDueDate(LocalDate.of(0, 12, 1));
        assertThrows(IllegalArgumentException.class,
                () -> service.createRepaymentSchedule(7L, unsupportedSqlServerDate));

        verify(schedules, never()).save(any());
    }

    @Test
    void rejectsNegativeAmountsMismatchAndUnsupportedCurrencyPrecision() {
        when(loans.findByIdForUpdate(7L)).thenReturn(Optional.of(loan));
        assertThrows(IllegalArgumentException.class,
                () -> service.createRepaymentSchedule(7L, schedule(1, "-1", "2", "1")));
        assertThrows(IllegalArgumentException.class,
                () -> service.createRepaymentSchedule(7L, schedule(1, "1", "-1", "0.01")));
        assertThrows(IllegalArgumentException.class,
                () -> service.createRepaymentSchedule(7L, schedule(1, "1", "1", "3")));
        assertThrows(IllegalArgumentException.class,
                () -> service.createRepaymentSchedule(7L, schedule(1, "1.001", "0", "1.001")));
        assertThrows(IllegalArgumentException.class,
                () -> service.createRepaymentSchedule(7L, schedule(1, "1E+18", "0", "1E+18")));
        verify(schedules, never()).save(any());
    }

    @Test
    void rejectsDuplicateInstallmentNumber() {
        when(loans.findByIdForUpdate(7L)).thenReturn(Optional.of(loan));
        when(schedules.existsByLoanIdAndInstallmentNumber(7L, 2)).thenReturn(true);
        assertThrows(DuplicateRepaymentScheduleException.class,
                () -> service.createRepaymentSchedule(7L, schedule(2, "10", "0", "10")));
        verify(schedules, never()).save(any());
    }

    @Test
    void updateLocksScheduleAndAllowsOnlyPendingRows() {
        RepaymentSchedule existing = schedule(1, "100", "10", "110");
        existing.setId(33L);
        existing.setInstallmentStatus(RepaymentSchedule.InstallmentStatus.PENDING);
        existing.setPaidAmount(BigDecimal.ZERO);
        existing.setRemainingAmount(new BigDecimal("110"));
        when(schedules.findByIdForUpdate(33L)).thenReturn(Optional.of(existing));
        when(schedules.save(existing)).thenReturn(existing);

        service.updateRepaymentSchedule(33L, schedule(1, "90", "10", "100"));
        assertEquals(0, new BigDecimal("100").compareTo(existing.getRemainingAmount()));
        verify(schedules).findByIdForUpdate(33L);

        existing.setInstallmentStatus(RepaymentSchedule.InstallmentStatus.PARTIAL);
        assertThrows(IllegalStateException.class, () -> service.updateRepaymentSchedule(33L, schedule(1, "90", "10", "100")));
    }

    @Test
    void overdueRequiresPastDueDateAndPositiveRemainingBalance() {
        RepaymentSchedule schedule = schedule(1, "100", "10", "110");
        schedule.setId(33L);
        schedule.setInstallmentStatus(RepaymentSchedule.InstallmentStatus.PENDING);
        schedule.setRemainingAmount(new BigDecimal("110"));
        when(schedules.findByIdForUpdate(33L)).thenReturn(Optional.of(schedule));
        when(schedules.save(schedule)).thenReturn(schedule);

        schedule.setDueDate(LocalDate.now());
        assertThrows(IllegalStateException.class, () -> service.markAsOverdue(33L));
        schedule.setDueDate(LocalDate.now().minusDays(1));
        assertEquals(RepaymentSchedule.InstallmentStatus.OVERDUE, service.markAsOverdue(33L).getInstallmentStatus());
        verify(schedules, times(1)).save(schedule);
    }

    @Test
    void onlyUntouchedPendingScheduleCanBeCancelled() {
        RepaymentSchedule schedule = schedule(1, "100", "10", "110");
        schedule.setId(33L);
        schedule.setInstallmentStatus(RepaymentSchedule.InstallmentStatus.PENDING);
        schedule.setPaidAmount(BigDecimal.ZERO);
        when(schedules.findByIdForUpdate(33L)).thenReturn(Optional.of(schedule));
        when(schedules.save(schedule)).thenReturn(schedule);

        assertEquals(RepaymentSchedule.InstallmentStatus.CANCELLED, service.cancelRepaymentSchedule(33L).getInstallmentStatus());
        schedule.setInstallmentStatus(RepaymentSchedule.InstallmentStatus.PENDING);
        schedule.setPaidAmount(new BigDecimal("1.00"));
        assertThrows(IllegalStateException.class, () -> service.cancelRepaymentSchedule(33L));
    }

    private RepaymentSchedule schedule(int number, String principal, String interest, String total) {
        RepaymentSchedule schedule = new RepaymentSchedule();
        schedule.setInstallmentNumber(number);
        schedule.setDueDate(LocalDate.of(2026, 12, 1));
        schedule.setPrincipalAmount(new BigDecimal(principal));
        schedule.setInterestAmount(new BigDecimal(interest));
        schedule.setTotalInstallmentAmount(new BigDecimal(total));
        return schedule;
    }
}
