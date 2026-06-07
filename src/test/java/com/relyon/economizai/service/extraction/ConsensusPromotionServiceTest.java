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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConsensusPromotionServiceTest {

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

    private HouseholdProductCategoryOverride override(UUID household, Product product, ProductCategory category) {
        return HouseholdProductCategoryOverride.builder()
                .household(Household.builder().id(household).build())
                .product(product).category(category).build();
    }

    private Product product(String name) {
        return Product.builder().id(UUID.randomUUID()).normalizedName(name)
                .category(ProductCategory.OTHER).categorizationSource(CategorizationSource.ML).build();
    }

    @Test
    void singleHousehold_doesNotGraduate() {
        var milho = product("MILHO ODERICH 200G");
        when(overrideRepository.findAll()).thenReturn(List.of(
                override(UUID.randomUUID(), milho, ProductCategory.GROCERIES)));

        var outcome = service.promote();

        assertEquals(0, outcome.productsGraduated());
        verify(productRepository, never()).save(any());
    }

    @Test
    void twoHouseholdsAgree_graduatesProductGlobally() {
        var milho = product("MILHO ODERICH 200G");
        when(overrideRepository.findAll()).thenReturn(List.of(
                override(UUID.randomUUID(), milho, ProductCategory.GROCERIES),
                override(UUID.randomUUID(), milho, ProductCategory.GROCERIES)));
        when(productRepository.findById(milho.getId())).thenReturn(Optional.of(milho));

        var outcome = service.promote();

        assertEquals(1, outcome.productsGraduated());
        assertEquals(ProductCategory.GROCERIES, milho.getCategory());
        assertEquals(CategorizationSource.USER, milho.getCategorizationSource());
        verify(productRepository).save(milho);
    }

    @Test
    void tie_doesNotGraduate() {
        var milho = product("MILHO ODERICH 200G");
        when(overrideRepository.findAll()).thenReturn(List.of(
                override(UUID.randomUUID(), milho, ProductCategory.GROCERIES),
                override(UUID.randomUUID(), milho, ProductCategory.GROCERIES),
                override(UUID.randomUUID(), milho, ProductCategory.PRODUCE),
                override(UUID.randomUUID(), milho, ProductCategory.PRODUCE)));
        lenient().when(productRepository.findById(milho.getId())).thenReturn(Optional.of(milho));

        var outcome = service.promote();

        assertEquals(0, outcome.productsGraduated(), "tie is not consensus");
    }

    @Test
    void recurringTokenAcrossConsensusProducts_isLearned() {
        var milhoA = product("MILHO ODERICH 200G");
        var milhoB = product("MILHO VERDE PREDILECTA 170G");
        when(overrideRepository.findAll()).thenReturn(List.of(
                override(UUID.randomUUID(), milhoA, ProductCategory.GROCERIES),
                override(UUID.randomUUID(), milhoA, ProductCategory.GROCERIES),
                override(UUID.randomUUID(), milhoB, ProductCategory.GROCERIES),
                override(UUID.randomUUID(), milhoB, ProductCategory.GROCERIES)));
        when(productRepository.findById(milhoA.getId())).thenReturn(Optional.of(milhoA));
        when(productRepository.findById(milhoB.getId())).thenReturn(Optional.of(milhoB));

        var outcome = service.promote();

        assertEquals(2, outcome.productsGraduated());
        // "milho" appears in both consensus products, all GROCERIES → learned
        var captor = ArgumentCaptor.forClass(LearnedDictionaryEntry.class);
        verify(learnedRepository, atLeastOnce()).save(captor.capture());
        var learnedMilho = captor.getAllValues().stream()
                .anyMatch(e -> e.getNormalizedToken().equals("milho") && e.getCategory() == ProductCategory.GROCERIES);
        assertEquals(true, learnedMilho);
    }
}
