package com.relyon.economizai.dto.response;

import com.relyon.economizai.model.ShoppingList;
import com.relyon.economizai.model.ShoppingListItem;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
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
            String freeText,
            String displayName,
            BigDecimal quantity,
            int position,
            boolean checked,
            LocalDateTime checkedAt
    ) {
        public static Item from(ShoppingListItem i) {
            var product = i.getProduct();
            var productName = product != null ? product.getNormalizedName() : null;
            var display = productName != null ? productName : i.getFreeText();
            return new Item(
                    i.getId(),
                    product != null ? product.getId() : null,
                    productName,
                    i.getFreeText(),
                    display,
                    i.getQuantity(),
                    i.getPosition(),
                    i.isChecked(),
                    i.getCheckedAt()
            );
        }
    }

    public static ShoppingListResponse from(ShoppingList list) {
        var items = list.getItems().stream().map(Item::from).toList();
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
