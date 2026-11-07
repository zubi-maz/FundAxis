package com.fundaxis.service;

import com.fundaxis.entity.LoanRepayment;
import com.fundaxis.entity.Repayment;
import com.fundaxis.entity.RepaymentSchedule;
import com.fundaxis.repository.LoanRepaymentRepository;
import com.fundaxis.repository.RepaymentRepository;
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
class LoanRepaymentIntegrityTest {

    @Mock private RepaymentRepository repaymentRepository;
    @Mock private LoanRepaymentRepository loanRepaymentRepository;
    @Mock private RepaymentScheduleRepository scheduleRepository;

    private RepaymentService repaymentService;
    private LoanRepaymentService loanRepaymentService;

    @BeforeEach
    void setUp() {
        repaymentService = new RepaymentService(repaymentRepository, scheduleRepository);
        loanRepaymentService = new LoanRepaymentService(loanRepaymentRepository, scheduleRepository);
    }

    @Test
    void directRepaymentLocksScheduleAndRejectsOverpaymentBeforeWriting() {
        RepaymentSchedule schedule = schedule(7L, "100.00", "0.00", "100.00");
        when(scheduleRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(schedule));

        Repayment payment = new Repayment();
        payment.setReferenceNumber("R-OVER");
        payment.setRepaymentDate(LocalDate.of(2026, 11, 7));
        payment.setRepaymentAmount(new BigDecimal("100.01"));
        payment.setPaymentMethod(Repayment.PaymentMethod.CASH);

        assertThrows(IllegalArgumentException.class,
                () -> repaymentService.recordRepayment(7L, payment, "admin"));

        verify(scheduleRepository).findByIdForUpdate(7L);
        verify(repaymentRepository, never()).save(any(Repayment.class));
    }

    @Test
    void directRepaymentRejectsFuturePaymentDateBeforeWriting() {
        RepaymentSchedule schedule = schedule(70L, "100.00", "0.00", "100.00");
        when(scheduleRepository.findByIdForUpdate(70L)).thenReturn(Optional.of(schedule));

        Repayment payment = new Repayment();
        payment.setReferenceNumber("R-FUTURE");
        payment.setRepaymentDate(LocalDate.now().plusDays(1));
        payment.setRepaymentAmount(new BigDecimal("10.00"));
        payment.setPaymentMethod(Repayment.PaymentMethod.CASH);

        assertThrows(IllegalArgumentException.class,
                () -> repaymentService.recordRepayment(70L, payment, "admin"));
        verify(repaymentRepository, never()).save(any(Repayment.class));
        assertEquals(new BigDecimal("0.00"), schedule.getPaidAmount());
    }

    @Test
    void reversalLocksRepaymentAndScheduleAndPreservesExactBalances() {
        RepaymentSchedule schedule = schedule(8L, "100.00", "40.00", "60.00");
        Repayment payment = new Repayment();
        payment.setId(81L);
        payment.setRepaymentSchedule(schedule);
        payment.setRepaymentAmount(new BigDecimal("40.00"));
        payment.setRepaymentStatus(Repayment.RepaymentStatus.COMPLETED);
        when(repaymentRepository.findByIdForUpdate(81L)).thenReturn(Optional.of(payment));
        when(scheduleRepository.findByIdForUpdate(8L)).thenReturn(Optional.of(schedule));
        when(repaymentRepository.save(payment)).thenReturn(payment);

        repaymentService.reverseRepayment(81L, "Duplicate receipt", "checker");

        assertEquals(0, schedule.getPaidAmount().compareTo(BigDecimal.ZERO));
        assertEquals(0, schedule.getRemainingAmount().compareTo(new BigDecimal("100.00")));
        assertEquals(Repayment.RepaymentStatus.REVERSED, payment.getRepaymentStatus());
        assertEquals("checker", payment.getReversedBy());
        verify(repaymentRepository).findByIdForUpdate(81L);
        verify(scheduleRepository).findByIdForUpdate(8L);
    }

    @Test
    void loanRepaymentCompletionUsesLockedCurrentScheduleBalance() {
        RepaymentSchedule schedule = schedule(9L, "100.00", "20.00", "80.00");
        LoanRepayment payment = new LoanRepayment();
        payment.setId(91L);
        payment.setRepaymentSchedule(schedule);
        payment.setRepaymentAmount(new BigDecimal("30.00"));
        payment.setRepaymentDate(LocalDate.of(2026, 11, 7));
        payment.setRepaymentStatus(LoanRepayment.RepaymentStatus.PROCESSING);
        when(loanRepaymentRepository.findByIdForUpdate(91L)).thenReturn(Optional.of(payment));
        when(scheduleRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(schedule));
        when(scheduleRepository.save(schedule)).thenReturn(schedule);
        when(loanRepaymentRepository.save(payment)).thenReturn(payment);

        loanRepaymentService.completeLoanRepayment(91L, "authenticated-admin");

        assertEquals(0, schedule.getPaidAmount().compareTo(new BigDecimal("50.00")));
        assertEquals(0, schedule.getRemainingAmount().compareTo(new BigDecimal("50.00")));
        assertEquals(RepaymentSchedule.InstallmentStatus.PARTIAL, schedule.getInstallmentStatus());
        assertEquals("authenticated-admin", payment.getProcessedBy());
        assertEquals(LoanRepayment.RepaymentStatus.COMPLETED, payment.getRepaymentStatus());
        verify(loanRepaymentRepository).findByIdForUpdate(91L);
        verify(scheduleRepository).findByIdForUpdate(9L);
    }

    @Test
    void pendingLoanRepaymentCreationLocksScheduleAndRejectsOverpayment() {
        RepaymentSchedule schedule = schedule(10L, "100.00", "0.00", "100.00");
        when(scheduleRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(schedule));

        LoanRepayment payment = new LoanRepayment();
        payment.setReferenceNumber("LR-OVER");
        payment.setRepaymentAmount(new BigDecimal("100.01"));

        assertThrows(IllegalArgumentException.class,
                () -> loanRepaymentService.createLoanRepayment(10L, payment));

        verify(scheduleRepository).findByIdForUpdate(10L);
        verify(loanRepaymentRepository, never()).save(any(LoanRepayment.class));
    }

    private static RepaymentSchedule schedule(Long id, String total, String paid, String remaining) {
        RepaymentSchedule schedule = new RepaymentSchedule();
        schedule.setId(id);
        schedule.setTotalInstallmentAmount(new BigDecimal(total));
        schedule.setPaidAmount(new BigDecimal(paid));
        schedule.setRemainingAmount(new BigDecimal(remaining));
        schedule.setDueDate(LocalDate.of(2026, 12, 1));
        schedule.setInstallmentStatus(new BigDecimal(paid).signum() == 0
                ? RepaymentSchedule.InstallmentStatus.PENDING
                : RepaymentSchedule.InstallmentStatus.PARTIAL);
        return schedule;
    }
}
