package com.relyon.economizai.service.extraction;

import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.service.canonicalization.DescriptionNormalizer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
public class DictionaryClassifier {

    private static final int MAX_PHRASE_TOKENS = 3;
    private final Map<String, DictEntry> entriesByKey = new LinkedHashMap<>();

    @PostConstruct
    void load() throws IOException {
        for (var row : CsvSeedLoader.load("seed/product-dictionary.csv")) {
            if (row.length < 3) continue;
            var key = row[0].trim().toLowerCase();
            var generic = row[1].trim().isEmpty() ? null : row[1].trim();
            var categoryRaw = row[2].trim();
            if (key.isEmpty() || categoryRaw.isEmpty()) continue;
            try {
                entriesByKey.put(key, new DictEntry(generic, ProductCategory.valueOf(categoryRaw)));
            } catch (IllegalArgumentException ex) {
                log.warn("Invalid category '{}' for dictionary key '{}', skipping", categoryRaw, key);
            }
        }
        log.info("Loaded {} dictionary entries", entriesByKey.size());
    }

    public DictEntry classify(String rawDescription) {
        var normalized = DescriptionNormalizer.normalize(rawDescription);
        if (normalized.isBlank()) return DictEntry.EMPTY;
        var tokens = normalized.split("\\s+");
        for (var size = MAX_PHRASE_TOKENS; size >= 1; size--) {
            for (var i = 0; i + size <= tokens.length; i++) {
                var phrase = String.join(" ", java.util.Arrays.copyOfRange(tokens, i, i + size));
                var entry = entriesByKey.get(phrase);
                if (entry != null) return entry;
            }
        }
        return DictEntry.EMPTY;
    }

    public record DictEntry(String genericName, ProductCategory category) {
        public static final DictEntry EMPTY = new DictEntry(null, null);
    }
}
