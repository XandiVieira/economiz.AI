package com.relyon.economizai.service.extraction;

import com.relyon.economizai.model.enums.CategorizationSource;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.service.canonicalization.DescriptionNormalizer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Two-tier dictionary lookup:
 *   - "curated" entries from src/main/resources/seed/product-dictionary.csv
 *     (hand-maintained, highest priority)
 *   - "learned" entries auto-promoted by AutoPromotionService from stable
 *     ML predictions (Phase 2.5c) — populated at runtime via addLearned()
 *
 * Curated wins on key collision. Both contribute to dictionary coverage at
 * inference time. Returned DictEntry carries the source so callers know
 * whether the answer came from human curation (DICTIONARY) or
 * auto-promoted ML (LEARNED_DICTIONARY).
 */
@Slf4j
@Component
public class DictionaryClassifier {

    private static final int MAX_PHRASE_TOKENS = 3;
    private final Map<String, DictEntry> curatedEntries = new LinkedHashMap<>();
    private final AtomicReference<Map<String, DictEntry>> learnedRef = new AtomicReference<>(Map.of());

    @PostConstruct
    void load() throws IOException {
        for (var row : CsvSeedLoader.load("seed/product-dictionary.csv")) {
            if (row.length < 3) continue;
            var key = row[0].trim().toLowerCase();
            var generic = row[1].trim().isEmpty() ? null : row[1].trim();
            var categoryRaw = row[2].trim();
            if (key.isEmpty() || categoryRaw.isEmpty()) continue;
            try {
                curatedEntries.put(key, new DictEntry(generic, ProductCategory.valueOf(categoryRaw),
                        CategorizationSource.DICTIONARY));
            } catch (IllegalArgumentException ex) {
                log.warn("Invalid category '{}' for dictionary key '{}', skipping", categoryRaw, key);
            }
        }
        log.info("Loaded {} curated dictionary entries", curatedEntries.size());
    }

    /**
     * Replaces the in-memory learned-entries map atomically. Called by
     * AutoPromotionService and ConsensusPromotionService after each promotion
     * pass. Lock-free: the reference swap is atomic, so classify() always reads
     * a consistent snapshot even when a reload is in progress.
     */
    public void replaceLearnedEntries(Map<String, DictEntry> entries) {
        learnedRef.set(Map.copyOf(entries));
        log.info("Loaded {} learned dictionary entries", entries.size());
    }

    public DictEntry classify(String rawDescription) {
        var normalized = DescriptionNormalizer.normalize(rawDescription);
        if (normalized.isBlank()) return DictEntry.EMPTY;
        var tokens = normalized.split("\\s+");
        var learned = learnedRef.get(); // single read — consistent snapshot throughout this call
        for (var size = MAX_PHRASE_TOKENS; size >= 1; size--) {
            for (var i = 0; i + size <= tokens.length; i++) {
                var phrase = String.join(" ", Arrays.copyOfRange(tokens, i, i + size));
                var curated = curatedEntries.get(phrase);
                if (curated != null) return curated;
                var entry = learned.get(phrase);
                if (entry != null) return entry;
            }
        }
        return DictEntry.EMPTY;
    }

    public record DictEntry(String genericName, ProductCategory category, CategorizationSource source) {
        public static final DictEntry EMPTY = new DictEntry(null, null, CategorizationSource.NONE);
    }
}
