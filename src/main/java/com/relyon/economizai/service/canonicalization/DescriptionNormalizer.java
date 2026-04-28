package com.relyon.economizai.service.canonicalization;

import java.text.Normalizer;

public final class DescriptionNormalizer {

    private DescriptionNormalizer() {}

    public static String normalize(String raw) {
        if (raw == null) return "";
        var stripped = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return stripped.toLowerCase()
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
