package com.relyon.economizai.service.extraction;

import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.HouseholdProductCategoryOverride;
import com.relyon.economizai.model.LearnedDictionaryEntry;
import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.enums.CategorizationSource;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.repository.HouseholdProductCategoryOverrideRepository;
import com.relyon.economizai.repository.LearnedDictionaryRepository;
import com.relyon.economizai.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Branch coverage beyond {@link ConsensusPromotionServiceTest}: null-category
 * overrides ignored, already-graduated products not re-saved, missing products
 * skipped, token disagreement / under-threshold skips, the upsert-existing
 * learned-entry path, the learned-dictionary reload, and scheduledPromote.
 */
@ExtendWith(MockitoExtension.class)
class ConsensusPromotionServiceCoverageTest {

    @Mock private HouseholdProductCategoryOverrideRepository overrideRepository;
    @Mock private ProductRepository productRepository;
    @Mock private LearnedDictionaryRepository learnedRepository;
    @Mock private DictionaryClassifier dictionaryClassifier;

    private ConsensusPromotionService service;

    @BeforeEach
    void setUp() {
        service = new ConsensusPromotionService(overrideRepository, productRepository, learnedRepository, dictionaryClassifier);
        ReflectionTestUtils.setField(service, "minHouseholds", 2);
        ReflectionTestUtils.setField(service, "minTokenProducts", 2);
        lenient().when(learnedRepository.findAll()).thenReturn(List.of());
        lenient().when(learnedRepository.findByNormalizedToken(any())).thenReturn(Optional.empty());
    }

    private HouseholdProductCategoryOverride override(Product product, ProductCategory category) {
        return HouseholdProductCategoryOverride.builder()
                .household(Household.builder().id(UUID.randomUUID()).build())
                .product(product).category(category).build();
    }

    private Product product(String name, ProductCategory category, CategorizationSource source) {
        return Product.builder().id(UUID.randomUUID()).normalizedName(name)
                .category(category).categorizationSource(source).build();
    }

    @Test
    void nullCategoryOverrides_areIgnored() {
        var milho = product("MILHO ODERICH 200G", ProductCategory.OTHER, CategorizationSource.ML);
        // Two null-category (custom) corrections — never count toward consensus.
        when(overrideRepository.findAll()).thenReturn(List.of(
                override(milho, null), override(milho, null)));

        var outcome = service.promote();

        assertEquals(0, outcome.productsGraduated());
        verify(productRepository).findAllById(Set.of());
        verify(productRepository, never()).save(any());
    }

    @Test
    void productAlreadyGraduatedToConsensusCategory_isNotResaved() {
        // Already USER + GROCERIES → no mutation, but tokens still feed.
        var milho = product("MILHO ODERICH", ProductCategory.GROCERIES, CategorizationSource.USER);
        when(overrideRepository.findAll()).thenReturn(List.of(
                override(milho, ProductCategory.GROCERIES),
                override(milho, ProductCategory.GROCERIES)));
        when(productRepository.findAllById(Set.of(milho.getId()))).thenReturn(List.of(milho));

        var outcome = service.promote();

        assertEquals(0, outcome.productsGraduated(), "no-op when already graduated identically");
        verify(productRepository, never()).save(any());
    }

    @Test
    void missingProduct_isSkipped() {
        var milho = product("MILHO", ProductCategory.OTHER, CategorizationSource.ML);
        when(overrideRepository.findAll()).thenReturn(List.of(
                override(milho, ProductCategory.GROCERIES),
                override(milho, ProductCategory.GROCERIES)));
        when(productRepository.findAllById(Set.of(milho.getId()))).thenReturn(List.of());

        var outcome = service.promote();

        assertEquals(0, outcome.productsGraduated());
        verify(productRepository, never()).save(any());
    }

