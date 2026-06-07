package com.relyon.economizai.service.extraction.ml;

import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.enums.CategorizationSource;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MlClassifierServiceTest {

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private MlClassifierService service;

    @BeforeEach
    void seedConfig() {
        ReflectionTestUtils.setField(service, "confidenceThreshold", 0.75);
        ReflectionTestUtils.setField(service, "categoryApplyEnabled", false);
    }

    private Product product(String normalizedName, ProductCategory category,
                            String genericName, CategorizationSource source) {
        return Product.builder()
                .id(UUID.randomUUID())
                .normalizedName(normalizedName)
                .category(category)
                .genericName(genericName)
                .categorizationSource(source)
                .build();
    }

    /**
     * Builds a list large enough to clear MIN_TRAINING_SIZE (30) by repeating a
     * handful of trusted, well-separated grocery/cleaning examples.
     */
    private List<Product> trustedTrainingSet() {
        var products = new ArrayList<Product>();
        for (var index = 0; index < 20; index++) {
            products.add(product("arroz tio joao 5kg " + index, ProductCategory.GROCERIES,
                    "Arroz", CategorizationSource.DICTIONARY));
            products.add(product("limpador veja limao 500ml " + index, ProductCategory.CLEANING,
                    "Limpador", CategorizationSource.USER));
        }
        return products;
    }

    @Test
    void retrain_trainsWhenEnoughTrustedExamples() {
        when(productRepository.findAll()).thenReturn(trustedTrainingSet());

        var outcome = service.retrain();

        assertTrue(outcome.trained());
        assertEquals(40, outcome.categoryExamples());
        assertEquals(40, outcome.genericNameExamples());
        assertNotNull(outcome.elapsed());
        assertTrue(service.isReady());
        assertNotNull(service.getLastTrainedAt());
    }

    @Test
    void retrain_skipsAndStaysNotReadyWhenInsufficientData() {
        var tiny = List.of(
                product("arroz", ProductCategory.GROCERIES, "Arroz", CategorizationSource.DICTIONARY),
                product("leite", ProductCategory.MEAT_DAIRY, "Leite", CategorizationSource.USER)
        );
        when(productRepository.findAll()).thenReturn(tiny);

        var outcome = service.retrain();

        assertFalse(outcome.trained());
        assertEquals(2, outcome.categoryExamples());
        assertFalse(service.isReady());
        assertNull(service.getLastTrainedAt());
    }

    @Test
    void retrain_filtersOutNonTrustedSources() {
        var products = new ArrayList<Product>();
        // 40 ML-sourced products must NOT be used as training signal.
        for (var index = 0; index < 40; index++) {
            products.add(product("ml junk " + index, ProductCategory.OTHER,
                    "Junk", CategorizationSource.ML));
        }
        // Only 2 trusted products → below MIN_TRAINING_SIZE once filtered.
        products.add(product("arroz", ProductCategory.GROCERIES, "Arroz", CategorizationSource.DICTIONARY));
        products.add(product("leite", ProductCategory.MEAT_DAIRY, "Leite", CategorizationSource.USER));
        when(productRepository.findAll()).thenReturn(products);

        var outcome = service.retrain();

        assertFalse(outcome.trained());
        assertEquals(2, outcome.categoryExamples());
        assertFalse(service.isReady());
    }

    @Test
    void retrain_acceptsLearnedDictionaryAsTrusted() {
        var products = new ArrayList<Product>();
        for (var index = 0; index < 35; index++) {
            products.add(product("biscoito recheado " + index, ProductCategory.GROCERIES,
                    "Biscoito", CategorizationSource.LEARNED_DICTIONARY));
        }
        when(productRepository.findAll()).thenReturn(products);

        var outcome = service.retrain();

        assertTrue(outcome.trained());
        assertEquals(35, outcome.categoryExamples());
    }

    @Test
    void retrain_ignoresProductsWithNullCategoryForCategoryModel() {
        var products = new ArrayList<Product>();
        // 35 trusted with category; plus extra trusted ones with null category.
        for (var index = 0; index < 35; index++) {
            products.add(product("arroz " + index, ProductCategory.GROCERIES,
                    "Arroz", CategorizationSource.DICTIONARY));
        }
        for (var index = 0; index < 10; index++) {
            products.add(product("sem categoria " + index, null,
                    "Misterio", CategorizationSource.USER));
        }
        when(productRepository.findAll()).thenReturn(products);

        var outcome = service.retrain();

        assertTrue(outcome.trained());
        assertEquals(35, outcome.categoryExamples());
        // genericName present on all 45 trusted products.
        assertEquals(45, outcome.genericNameExamples());
    }

    @Test
    void predictCategory_returnsEmptyWhenNotReady() {
        var prediction = service.predictCategory("arroz tio joao");

        assertNull(prediction.label());
        assertEquals(0.0, prediction.confidence());
    }

    @Test
    void predictGenericName_returnsEmptyWhenNotReady() {
        var prediction = service.predictGenericName("arroz tio joao");

        assertNull(prediction.label());
        assertEquals(0.0, prediction.confidence());
    }

    @Test
    void predictCategory_returnsTrainedLabelWhenReady() {
        when(productRepository.findAll()).thenReturn(trustedTrainingSet());
        service.retrain();

        var prediction = service.predictCategory("arroz jasmim 5kg");

        assertEquals(ProductCategory.GROCERIES, prediction.label());
        assertTrue(prediction.confidence() > 0.0);
    }

    @Test
    void predictGenericName_returnsTrainedLabelWhenReady() {
        when(productRepository.findAll()).thenReturn(trustedTrainingSet());
        service.retrain();

        var prediction = service.predictGenericName("limpador multiuso 1l");

        assertEquals("Limpador", prediction.label());
    }

    @Test
    void isCategoryApplyEnabled_reflectsConfiguredValue() {
        assertFalse(service.isCategoryApplyEnabled());
        ReflectionTestUtils.setField(service, "categoryApplyEnabled", true);
        assertTrue(service.isCategoryApplyEnabled());
    }

    @Test
    void getConfidenceThreshold_reflectsConfiguredValue() {
        assertEquals(0.75, service.getConfidenceThreshold());
        ReflectionTestUtils.setField(service, "confidenceThreshold", 0.9);
        assertEquals(0.9, service.getConfidenceThreshold());
    }

    @Test
    void retrainBecomesNotReadyAgainWhenDataDropsBelowMinimum() {
        when(productRepository.findAll()).thenReturn(trustedTrainingSet());
        service.retrain();
        assertTrue(service.isReady());

        // A subsequent retrain with too little data flips ready back to false.
        when(productRepository.findAll()).thenReturn(List.of(
                product("arroz", ProductCategory.GROCERIES, "Arroz", CategorizationSource.DICTIONARY)
        ));
        var outcome = service.retrain();

        assertFalse(outcome.trained());
        assertFalse(service.isReady());
    }

    @Test
    void scheduledRetrainDelegatesToRetrain() {
        when(productRepository.findAll()).thenReturn(trustedTrainingSet());

        service.scheduledRetrain();

        assertTrue(service.isReady());
    }
}
