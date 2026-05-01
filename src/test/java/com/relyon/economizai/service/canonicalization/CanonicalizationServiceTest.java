package com.relyon.economizai.service.canonicalization;

import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.ProductAlias;
import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.ReceiptItem;
import com.relyon.economizai.model.User;
import com.relyon.economizai.repository.ProductAliasRepository;
import com.relyon.economizai.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
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
    @Mock private com.relyon.economizai.service.extraction.ProductExtractor productExtractor;

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
        when(productExtractor.extract(any())).thenReturn(
                new com.relyon.economizai.service.extraction.ProductExtraction(
                        "Leite", null, new java.math.BigDecimal("1"), "L",
                        com.relyon.economizai.model.enums.ProductCategory.MEAT_DAIRY));
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
        assertEquals(com.relyon.economizai.model.enums.ProductCategory.MEAT_DAIRY, product.getCategory());
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

        var outcome = service.canonicalize(receipt);

        assertEquals(1, outcome.unmatched());
        assertNull(receipt.getItems().get(0).getProduct());
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
