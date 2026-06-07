package com.relyon.economizai.dto.response;

import java.util.UUID;

public record ProductDeletionResponse(UUID productId, long receiptItemsDetached) {
}
