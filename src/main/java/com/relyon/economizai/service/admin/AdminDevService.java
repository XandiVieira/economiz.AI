package com.relyon.economizai.service.admin;

import com.relyon.economizai.dto.response.ReceiptResponse;
import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.ReceiptItem;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.ReceiptStatus;
import com.relyon.economizai.model.enums.UnidadeFederativa;
import com.relyon.economizai.repository.ReceiptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Dev-only test-data seeding for QA / e2e flows. Not for prod — the caller
 * ({@code AdminController}) guards every entry point on
 * {@code economizai.admin.dev-seed-enabled}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminDevService {

    private final ReceiptRepository receiptRepository;

    /**
     * Plants a PENDING_CONFIRMATION receipt with a receipt-level discount and one
     * line carrying a manual paid price (promotional), so the review/discount UI
     * has a deterministic note to exercise. Returns the created receipt.
     */
    @Transactional
    public ReceiptResponse seedDiscountedReceipt(User caller) {
        var receipt = Receipt.builder()
                .user(caller)
                .household(caller.getHousehold())
                .chaveAcesso(randomChave())
                .qrPayload("seed://discounted-receipt")
                .uf(UnidadeFederativa.RS)
                .cnpjEmitente("12345678000190")
                .marketName("MERCADO TESTE (SEED)")
                .issuedAt(LocalDateTime.now())
                .status(ReceiptStatus.PENDING_CONFIRMATION)
                .discountTotal(new BigDecimal("5.00"))
                // gross 68.00 − discount 5.00 = 63.00 net "valor a pagar"
                .totalAmount(new BigDecimal("63.00"))
                .build();

        // Line 1 was bought on promotion: paid 19.90 vs 25.00 as-printed.
        receipt.addItem(item(1, "ARROZ TESTE 5KG", "1", "25.00", "25.00", "19.90"));
        receipt.addItem(item(2, "FEIJAO TESTE 1KG", "2", "8.00", "16.00", null));
        receipt.addItem(item(3, "LEITE TESTE 1L", "6", "4.50", "27.00", null));

        var saved = receiptRepository.save(receipt);
        log.info("dev.seed.discounted_receipt user={} receipt={} items={} discount={}",
                caller.getEmail(), saved.getId(), saved.getItems().size(), saved.getDiscountTotal());
        return ReceiptResponse.from(saved);
    }

    private ReceiptItem item(int lineNumber, String description, String quantity,
                             String unitPrice, String totalPrice, String paidTotalPrice) {
        var quantityValue = new BigDecimal(quantity);
        var paidTotal = paidTotalPrice == null ? null : new BigDecimal(paidTotalPrice);
        var paidUnit = paidTotal == null || quantityValue.signum() == 0
                ? null : paidTotal.divide(quantityValue, 4, RoundingMode.HALF_UP);
        return ReceiptItem.builder()
                .lineNumber(lineNumber)
                .rawDescription(description)
                .quantity(quantityValue)
                .unit("UN")
                .unitPrice(new BigDecimal(unitPrice))
                .totalPrice(new BigDecimal(totalPrice))
                .paidUnitPrice(paidUnit)
                .paidTotalPrice(paidTotal)
                .build();
    }

    private String randomChave() {
        var random = ThreadLocalRandom.current();
        var chave = new StringBuilder(44);
        for (var digit = 0; digit < 44; digit++) {
            chave.append(random.nextInt(10));
        }
        return chave.toString();
    }
}
