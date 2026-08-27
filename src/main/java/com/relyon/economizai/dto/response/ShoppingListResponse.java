package com.relyon.economizai.dto.response;

import com.relyon.economizai.model.ShoppingList;
import com.relyon.economizai.model.ShoppingListItem;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ShoppingListResponse(
        UUID id,
        String name,
        UUID createdByUserId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        int totalItems,
        int checkedItems,
        List<Item> items
) {
    public record Item(
            UUID id,
            UUID productId,
            String productName,
            String friendlyDescription,
            String freeText,
            String displayName,
            BigDecimal quantity,
            int position,
            boolean checked,
            LocalDateTime checkedAt
    ) {
        /**
         * @param friendlyDescription the household's own rename of the item's product
         *                            (household_product_aliases), null when never renamed
         *                            or when the item has no linked product.
         */
        public static Item from(ShoppingListItem i, String friendlyDescription) {
            var product = i.getProduct();
            var productName = product != null ? product.getNormalizedName() : null;
            var hasFriendly = friendlyDescription != null && !friendlyDescription.isBlank();
            var display = hasFriendly ? friendlyDescription
                    : productName != null ? productName
                    : i.getFreeText();
            return new Item(
                    i.getId(),
                    product != null ? product.getId() : null,
                    productName,
                    hasFriendly ? friendlyDescription : null,
                    i.getFreeText(),
                    display,
                    i.getQuantity(),
                    i.getPosition(),
                    i.isChecked(),
                    i.getCheckedAt()
            );
        }
    }

    /**
     * @param friendlyByProduct the household's product aliases (household_product_aliases),
     *                          keyed by product id — see {@code ShoppingListService.friendlyNamesByProduct}.
     */
    public static ShoppingListResponse from(ShoppingList list, Map<UUID, String> friendlyByProduct) {
        var items = list.getItems().stream()
                .map(i -> Item.from(i, i.getProduct() != null ? friendlyByProduct.get(i.getProduct().getId()) : null))
                .toList();
        var checked = (int) items.stream().filter(Item::checked).count();
        return new ShoppingListResponse(
                list.getId(),
                list.getName(),
                list.getCreatedBy().getId(),
                list.getCreatedAt(),
                list.getUpdatedAt(),
                items.size(),
                checked,
                items
        );
    }
}
