package com.relyon.economizai.service.extraction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LearnableTokenFilterTest {

    @Test
    void realProductWordsAreLearnable() {
        assertTrue(LearnableTokenFilter.isLearnable("azeite"));
        assertTrue(LearnableTokenFilter.isLearnable("limpol"));
        assertTrue(LearnableTokenFilter.isLearnable("frimesa iog"));
        assertTrue(LearnableTokenFilter.isLearnable("doce leite"));
    }

    @Test
    void sizeTokensAreNot() {
        // the real poisoning cases from the dev learned dictionary
        assertFalse(LearnableTokenFilter.isLearnable("500ml"), "learned 500ml→CLEANING misfiled olive oil");
        assertFalse(LearnableTokenFilter.isLearnable("2kg"));
        assertFalse(LearnableTokenFilter.isLearnable("165g"));
        assertFalse(LearnableTokenFilter.isLearnable("12x1"));
        assertFalse(LearnableTokenFilter.isLearnable("1,5l"));
    }

    @Test
    void bareUnitsAndFragmentsAreNot() {
        assertFalse(LearnableTokenFilter.isLearnable("kg"), "learned kg→PRODUCE matched every butcher item");
        assertFalse(LearnableTokenFilter.isLearnable("un"));
        assertFalse(LearnableTokenFilter.isLearnable("c"));
        assertFalse(LearnableTokenFilter.isLearnable("s o"));
        assertFalse(LearnableTokenFilter.isLearnable(""));
        assertFalse(LearnableTokenFilter.isLearnable(null));
    }

    @Test
    void phrasesContainingAJunkWordAreNot() {
        assertFalse(LearnableTokenFilter.isLearnable("zip 1kg"));
        assertFalse(LearnableTokenFilter.isLearnable("c vale"));
        assertFalse(LearnableTokenFilter.isLearnable("gallo 500ml"));
    }
}
