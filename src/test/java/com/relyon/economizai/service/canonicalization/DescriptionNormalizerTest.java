package com.relyon.economizai.service.canonicalization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DescriptionNormalizerTest {

    @Test
    void stripsAccentsAndLowercases() {
        assertEquals("acucar refinado", DescriptionNormalizer.normalize("AÇÚCAR REFINADO"));
    }

    @Test
    void collapsesWhitespace() {
        assertEquals("arroz tio joao 5kg", DescriptionNormalizer.normalize("  ARROZ   TIO\tJOAO  5KG  "));
    }

    @Test
    void stripsPunctuation() {
        assertEquals("leite integral 1l", DescriptionNormalizer.normalize("LEITE INTEGRAL, 1L."));
    }

    @Test
    void preservesAlphanumeric() {
        assertEquals("p1 7891234567890", DescriptionNormalizer.normalize("P1 7891234567890"));
    }

    @Test
    void emptyAndNullProduceEmptyString() {
        assertEquals("", DescriptionNormalizer.normalize(""));
        assertEquals("", DescriptionNormalizer.normalize(null));
    }
}
