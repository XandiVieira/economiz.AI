package com.relyon.economizai.service.canonicalization;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MerchantClassifierTest {

    private final MerchantClassifier classifier = new MerchantClassifier();

    @ParameterizedTest
    @ValueSource(strings = {
            "DIMED S/A DISTRIBUIDORA DE MEDICAMENTOS",
            "DROGARIA SAO JOAO LTDA",
            "FARMACIA E DROGARIA NISSEI",
            "PANVEL FARMACIAS",
            "RAIA DROGASIL S.A.",
            "DROGÃO SUPER"
    })
    void recognizesPharmacies(String name) {
        assertTrue(classifier.isPharmacy(name), name);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "COMPANHIA ZAFFARI COMERCIO E INDUSTRIA",
            "BISTEK SUPERMERCADOS LTDA",
            "SUPERMERCADO SAO JOAO LTDA",
            "ATACADAO S.A."
    })
    void doesNotFlagSupermarkets(String name) {
        assertFalse(classifier.isPharmacy(name), name);
    }

    @Test
    void nullOrBlankIsNotPharmacy() {
        assertFalse(classifier.isPharmacy(null));
        assertFalse(classifier.isPharmacy("  "));
    }
}
