package com.relyon.economizai.controller;

import com.relyon.economizai.dto.response.MarketResponse;
import com.relyon.economizai.model.User;
import com.relyon.economizai.service.geo.WatchedMarketService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Markets the user has shopped at, plus user-curated "watchlist" of CNPJs
 * they want monitored beyond the home-radius default — e.g. a market on
 * the way to work.
 */
@RestController
@RequestMapping("/api/v1/markets")
@RequiredArgsConstructor
@Tag(name = "Markets", description = "Market catalogue + user-curated watchlist")
public class MarketController {

    private final WatchedMarketService watchedMarketService;

    /** Catalogue for the picker UI: visited + watched + (optionally) nearby. */
    @GetMapping
    public ResponseEntity<List<MarketResponse>> list(@AuthenticationPrincipal User user,
                                                     @RequestParam(required = false) Double radiusKm) {
        return ResponseEntity.ok(watchedMarketService.listForPicker(user, radiusKm));
    }

    /** "Meus mercados" — only the markets the user has explicitly pinned. */
    @GetMapping("/watched")
    public ResponseEntity<List<MarketResponse>> watched(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(watchedMarketService.listWatched(user));
    }

    @PostMapping("/watched/{cnpj}")
    public ResponseEntity<MarketResponse> pin(@AuthenticationPrincipal User user, @PathVariable String cnpj) {
        return ResponseEntity.ok(watchedMarketService.watch(user, cnpj));
    }

    @DeleteMapping("/watched/{cnpj}")
    public ResponseEntity<Void> unpin(@AuthenticationPrincipal User user, @PathVariable String cnpj) {
        watchedMarketService.unwatch(user, cnpj);
        return ResponseEntity.noContent().build();
    }
}
