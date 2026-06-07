package com.relyon.economizai.service;

import com.relyon.economizai.dto.request.CreateProductRequest;
import com.relyon.economizai.dto.request.UpdateProductRequest;
import com.relyon.economizai.exception.ProductNotFoundException;
import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.enums.CategorizationSource;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.repository.ProductAliasRepository;
import com.relyon.economizai.repository.ProductRepository;
import com.relyon.economizai.repository.ReceiptItemRepository;
import com.relyon.economizai.service.extraction.ProductExtraction;
import com.relyon.economizai.service.extraction.ProductExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class ProductServiceSearchGetUpdateTest {

    @Mock private ProductRepository productRepository;
    @Mock private ProductAliasRepository aliasRepository;
    @Mock private ReceiptItemRepository receiptItemRepository;
    @Mock private ProductExtractor productExtractor;

    @InjectMocks private ProductService productService;

    private Product buildProduct(UUID id) {
        return Product.builder()
                .id(id)
                .ean("7891234567890")
                .normalizedName("Arroz Tio Joao 5kg")
                .genericName("Arroz")
                .brand("Tio Joao")
                .category(ProductCategory.GROCERIES)
                .unit("UN")
                .packSize(new BigDecimal("5.000"))
                .packUnit("KG")
                .categorizationSource(CategorizationSource.USER)
                .build();
    }

    @Test
    void search_trimsQueryAndMapsResults() {
        var pageable = PageRequest.of(0, 20);
        var product = buildProduct(UUID.randomUUID());
        Page<Product> page = new PageImpl<>(List.of(product));
        when(productRepository.search("arroz", pageable)).thenReturn(page);

        var result = productService.search("  arroz  ", pageable);

        assertEquals(1, result.getTotalElements());
        assertEquals("Arroz Tio Joao 5kg", result.getContent().get(0).normalizedName());
        verify(productRepository).search("arroz", pageable);
    }

    @Test
    void search_blankQueryBecomesNull() {
        var pageable = PageRequest.of(0, 20);
        when(productRepository.search(isNull(), eq(pageable))).thenReturn(new PageImpl<>(List.of()));

        var result = productService.search("   ", pageable);

        assertEquals(0, result.getTotalElements());
        verify(productRepository).search(isNull(), eq(pageable));
    }

    @Test
    void search_nullQueryBecomesNull() {
        var pageable = PageRequest.of(0, 20);
        when(productRepository.search(isNull(), eq(pageable))).thenReturn(new PageImpl<>(List.of()));

        productService.search(null, pageable);

        verify(productRepository).search(isNull(), eq(pageable));
    }

    @Test
    void get_returnsMappedProduct() {
        var id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.of(buildProduct(id)));

        var response = productService.get(id);

        assertEquals(id, response.id());
        assertEquals("Tio Joao", response.brand());
        assertEquals(ProductCategory.GROCERIES, response.category());
    }

    @Test
    void get_throwsWhenMissing() {
        var id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(ProductNotFoundException.class, () -> productService.get(id));
    }

    @Test
    void update_setsFieldsAndMarksSourceUserWhenCategoryProvided() {
        var id = UUID.randomUUID();
        var existing = buildProduct(id);
        when(productRepository.findById(id)).thenReturn(Optional.of(existing));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var request = new UpdateProductRequest(
                "Arroz Tio Joao 1kg", "Arroz Branco", "Tio Joao",
                ProductCategory.GROCERIES, "UN", new BigDecimal("1.000"), "KG");

        var response = productService.update(id, request);

        var saved = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(saved.capture());
        assertEquals("Arroz Tio Joao 1kg", saved.getValue().getNormalizedName());
        assertEquals("Arroz Branco", saved.getValue().getGenericName());
        assertEquals(ProductCategory.GROCERIES, saved.getValue().getCategory());
        assertEquals(CategorizationSource.USER, saved.getValue().getCategorizationSource());
        assertEquals("Arroz Tio Joao 1kg", response.normalizedName());
    }

    @Test
    void update_blankOptionalFieldsBecomeNullAndSourceNoneWhenCategoryNull() {
        var id = UUID.randomUUID();
        var existing = buildProduct(id);
        when(productRepository.findById(id)).thenReturn(Optional.of(existing));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var request = new UpdateProductRequest(
                "Arroz", "   ", "", null, "  ", null, "");

        productService.update(id, request);

        var saved = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(saved.capture());
        assertNull(saved.getValue().getGenericName());
        assertNull(saved.getValue().getBrand());
        assertNull(saved.getValue().getCategory());
        assertNull(saved.getValue().getUnit());
        assertNull(saved.getValue().getPackUnit());
        assertEquals(CategorizationSource.NONE, saved.getValue().getCategorizationSource());
    }

    @Test
    void update_throwsWhenProductMissing() {
        var id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.empty());

        var request = new UpdateProductRequest("X", null, null, null, null, null, null);
        assertThrows(ProductNotFoundException.class, () -> productService.update(id, request));
        verify(productRepository, never()).save(any());
    }

    @Test
    void create_withoutEan_skipsBackfillAndUsesExtractedFallbacks() {
        var extracted = new ProductExtraction(
                "Cerveja", "Stella", new BigDecimal("330"), "ML",
                ProductCategory.BEVERAGES, CategorizationSource.DICTIONARY);
        when(productExtractor.extract("Cerveja Stella Artois 330ml")).thenReturn(extracted);
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> {
            Product product = invocation.getArgument(0);
            product.setId(UUID.randomUUID());
            return product;
        });

        var request = new CreateProductRequest(
                "  ", "Cerveja Stella Artois 330ml", null, null, null, "UN", null, null);

        var response = productService.create(request);

        assertNull(response.ean());
        assertEquals("Cerveja", response.genericName());
        assertEquals("Stella", response.brand());
        assertEquals(ProductCategory.BEVERAGES, response.category());
        assertEquals(CategorizationSource.DICTIONARY, response.categorizationSource());
        verify(receiptItemRepository, never()).linkByEan(any(Product.class), any());
    }
}
