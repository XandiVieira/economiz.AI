package com.relyon.economizai.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A product the household has actually bought (confirmed, non-excluded
 * purchases), with the household's own most-recent purchase context.
 */
public record HouseholdProductResponse(
        UUID productId,
        String name,
        String brand,
        String category,
        long timesBought,
        LocalDateTime lastBoughtAt,
        BigDecimal lastUnitPrice,
        String lastMarketCnpj,
        String lastMarketName,
        String lastMarketFriendlyName
) {}
