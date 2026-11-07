package com.fundaxis.controller.admin;

import com.fundaxis.entity.ContributionPayment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SingleMonthPaymentMethodReportTest {
    @Test
    void reportsDistinctMethodsFromActiveDirectPaymentsOnly() {
        ContributionPayment hand = payment(ContributionPayment.PaymentMethod.HO_HAND, false);
        ContributionPayment transfer = payment(ContributionPayment.PaymentMethod.BANK_FT, false);
        ContributionPayment duplicateTransfer = payment(ContributionPayment.PaymentMethod.BANK_FT, false);
        ContributionPayment reversed = payment(ContributionPayment.PaymentMethod.ITD_HAND, true);

        assertEquals("Bank FT, HO-Hand", AdminContributionWorkspaceController
                .summarizeActivePaymentMethods(List.of(hand, transfer, duplicateTransfer, reversed)));
    }

    @Test
    void distinguishesNoPaymentFromUnrecordedHistoricalPaymentMethod() {
        assertEquals("—", AdminContributionWorkspaceController.summarizeActivePaymentMethods(List.of()));
        assertEquals("Not Recorded", AdminContributionWorkspaceController.summarizeActivePaymentMethods(
                List.of(payment(ContributionPayment.PaymentMethod.UNSPECIFIED, false))));
        assertEquals("—", AdminContributionWorkspaceController.summarizeActivePaymentMethods(
                List.of(payment(ContributionPayment.PaymentMethod.HO_HAND, true))));
    }

    private static ContributionPayment payment(ContributionPayment.PaymentMethod method, boolean reversed) {
        ContributionPayment payment = new ContributionPayment();
        payment.setPaymentMethod(method);
        payment.setReversed(reversed);
        return payment;
    }
}
