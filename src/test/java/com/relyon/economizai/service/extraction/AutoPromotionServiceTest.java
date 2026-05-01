package com.relyon.economizai.service.extraction;

import com.relyon.economizai.model.LearnedDictionaryEntry;
import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.enums.CategorizationSource;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.repository.LearnedDictionaryRepository;
import com.relyon.economizai.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutoPromotionServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private LearnedDictionaryRepository learnedRepository;
    @Mock private DictionaryClassifier dictionaryClassifier;

    @InjectMocks private AutoPromotionService service;

    @BeforeEach
    void seedThresholds() {
        ReflectionTestUtils.setField(service, "minSamples", 30);
        ReflectionTestUtils.setField(service, "minAgreement", 0.90);
    }

    private Product mlProduct(String name, ProductCategory cat, String genericName) {
        return Product.builder()
                .id(UUID.randomUUID())
                .normalizedName(name)
                .category(cat)
                .genericName(genericName)
                .categorizationSource(CategorizationSource.ML)
                .build();
    }

    private Product userProduct(String name, ProductCategory cat) {
        return Product.builder()
                .id(UUID.randomUUID())
                .normalizedName(name)
                .category(cat)
                .categorizationSource(CategorizationSource.USER)
                .build();
    }

    @Test
    void promotesTokenWith30AgreeingMlSamplesAndNoUserOverride() {
        var products = IntStream.range(0, 30)
                .mapToObj(i -> mlProduct("RACAO PEDIGREE FILHOTE " + i, ProductCategory.OTHER, "Ração"))
                .toList();
        when(productRepository.findAll()).thenReturn(products);
        when(learnedRepository.findByNormalizedToken(any())).thenReturn(Optional.empty());
        when(learnedRepository.findAll()).thenReturn(List.of());

        var outcome = service.promote();

        // "racao", "pedigree", "filhote", and N-gram phrases all reach 30 — multiple promotions.
        verify(learnedRepository, times(outcome.promoted())).save(any(LearnedDictionaryEntry.class));
        assert outcome.promoted() > 0;
    }

    @Test
    void userOverrideBlocksPromotionOfThatExactToken() {
        // 50 ML products say "RACAO" → OTHER. One user disagrees: "RACAO" → GROCERIES.
        // The user override should block promotion of token "racao" specifically;
        // tokens that don't appear in the user product (none here) would still be eligible.
        var products = new java.util.ArrayList<Product>(IntStream.range(0, 50)
                .mapToObj(i -> mlProduct("RACAO " + i, ProductCategory.OTHER, "Ração"))
                .toList());
        products.add(userProduct("RACAO", ProductCategory.GROCERIES));
        when(productRepository.findAll()).thenReturn(products);
        when(learnedRepository.findAll()).thenReturn(List.of());

        var outcome = service.promote();

        // "racao" had a user override → not promoted. Other tokens (numerals) won't
        // hit the 30-sample threshold individually since each ML sample has a unique number.
        assertEquals(0, outcome.promoted());
        verify(learnedRepository, never()).save(any());
    }

    @Test
    void blocksPromotionWhenAgreementBelowThreshold() {
        var groceries = IntStream.range(0, 20)
                .mapToObj(i -> mlProduct("FOO " + i, ProductCategory.GROCERIES, "X"));
        var cleaning = IntStream.range(0, 15)
                .mapToObj(i -> mlProduct("FOO " + i, ProductCategory.CLEANING, "X"));
        var products = java.util.stream.Stream.concat(groceries, cleaning).toList();
        when(productRepository.findAll()).thenReturn(products);
        when(learnedRepository.findAll()).thenReturn(List.of());

        var outcome = service.promote();

        // 35 samples for "foo" but only 20/35 = 57% agreement → blocked
        assertEquals(0, outcome.promoted());
    }

    @Test
    void skipsTokensBelow30Samples() {
        var products = IntStream.range(0, 10)
                .mapToObj(i -> mlProduct("BAR " + i, ProductCategory.OTHER, "Y"))
                .toList();
        when(productRepository.findAll()).thenReturn(products);
        when(learnedRepository.findAll()).thenReturn(List.of());

        var outcome = service.promote();

        assertEquals(0, outcome.promoted());
    }
}
