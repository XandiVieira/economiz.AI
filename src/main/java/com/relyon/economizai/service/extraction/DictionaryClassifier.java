package com.relyon.economizai.service.extraction;

import com.relyon.economizai.model.enums.CategorizationSource;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.service.canonicalization.DescriptionNormalizer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

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
    private final Map<String, DictEntry> learnedEntries = new LinkedHashMap<>();

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
     * Replaces the in-memory learned-entries map. Called by
     * AutoPromotionService after each promotion pass.
     */
    public synchronized void replaceLearnedEntries(Map<String, DictEntry> entries) {
        learnedEntries.clear();
        learnedEntries.putAll(entries);
        log.info("Loaded {} learned dictionary entries", learnedEntries.size());
    }

    public DictEntry classify(String rawDescription) {
        var normalized = DescriptionNormalizer.normalize(rawDescription);
        if (normalized.isBlank()) return DictEntry.EMPTY;
        var tokens = normalized.split("\\s+");
        for (var size = MAX_PHRASE_TOKENS; size >= 1; size--) {
            for (var i = 0; i + size <= tokens.length; i++) {
                var phrase = String.join(" ", java.util.Arrays.copyOfRange(tokens, i, i + size));
                var curated = curatedEntries.get(phrase);
                if (curated != null) return curated;
                var learned = learnedEntries.get(phrase);
                if (learned != null) return learned;
            }
        }
        return DictEntry.EMPTY;
    }

    public record DictEntry(String genericName, ProductCategory category, CategorizationSource source) {
        public static final DictEntry EMPTY = new DictEntry(null, null, CategorizationSource.NONE);
    }
}
