package com.relyon.economizai.dto.response;

import com.relyon.economizai.model.ReceiptItem;

import java.math.BigDecimal;
import java.util.UUID;

public record ReceiptItemResponse(
        UUID id,
        UUID productId,
        Integer lineNumber,
        String rawDescription,
        String friendlyDescription,
        String displayDescription,
        String ean,
        BigDecimal quantity,
        String unit,
        BigDecimal unitPrice,
        BigDecimal totalPrice,
        boolean excluded,
        boolean nfcePromoFlag,
        String category,
        boolean categorySuggested
) {
    public static ReceiptItemResponse from(ReceiptItem item) {
        return from(item, null, false);
    }

    public static ReceiptItemResponse from(ReceiptItem item, String overrideLabel) {
        return from(item, overrideLabel, false);
    }

    /**
     * Category precedence: household override (explicit user choice) →
     * snapshot frozen at confirmation → live product category. The snapshot
     * keeps confirmed history stable while the product keeps improving for
     * future purchases.
     *
     * @param overrideLabel the household's corrected category label (global enum
     *                      name OR a custom-category name), shown in place of the
     *                      product's/snapshot category when present.
     * @param suggested     true when {@code overrideLabel} is a best-effort
     *                      PREVIEW (pending receipt, item not linked yet) rather
     *                      than a user choice or confirmed category — the FE
     *                      should render it as a suggestion.
     */
    public static ReceiptItemResponse from(ReceiptItem item, String overrideLabel, boolean suggested) {
        var friendly = item.getFriendlyDescription();
        var display = friendly != null && !friendly.isBlank() ? friendly : item.getRawDescription();
        var product = item.getProduct();
        var snapshot = item.getCategoryAtConfirmation() != null ? item.getCategoryAtConfirmation().name() : null;
        var liveCategory = product != null && product.getCategory() != null ? product.getCategory().name() : null;
        var category = overrideLabel != null ? overrideLabel
                : snapshot != null ? snapshot
                : liveCategory;
        return new ReceiptItemResponse(
                item.getId(),
                product != null ? product.getId() : null,
                item.getLineNumber(),
                item.getRawDescription(),
                friendly,
                display,
                item.getEan(),
                item.getQuantity(),
                item.getUnit(),
                item.getUnitPrice(),
                item.getTotalPrice(),
                item.isExcluded(),
                item.isNfcePromoFlag(),
                category,
                suggested
        );
    }
}
