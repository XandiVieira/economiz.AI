package com.relyon.economizai.service.canonicalization;

/**
 * Jaro-Winkler similarity score in [0.0, 1.0]. Higher = more similar.
 * Boosts the score when the strings share a common prefix (up to 4 chars),
 * which works well for grocery item descriptions where the first tokens
 * (product type, brand) carry most of the discriminating signal —
 * "ARROZ TIO J 5KG" and "ARROZ TIO JOAO 5KG" share a long prefix and
 * score high; "ARROZ TIO J 5KG" and "FEIJAO PRETO 1KG" share little
 * and score low.
 *
 * <p>Self-contained (no external dependency). Pure function — safe to
 * call from any thread, no allocation beyond two boolean arrays per call.
 */
public final class JaroWinklerSimilarity {

    private static final double SCALING_FACTOR = 0.1;
    private static final int PREFIX_MAX = 4;

    private JaroWinklerSimilarity() {}

    public static double score(String s1, String s2) {
        if (s1 == null || s2 == null) return 0.0;
        if (s1.equals(s2)) return 1.0;
        var len1 = s1.length();
        var len2 = s2.length();
        if (len1 == 0 || len2 == 0) return 0.0;

        var matchDistance = Math.max(len1, len2) / 2 - 1;
        var s1Matches = new boolean[len1];
        var s2Matches = new boolean[len2];

        var matches = 0;
        for (var i = 0; i < len1; i++) {
            var start = Math.max(0, i - matchDistance);
            var end = Math.min(i + matchDistance + 1, len2);
            for (var j = start; j < end; j++) {
                if (s2Matches[j] || s1.charAt(i) != s2.charAt(j)) continue;
                s1Matches[i] = true;
                s2Matches[j] = true;
                matches++;
                break;
            }
        }
        if (matches == 0) return 0.0;

        var k = 0;
        var transpositions = 0;
        for (var i = 0; i < len1; i++) {
            if (!s1Matches[i]) continue;
            while (!s2Matches[k]) k++;
            if (s1.charAt(i) != s2.charAt(k)) transpositions++;
            k++;
        }

        var m = (double) matches;
        var jaro = (m / len1 + m / len2 + (m - transpositions / 2.0) / m) / 3.0;

        var prefix = 0;
        var prefixLimit = Math.min(Math.min(len1, len2), PREFIX_MAX);
        for (var i = 0; i < prefixLimit; i++) {
            if (s1.charAt(i) == s2.charAt(i)) prefix++;
            else break;
        }
        return jaro + prefix * SCALING_FACTOR * (1.0 - jaro);
    }
}
