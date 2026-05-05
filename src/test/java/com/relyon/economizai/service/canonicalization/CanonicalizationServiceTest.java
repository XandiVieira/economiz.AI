package com.relyon.economizai.service.canonicalization;

import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.ProductAlias;
import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.ReceiptItem;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.CategorizationSource;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.repository.ProductAliasRepository;
import com.relyon.economizai.repository.ProductRepository;
import com.relyon.economizai.service.HouseholdProductAliasService;
import com.relyon.economizai.service.extraction.ProductExtraction;
import com.relyon.economizai.service.extraction.ProductExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CanonicalizationServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private ProductAliasRepository aliasRepository;
    @Mock private ProductExtractor productExtractor;
    @Mock private HouseholdProductAliasService householdProductAliasService;

    @InjectMocks private CanonicalizationService service;

    private Receipt buildReceipt(ReceiptItem... items) {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        var user = User.builder().id(UUID.randomUUID()).email("john@test.com").household(household).build();
        var receipt = Receipt.builder().id(UUID.randomUUID()).user(user).household(household).build();
        for (var item : items) {
            receipt.addItem(item);
        }
        return receipt;
    }

    private ReceiptItem item(String desc, String ean) {
        return ReceiptItem.builder()
                .id(UUID.randomUUID())
                .lineNumber(1)
                .rawDescription(desc)
                .ean(ean)
                .quantity(BigDecimal.ONE)
                .totalPrice(new BigDecimal("9.99"))
                .build();
    }

    @Test
    void matchesExistingProductByEan() {
        var existing = Product.builder().id(UUID.randomUUID()).ean("789").normalizedName("Arroz").build();
        var receipt = buildReceipt(item("ARROZ TIO J", "789"));
        when(productRepository.findByEan("789")).thenReturn(Optional.of(existing));
        when(aliasRepository.existsByNormalizedDescription(anyString())).thenReturn(false);

        var outcome = service.canonicalize(receipt);

        assertEquals(1, outcome.matched());
        assertEquals(existing, receipt.getItems().get(0).getProduct());
        verify(aliasRepository).save(any(ProductAlias.class));
    }

    @Test
    void createsNewProductWhenEanIsUnknown() {
        var receipt = buildReceipt(item("LEITE INTEGRAL 1L", "123"));
        // Brand null → metadata-dedup gate is skipped automatically
        when(productExtractor.extract(any())).thenReturn(
                new ProductExtraction("Leite", null, new BigDecimal("1"), "L",
                        ProductCategory.MEAT_DAIRY, CategorizationSource.DICTIONARY));
        when(productRepository.findByEan("123")).thenReturn(Optional.empty());
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> {
            var p = inv.<Product>getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });
        when(aliasRepository.existsByNormalizedDescription(anyString())).thenReturn(false);

        var outcome = service.canonicalize(receipt);

        assertEquals(1, outcome.created());
        var product = receipt.getItems().get(0).getProduct();
        assertNotNull(product);
        assertEquals("123", product.getEan());
        assertEquals("Leite", product.getGenericName());
        assertEquals("L", product.getPackUnit());
        assertEquals(ProductCategory.MEAT_DAIRY, product.getCategory());
    }

    @Test
    void dedupsToExistingProductWhenEanIsUnknownButMetadataMatches() {
        var existing = Product.builder().id(UUID.randomUUID())
                .ean("7891000100103")
                .normalizedName("Leite Integral Itambe 1L")
                .genericName("Leite")
                .brand("Itambe")
                .packSize(new BigDecimal("1"))
                .packUnit("L")
                .build();
        var receipt = buildReceipt(item("LEITE INTEGRAL ITAMBE 1L", "INTERNO99")); // pseudo-EAN from a small market
        when(productRepository.findByEan("INTERNO99")).thenReturn(Optional.empty());
        when(productExtractor.extract(any())).thenReturn(
                new ProductExtraction("Leite", "Itambe", new BigDecimal("1"), "L",
                        ProductCategory.MEAT_DAIRY, CategorizationSource.DICTIONARY));
        when(productRepository.findByMetadata("Leite", "Itambe", new BigDecimal("1"), "L"))
                .thenReturn(List.of(existing));
        when(aliasRepository.existsByNormalizedDescription(anyString())).thenReturn(false);

        var outcome = service.canonicalize(receipt);

        assertEquals(1, outcome.matched());
        assertEquals(existing, receipt.getItems().get(0).getProduct());
        // existing product's EAN is preserved, NOT overwritten with the pseudo-EAN
        assertEquals("7891000100103", receipt.getItems().get(0).getProduct().getEan());
        verify(productRepository, never()).save(any(Product.class));
        verify(aliasRepository).save(any(ProductAlias.class));
    }

    @Test
    void dedupSkippedWhenBrandIsMissing() {
        var receipt = buildReceipt(item("LEITE INTEGRAL 1L", "123"));
        when(productRepository.findByEan("123")).thenReturn(Optional.empty());
        // brand null → dedup gate skipped, falls through to create new
        when(productExtractor.extract(any())).thenReturn(
                new ProductExtraction("Leite", null, new BigDecimal("1"), "L",
                        ProductCategory.MEAT_DAIRY, CategorizationSource.DICTIONARY));
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> {
            var p = inv.<Product>getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });
        when(aliasRepository.existsByNormalizedDescription(anyString())).thenReturn(false);

        var outcome = service.canonicalize(receipt);

        assertEquals(1, outcome.created());
        verify(productRepository, never()).findByMetadata(any(), any(), any(), any());
    }

    @Test
    void matchesByAliasWhenNoEan() {
        var product = Product.builder().id(UUID.randomUUID()).normalizedName("Banana").build();
        var alias = ProductAlias.builder().product(product).normalizedDescription("banana caturra kg").build();
        var receipt = buildReceipt(item("BANANA CATURRA KG", null));
        when(aliasRepository.findByNormalizedDescription("banana caturra kg")).thenReturn(Optional.of(alias));

        var outcome = service.canonicalize(receipt);

        assertEquals(1, outcome.matched());
        assertEquals(product, receipt.getItems().get(0).getProduct());
        verify(productRepository, never()).findByEan(any());
    }

    @Test
    void leavesItemUnmatchedWhenNoEanAndNoAlias() {
        var receipt = buildReceipt(item("ITEM DESCONHECIDO", null));
        when(aliasRepository.findByNormalizedDescription(anyString())).thenReturn(Optional.empty());
        when(productExtractor.extract(any())).thenReturn(ProductExtraction.empty());

        var outcome = service.canonicalize(receipt);

        assertEquals(1, outcome.unmatched());
        assertNull(receipt.getItems().get(0).getProduct());
    }

    @Test
    void fuzzyMatchesByAliasSimilarityWhenExactAliasMisses() {
        // Existing product cataloged via "ARROZ TIO JOAO 5KG"
        var product = Product.builder().id(UUID.randomUUID())
                .normalizedName("Arroz Tio Joao 5KG")
                .genericName("Arroz")
                .packSize(new BigDecimal("5"))
                .packUnit("KG")
                .build();
        var existingAlias = ProductAlias.builder()
                .product(product)
                .normalizedDescription("arroz tio joao 5kg")
                .build();
        // Incoming item from another market with abbreviation
        var receipt = buildReceipt(item("ARROZ TIO J 5KG", null));
        when(aliasRepository.findByNormalizedDescription("arroz tio j 5kg")).thenReturn(Optional.empty());
        when(productExtractor.extract("ARROZ TIO J 5KG")).thenReturn(
                new ProductExtraction("Arroz", "Tio Joao", new BigDecimal("5"), "KG",
                        ProductCategory.GROCERIES, CategorizationSource.DICTIONARY));
        when(aliasRepository.findCandidatesByProductMetadata("Arroz", new BigDecimal("5"), "KG"))
                .thenReturn(List.of(existingAlias));
        when(aliasRepository.existsByNormalizedDescription("arroz tio j 5kg")).thenReturn(false);

        var outcome = service.canonicalize(receipt);

        assertEquals(1, outcome.matched());
        assertEquals(product, receipt.getItems().get(0).getProduct());
        verify(aliasRepository).save(any(ProductAlias.class)); // new alias persisted for the variant
    }

    @Test
    void fuzzyDoesNotMatchWhenScoreBelowThreshold() {
        var product = Product.builder().id(UUID.randomUUID())
                .normalizedName("Arroz")
                .genericName("Arroz")
                .packSize(new BigDecimal("5"))
                .packUnit("KG")
                .build();
        var existingAlias = ProductAlias.builder()
                .product(product)
                .normalizedDescription("arroz tio joao tipo 1 5kg")
                .build();
        var receipt = buildReceipt(item("FEIJAO PRETO 5KG", null));
        when(aliasRepository.findByNormalizedDescription("feijao preto 5kg")).thenReturn(Optional.empty());
        when(productExtractor.extract("FEIJAO PRETO 5KG")).thenReturn(
                new ProductExtraction("Arroz", null, new BigDecimal("5"), "KG",
                        ProductCategory.GROCERIES, CategorizationSource.DICTIONARY));
        when(aliasRepository.findCandidatesByProductMetadata("Arroz", new BigDecimal("5"), "KG"))
                .thenReturn(List.of(existingAlias));

        var outcome = service.canonicalize(receipt);

        assertEquals(1, outcome.unmatched());
        assertNull(receipt.getItems().get(0).getProduct());
        verify(aliasRepository, never()).save(any(ProductAlias.class));
    }

    @Test
    void fuzzySkippedWhenMetadataIncomplete() {
        // No packSize extracted — fuzzy is skipped to avoid wide-net false positives
        var receipt = buildReceipt(item("BANANA PRATA KG", null));
        when(aliasRepository.findByNormalizedDescription("banana prata kg")).thenReturn(Optional.empty());
        when(productExtractor.extract("BANANA PRATA KG")).thenReturn(
                new ProductExtraction("Banana Prata", null, null, null,
                        ProductCategory.PRODUCE, CategorizationSource.DICTIONARY));

        var outcome = service.canonicalize(receipt);

        assertEquals(1, outcome.unmatched());
        verify(aliasRepository, never()).findCandidatesByProductMetadata(any(), any(), any());
    }

    @Test
    void doesNotDuplicateAliasWhenItAlreadyExists() {
        var existing = Product.builder().id(UUID.randomUUID()).ean("789").normalizedName("Arroz").build();
        var receipt = buildReceipt(item("ARROZ TIO J", "789"));
        when(productRepository.findByEan("789")).thenReturn(Optional.of(existing));
        when(aliasRepository.existsByNormalizedDescription("arroz tio j")).thenReturn(true);

        service.canonicalize(receipt);

        verify(aliasRepository, never()).save(any(ProductAlias.class));
    }

    @Test
    void skipsAlreadyCanonicalizedItems() {
        var product = Product.builder().id(UUID.randomUUID()).normalizedName("X").build();
        var item = item("X", null);
        item.setProduct(product);
        var receipt = buildReceipt(item);

        var outcome = service.canonicalize(receipt);

        assertEquals(1, outcome.matched());
        verify(aliasRepository, never()).save(any());
    }
}
