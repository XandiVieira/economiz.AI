package com.relyon.economizai.dto.response;

import com.relyon.economizai.model.MarketLocation;

import java.math.BigDecimal;

/**
 * One market entry as seen by the user — includes whether the user has
 * already shopped here ({@code visited}) and whether they've pinned it
 * to their watchlist ({@code watching}).
 */
public record MarketResponse(
        String cnpj,
        String cnpjRoot,
        String name,
        String address,
        BigDecimal latitude,
        BigDecimal longitude,
        Double distanceKm,
        boolean visited,
        boolean watching
) {
    public static MarketResponse from(MarketLocation location, Double distanceKm,
                                      boolean visited, boolean watching) {
        return new MarketResponse(
                location.getCnpj(),
                location.getCnpjRoot(),
                location.getName(),
                location.getAddress(),
                location.getLatitude(),
                location.getLongitude(),
                distanceKm,
                visited,
                watching
        );
    }
}