    @Test
    void tokenDisagreementAcrossProducts_isNotLearned() {
        // Both products share token "milho" but graduate to DIFFERENT categories
        // → token has 2 category votes → disagreement → not learned.
        var milhoA = product("MILHO ODERICH 200G", ProductCategory.OTHER, CategorizationSource.ML);
        var milhoB = product("MILHO VERDE PREDILECTA 170G", ProductCategory.OTHER, CategorizationSource.ML);
        when(overrideRepository.findAll()).thenReturn(List.of(
                override(milhoA, ProductCategory.GROCERIES),
                override(milhoA, ProductCategory.GROCERIES),
                override(milhoB, ProductCategory.PRODUCE),
                override(milhoB, ProductCategory.PRODUCE)));
        when(productRepository.findAllById(Set.of(milhoA.getId(), milhoB.getId())))
                .thenReturn(List.of(milhoA, milhoB));

        var outcome = service.promote();

        assertEquals(2, outcome.productsGraduated());
        assertEquals(0, outcome.tokensLearned(), "conflicting categories block the shared token");
    }

    @Test
    void tokenBelowMinTokenProducts_isNotLearned() {
        // Single consensus product → its tokens appear only once → below threshold 2.
        var milho = product("MILHO ODERICH 200G", ProductCategory.OTHER, CategorizationSource.ML);
        when(overrideRepository.findAll()).thenReturn(List.of(
                override(milho, ProductCategory.GROCERIES),
                override(milho, ProductCategory.GROCERIES)));
        when(productRepository.findAllById(Set.of(milho.getId()))).thenReturn(List.of(milho));

        var outcome = service.promote();

        assertEquals(1, outcome.productsGraduated());
        assertEquals(0, outcome.tokensLearned());
        verify(learnedRepository, never()).save(any());
    }

    @Test
    void existingLearnedEntry_isUpdatedInPlace() {
        var milhoA = product("MILHO ODERICH 200G", ProductCategory.OTHER, CategorizationSource.ML);
        var milhoB = product("MILHO VERDE 170G", ProductCategory.OTHER, CategorizationSource.ML);
        when(overrideRepository.findAll()).thenReturn(List.of(
                override(milhoA, ProductCategory.GROCERIES),
                override(milhoA, ProductCategory.GROCERIES),
                override(milhoB, ProductCategory.GROCERIES),
                override(milhoB, ProductCategory.GROCERIES)));
        when(productRepository.findAllById(Set.of(milhoA.getId(), milhoB.getId())))
                .thenReturn(List.of(milhoA, milhoB));
        var existing = LearnedDictionaryEntry.builder()
                .normalizedToken("milho").category(ProductCategory.OTHER)
                .promotedAt(LocalDateTime.now().minusDays(5)).build();
        when(learnedRepository.findByNormalizedToken("milho")).thenReturn(Optional.of(existing));

        var outcome = service.promote();

        assertTrue(outcome.tokensLearned() >= 1);
        // existing instance reused and re-categorized, not replaced.
        assertEquals(ProductCategory.GROCERIES, existing.getCategory());
        verify(learnedRepository).save(existing);
    }

    @Test
    void reloadLearnedEntries_pushesAllEntriesIntoClassifier() {
        var milho = product("MILHO ODERICH 200G", ProductCategory.OTHER, CategorizationSource.ML);
        when(overrideRepository.findAll()).thenReturn(List.of(
                override(milho, ProductCategory.GROCERIES),
                override(milho, ProductCategory.GROCERIES)));
        when(productRepository.findAllById(Set.of(milho.getId()))).thenReturn(List.of(milho));
        var learnedEntry = LearnedDictionaryEntry.builder()
                .normalizedToken("milho").genericName("milho").category(ProductCategory.GROCERIES)
                .promotedAt(LocalDateTime.now()).build();
        when(learnedRepository.findAll()).thenReturn(List.of(learnedEntry));

        var outcome = service.promote();

        assertEquals(1, outcome.learnedTotal());
        var mapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(dictionaryClassifier).replaceLearnedEntries(mapCaptor.capture());
        assertTrue(mapCaptor.getValue().containsKey("milho"));
    }

    @Test
    void scheduledPromote_delegatesToPromote() {
        when(overrideRepository.findAll()).thenReturn(List.of());

        service.scheduledPromote();

        verify(overrideRepository).findAll();
        verify(dictionaryClassifier).replaceLearnedEntries(anyMap());
    }

    @Test
    void emptyOverrides_produceZeroOutcome() {
        when(overrideRepository.findAll()).thenReturn(List.of());

        var outcome = service.promote();

        assertEquals(0, outcome.productsGraduated());
        assertEquals(0, outcome.tokensLearned());
        assertEquals(0, outcome.learnedTotal());
    }
}
