package com.relyon.economizai.controller;

import com.relyon.economizai.model.User;
import com.relyon.economizai.service.geo.WatchedMarketService;
import com.relyon.economizai.service.priceindex.CommunityPromoService;
import com.relyon.economizai.service.priceindex.PriceIndexService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Public-facing collaborative price intelligence. All endpoints respect
 * k-anonymity and minimum-sample thresholds (configured via
 * <code>economizai.collaborative.*</code>) — they return empty results
 * rather than partial / re-identifiable data when volume is insufficient.
 *
 * <p>Distance filtering: pass {@code radiusKm} to limit markets to within
 * X km of the authenticated user's home location. Markets without a known
 * geolocation are excluded when {@code radiusKm} is set; without it,
 * {@code distanceKm} is just an extra field for the client.
 */
@RestController
@RequestMapping("/api/v1/price-index")
@RequiredArgsConstructor
@Tag(name = "Price index", description = "Anonymized collaborative price intelligence (k-anon protected)")
public class PriceIndexController {

    private final PriceIndexService priceIndexService;
    private final CommunityPromoService communityPromoService;
    private final WatchedMarketService watchedMarketService;

    @GetMapping("/products/{productId}/markets/{marketCnpj}/reference")
    public ResponseEntity<PriceIndexService.ReferencePrice> reference(@PathVariable UUID productId,
                                                                     @PathVariable String marketCnpj) {
        return ResponseEntity.ok(priceIndexService.referencePrice(productId, marketCnpj));
    }

    @GetMapping("/products/{productId}/best-markets")
    public ResponseEntity<List<PriceIndexService.MarketPriceRow>> bestMarkets(
            @AuthenticationPrincipal User user,
            @PathVariable UUID productId,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) Double radiusKm) {
        return ResponseEntity.ok(priceIndexService.bestMarkets(productId, limit,
                user.getHomeLatitude(), user.getHomeLongitude(), radiusKm,
                watchedMarketService.watchedCnpjs(user)));
    }

    @GetMapping("/promos")
    public ResponseEntity<List<CommunityPromoService.CommunityPromo>> currentPromos(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) Double radiusKm) {
        return ResponseEntity.ok(communityPromoService.detectAll(
                user.getHomeLatitude(), user.getHomeLongitude(), radiusKm,
                watchedMarketService.watchedCnpjs(user)));
    }
}
