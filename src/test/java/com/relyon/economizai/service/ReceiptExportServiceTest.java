package com.relyon.economizai.service;

import com.relyon.economizai.exception.PaywallException;
import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.ReceiptItem;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.repository.ReceiptRepository;
import com.relyon.economizai.service.subscription.Feature;
import com.relyon.economizai.service.subscription.SubscriptionGateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReceiptExportServiceTest {

    @Mock private ReceiptRepository receiptRepository;
    @Mock private SubscriptionGateService subscriptionGate;
    @Mock private LocalizedMessageService localizedMessageService;

    @InjectMocks private ReceiptExportService service;

    private User user;

    @BeforeEach
    void setUp() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        user = User.builder().id(UUID.randomUUID()).email("developer+export@economizaai.app")
                .household(household).build();
        // headers echo their key so assertions are stable regardless of locale
        lenient().when(localizedMessageService.translate(anyString())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(subscriptionGate.clampFrom(eq(user), any())).thenAnswer(inv -> inv.getArgument(1));
    }

    private Receipt confirmedReceipt(ReceiptItem... items) {
        var receipt = Receipt.builder()
                .id(UUID.randomUUID())
                .chaveAcesso("43260493015006005182651130003394021410514546")
                .cnpjEmitente("93015006005182")
                .marketName("Zaffari; Centro")
                .issuedAt(LocalDateTime.of(2026, Month.JULY, 20, 18, 30))
                .totalAmount(new BigDecimal("47.00"))
                .build();
        for (var item : items) receipt.addItem(item);
        return receipt;
    }

    private ReceiptItem item(String description, String unitPrice, boolean excluded) {
        var receiptItem = ReceiptItem.builder()
                .lineNumber(1).rawDescription(description)
                .quantity(new BigDecimal("2.000")).unit("UN")
                .unitPrice(new BigDecimal(unitPrice)).totalPrice(new BigDecimal(unitPrice).multiply(BigDecimal.TWO))
                .categoryAtConfirmation(ProductCategory.BEVERAGES)
                .build();
        receiptItem.setExcluded(excluded);
        return receiptItem;
    }

    @Test
    void export_buildsSemicolonCsvWithBomHeaderAndRows() {
        when(receiptRepository.findAll(any(Specification.class), any(Sort.class)))
                .thenReturn(List.of(confirmedReceipt(item("CHOPP BRAHMA 440ml", "14.00", false))));

        var csv = service.exportPurchaseHistory(user, null, null);

        assertThat(csv).startsWith("﻿");
        var lines = csv.substring(1).split("\n");
        assertThat(lines[0]).isEqualTo("export.header.date;export.header.market;export.header.market-cnpj;"
                + "export.header.chave-acesso;export.header.item;export.header.quantity;export.header.unit;"
                + "export.header.unit-price;export.header.item-total;export.header.category;export.header.receipt-total");
        // market name contains the separator -> quoted; decimals use comma
        assertThat(lines[1]).isEqualTo("20/07/2026 18:30;\"Zaffari; Centro\";93015006005182;"
                + "43260493015006005182651130003394021410514546;CHOPP BRAHMA 440ml;2,000;UN;14,00;28,00;"
                + "BEVERAGES;47,00");
    }

    @Test
    void export_skipsExcludedItems() {
        when(receiptRepository.findAll(any(Specification.class), any(Sort.class)))
                .thenReturn(List.of(confirmedReceipt(
                        item("ARROZ 5KG", "28.90", false),
                        item("ITEM EXCLUIDO", "9.99", true))));

        var csv = service.exportPurchaseHistory(user, null, null);

        assertThat(csv).contains("ARROZ 5KG").doesNotContain("ITEM EXCLUIDO");
    }

    @Test
    void export_requiresCsvExportFeature() {
        doThrow(new PaywallException(Feature.CSV_EXPORT.name()))
                .when(subscriptionGate).require(user, Feature.CSV_EXPORT);

        assertThrows(PaywallException.class, () -> service.exportPurchaseHistory(user, null, null));
        verify(receiptRepository, never()).findAll(any(Specification.class), any(Sort.class));
    }

    @Test
    void export_clampsFromToFreeHistoryWindow() {
        var requestedFrom = LocalDateTime.of(2020, Month.JANUARY, 1, 0, 0);
        var clampedFrom = LocalDateTime.of(2026, Month.JUNE, 22, 0, 0);
        when(subscriptionGate.clampFrom(user, requestedFrom)).thenReturn(clampedFrom);
        when(receiptRepository.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of());

        service.exportPurchaseHistory(user, requestedFrom, null);

        verify(subscriptionGate).clampFrom(user, requestedFrom);
    }
}
