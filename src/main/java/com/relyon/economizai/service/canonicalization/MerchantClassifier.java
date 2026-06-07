package com.relyon.economizai.service.canonicalization;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.List;

/**
 * Classifies a merchant from its NFC-e name. Used as a fallback categorization
 * signal: items a pharmacy sells that the dictionary can't place default to
 * PHARMACY instead of OTHER. Name-based and synchronous — no external call, so
 * it applies at confirm time with no async gap. (A CNAE-based upgrade via an
 * external CNPJ API can refine this later for oddly-named drugstores.)
 *
 * <p>High precision by design: only unambiguous drugstore markers match, so a
 * "Supermercado São João" is never mistaken for "Farmácias São João". Items the
 * dictionary already knows keep their category — this only fills OTHER.
 */
@Slf4j
@Service
public class MerchantClassifier {

    // Substrings (normalized: uppercase, no accents) that reliably mark a
    // pharmacy/drugstore. Kept conservative — bare ambiguous tokens excluded.
    private static final List<String> PHARMACY_MARKERS = List.of(
            "FARMACIA", "DROGARIA", "DROGASIL", "DROGA RAIA", "RAIA DROGASIL",
            "DROGAO", "DROGAL", "DROGAFUJI", "PANVEL", "PAGUE MENOS",
            "ULTRAFARMA", "BIFARMA", "NISSEI", "AGAFARMA", "PERMANENTE FARMA",
            "VENANCIO", "PACHECO", "DIMED", "PANARELLO", "PROFARMA",
            "DISTRIBUIDORA DE MEDICAMENTOS", "PRODUTOS FARMACEUTICOS");

    public boolean isPharmacy(String marketName) {
        if (marketName == null || marketName.isBlank()) return false;
        var normalized = normalize(marketName);
        var match = PHARMACY_MARKERS.stream().anyMatch(normalized::contains);
        if (match) {
            log.debug("merchant.classified segment=PHARMACY name='{}'", marketName);
        }
        return match;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase();
    }
}
