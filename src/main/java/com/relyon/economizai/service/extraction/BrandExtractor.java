package com.relyon.economizai.service.extraction;

import com.relyon.economizai.service.canonicalization.DescriptionNormalizer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
public class BrandExtractor {

    private static final int MAX_PHRASE_TOKENS = 3;
    private final Map<String, String> brandsByNormalizedKey = new LinkedHashMap<>();

    @PostConstruct
    void load() throws IOException {
        for (var row : CsvSeedLoader.load("seed/brand-registry.csv")) {
            if (row.length < 2) continue;
            var key = row[0].trim().toLowerCase();
            var display = row[1].trim();
            if (!key.isEmpty() && !display.isEmpty()) {
                brandsByNormalizedKey.put(key, display);
            }
        }
        log.info("Loaded {} brand entries", brandsByNormalizedKey.size());
    }

    public String find(String rawDescription) {
        var normalized = DescriptionNormalizer.normalize(rawDescription);
        if (normalized.isBlank()) return null;
        var tokens = normalized.split("\\s+");
        for (var size = MAX_PHRASE_TOKENS; size >= 1; size--) {
            for (var i = 0; i + size <= tokens.length; i++) {
                var phrase = String.join(" ", java.util.Arrays.copyOfRange(tokens, i, i + size));
                var match = brandsByNormalizedKey.get(phrase);
                if (match != null) return match;
            }
        }
        return null;
    }
}
