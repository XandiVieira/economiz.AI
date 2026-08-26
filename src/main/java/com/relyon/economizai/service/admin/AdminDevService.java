package com.relyon.economizai.service.admin;

import com.relyon.economizai.dto.response.ReceiptResponse;
import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.ReceiptItem;
import com.relyon.economizai.model.User;
import com.relyon.economizai.exception.UserNotFoundException;
import com.relyon.economizai.model.enums.ReceiptStatus;
import com.relyon.economizai.model.enums.UnidadeFederativa;
import com.relyon.economizai.repository.ReceiptRepository;
import com.relyon.economizai.repository.UserRepository;
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
    private final UserRepository userRepository;

    /**
     * Plants a PENDING_CONFIRMATION receipt with a receipt-level discount and
     * three lines carrying a manual paid price (promotional), so the
     * review/discount UI has deterministic promotions to exercise. Seeds into
     * {@code targetEmail}'s account when given (admin planting screenshot data
     * for another account), the caller's own otherwise. Returns the created receipt.
     */
    @Transactional
    public ReceiptResponse seedDiscountedReceipt(User caller, String targetEmail) {
        var target = resolveTarget(caller, targetEmail);
        var receipt = Receipt.builder()
                .user(target)
                .household(target.getHousehold())
                .chaveAcesso(randomChave())
                .qrPayload("seed://discounted-receipt")
                .uf(UnidadeFederativa.RS)
                .cnpjEmitente("12345678000190")
                .marketName("MERCADO TESTE (SEED)")
                .issuedAt(LocalDateTime.now())
                .status(ReceiptStatus.PENDING_CONFIRMATION)
                .discountTotal(new BigDecimal("5.00"))
                // gross 86.90 − discount 5.00 = 81.90 net "valor a pagar"
                .totalAmount(new BigDecimal("81.90"))
                .build();

        // Lines 1-3 were bought on promotion: paid total < as-printed total.
        receipt.addItem(item(1, "ARROZ TESTE 5KG", "1", "25.00", "25.00", "19.90"));
        receipt.addItem(item(2, "FEIJAO TESTE 1KG", "2", "8.00", "16.00", "13.50"));
        receipt.addItem(item(3, "CAFE TESTE 500G", "1", "18.90", "18.90", "14.90"));
        receipt.addItem(item(4, "LEITE TESTE 1L", "6", "4.50", "27.00", null));

        var saved = receiptRepository.save(receipt);
        log.info("dev.seed.discounted_receipt caller={} target={} receipt={} items={} discount={}",
                caller.getEmail(), target.getEmail(), saved.getId(), saved.getItems().size(),
                saved.getDiscountTotal());
        return ReceiptResponse.from(saved);
    }

    private User resolveTarget(User caller, String targetEmail) {
        if (targetEmail == null || targetEmail.isBlank()) return caller;
        var email = targetEmail.trim();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UserNotFoundException(email));
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
