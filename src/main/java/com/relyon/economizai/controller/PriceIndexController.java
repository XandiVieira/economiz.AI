package com.relyon.economizai.controller;

import com.relyon.economizai.service.priceindex.CommunityPromoService;
import com.relyon.economizai.service.priceindex.PriceIndexService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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
 */
@RestController
@RequestMapping("/api/v1/price-index")
@RequiredArgsConstructor
@Tag(name = "Price index", description = "Anonymized collaborative price intelligence (k-anon protected)")
public class PriceIndexController {

    private final PriceIndexService priceIndexService;
    private final CommunityPromoService communityPromoService;

    /**
     * Reference price for one product at one market over the configured
     * lookback window. Returns {@code hasData=false} when sparse.
     */
    @GetMapping("/products/{productId}/markets/{marketCnpj}/reference")
    public ResponseEntity<PriceIndexService.ReferencePrice> reference(@PathVariable UUID productId,
                                                                     @PathVariable String marketCnpj) {
        return ResponseEntity.ok(priceIndexService.referencePrice(productId, marketCnpj));
    }

    /**
     * Cheapest markets for a given product, ranked by median price.
     * Each market entry is independently k-anon checked.
     */
    @GetMapping("/products/{productId}/best-markets")
    public ResponseEntity<List<PriceIndexService.MarketPriceRow>> bestMarkets(@PathVariable UUID productId,
                                                                              @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(priceIndexService.bestMarkets(productId, limit));
    }

    /**
     * Currently-active community promos (recent median significantly below
     * baseline), across all products + markets the system has visibility on.
     */
    @GetMapping("/promos")
    public ResponseEntity<List<CommunityPromoService.CommunityPromo>> currentPromos() {
        return ResponseEntity.ok(communityPromoService.detectAll());
    }
}
