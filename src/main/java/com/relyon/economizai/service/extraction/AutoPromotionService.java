package com.relyon.economizai.service.extraction;

import com.relyon.economizai.model.LearnedDictionaryEntry;
import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.enums.CategorizationSource;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.repository.LearnedDictionaryRepository;
import com.relyon.economizai.repository.ProductRepository;
import com.relyon.economizai.service.canonicalization.DescriptionNormalizer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Auto-promotes stable ML predictions into the learned-dictionary so the
 * faster, deterministic dictionary path catches them next time.
 *
 * <p>Promotion criteria (all must hold for a token):
 * <ul>
 *   <li>≥ {@value #MIN_SAMPLES} ML-categorized Products contain the token
 *       in their normalizedName</li>
 *   <li>≥ {@value #MIN_AGREEMENT} share of those Products have the same
 *       category as the majority class</li>
 *   <li>Zero USER-corrected Products contain the token (any human override
 *       blocks promotion — we trust humans over the ML)</li>
 * </ul>
 *
 * <p>Tokens are 1- to 3-word phrases extracted from each Product's
 * normalizedName, same way DictionaryClassifier looks them up. Curated CSV
 * always wins over learned, so promoting an entry that the curated CSV
 * already has is a no-op at lookup time.
 *
 * <p>Runs on app startup (after MlClassifierService training) and on a
 * fixed schedule (default daily). Manual trigger: POST /categorizer/auto-promote.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoPromotionService {

    static final int MIN_SAMPLES = 30;
    static final double MIN_AGREEMENT = 0.90;
    private static final int MAX_PHRASE_TOKENS = 3;

    private final ProductRepository productRepository;
    private final LearnedDictionaryRepository learnedRepository;
    private final DictionaryClassifier dictionaryClassifier;

    @PostConstruct
    void loadOnStartup() {
        refreshClassifierMemory();
    }

    @Scheduled(fixedDelayString = "${economizai.ml.auto-promote-interval-ms:86400000}",
               initialDelayString = "${economizai.ml.auto-promote-interval-ms:86400000}")
    public void scheduledPromote() {
        log.info("auto_promote.scheduled trigger");
        promote();
    }

    @Transactional
    public synchronized PromotionOutcome promote() {
        var products = productRepository.findAll();
        var byToken = new HashMap<String, TokenStats>();

        for (var p : products) {
            if (p.getNormalizedName() == null) continue;
            var tokens = phraseTokens(p.getNormalizedName());
            for (var token : tokens) {
                var stats = byToken.computeIfAbsent(token, k -> new TokenStats());
                if (p.getCategorizationSource() == CategorizationSource.USER) {
                    stats.userOverrides++;
                } else if (p.getCategorizationSource() == CategorizationSource.ML
                        && p.getCategory() != null) {
                    stats.mlSamples++;
                    stats.categoryCounts.merge(p.getCategory(), 1, Integer::sum);
                    if (p.getGenericName() != null) {
                        stats.genericNameCounts.merge(p.getGenericName(), 1, Integer::sum);
                    }
                }
            }
        }

        var promoted = 0;
        var skippedHuman = 0;
        var skippedAgreement = 0;
        var skippedSamples = 0;

        for (var entry : byToken.entrySet()) {
            var token = entry.getKey();
            var stats = entry.getValue();
            if (stats.userOverrides > 0) {
                if (stats.mlSamples >= MIN_SAMPLES) skippedHuman++;
                continue;
            }
            if (stats.mlSamples < MIN_SAMPLES) {
                skippedSamples++;
                continue;
            }
            var topCategory = stats.topCategory();
            var agreement = (double) stats.categoryCounts.get(topCategory) / stats.mlSamples;
            if (agreement < MIN_AGREEMENT) {
                skippedAgreement++;
                continue;
            }
            var topGeneric = stats.topGenericName();
            upsertLearnedEntry(token, topGeneric, topCategory, stats.mlSamples);
            promoted++;
            log.info("auto_promote.promoted token='{}' category={} genericName='{}' samples={} agreement={}",
                    token, topCategory, topGeneric, stats.mlSamples, String.format("%.2f", agreement));
        }

        var totalLearned = refreshClassifierMemory();
        var outcome = new PromotionOutcome(promoted, skippedHuman, skippedAgreement, skippedSamples, totalLearned);
        log.info("auto_promote.done {}", outcome);
        return outcome;
    }

    private int refreshClassifierMemory() {
        var entries = learnedRepository.findAll();
        var map = new LinkedHashMap<String, DictionaryClassifier.DictEntry>();
        for (var entry : entries) {
            map.put(entry.getNormalizedToken(), new DictionaryClassifier.DictEntry(
                    entry.getGenericName(),
                    entry.getCategory(),
                    CategorizationSource.LEARNED_DICTIONARY));
        }
        dictionaryClassifier.replaceLearnedEntries(map);
        return entries.size();
    }

    private void upsertLearnedEntry(String token, String genericName, ProductCategory category, int samples) {
        var existing = learnedRepository.findByNormalizedToken(token).orElseGet(() ->
                LearnedDictionaryEntry.builder()
                        .normalizedToken(token)
                        .promotedAt(LocalDateTime.now())
                        .build());
        existing.setGenericName(genericName);
        existing.setCategory(category);
        existing.setSampleCount(samples);
        existing.setPromotedAt(LocalDateTime.now());
        learnedRepository.save(existing);
    }

    private List<String> phraseTokens(String text) {
        var normalized = DescriptionNormalizer.normalize(text);
        if (normalized.isBlank()) return List.of();
        var tokens = normalized.split("\\s+");
        var phrases = new java.util.ArrayList<String>();
        for (var size = MAX_PHRASE_TOKENS; size >= 1; size--) {
            for (var i = 0; i + size <= tokens.length; i++) {
                phrases.add(String.join(" ", java.util.Arrays.copyOfRange(tokens, i, i + size)));
            }
        }
        return phrases;
    }

    private static class TokenStats {
        int mlSamples = 0;
        int userOverrides = 0;
        Map<ProductCategory, Integer> categoryCounts = new HashMap<>();
        Map<String, Integer> genericNameCounts = new HashMap<>();

        ProductCategory topCategory() {
            return categoryCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
        }

        String topGenericName() {
            return genericNameCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
        }
    }

    public record PromotionOutcome(int promoted, int skippedDueToHuman, int skippedDueToAgreement,
                                   int skippedDueToSamples, int learnedTotal) {}
}
