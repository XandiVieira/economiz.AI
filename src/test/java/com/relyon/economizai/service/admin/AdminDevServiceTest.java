package com.relyon.economizai.service.admin;

import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.ReceiptStatus;
import com.relyon.economizai.repository.ReceiptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminDevServiceTest {

    @Mock private ReceiptRepository receiptRepository;
    @InjectMocks private AdminDevService adminDevService;

    @Test
    void seedDiscountedReceipt_buildsPendingReceiptWithDiscountAndPromotionalItem() {
        var household = new Household();
        var user = User.builder().email("dev@economizaai.app").household(household).build();
        when(receiptRepository.save(any(Receipt.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = adminDevService.seedDiscountedReceipt(user);

        assertEquals(ReceiptStatus.PENDING_CONFIRMATION, response.status());
        assertEquals(0, response.discountTotal().compareTo(new BigDecimal("5.00")));
        assertEquals(3, response.items().size());

        var promoLine = response.items().stream().filter(item -> item.promotional()).findFirst().orElse(null);
        assertNotNull(promoLine, "one line should be promotional");
        assertEquals(0, promoLine.paidTotalPrice().compareTo(new BigDecimal("19.90")));
        assertTrue(promoLine.paidTotalPrice().compareTo(promoLine.totalPrice()) < 0);
    }
}
