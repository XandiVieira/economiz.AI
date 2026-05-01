package com.relyon.economizai.service;

import com.relyon.economizai.dto.request.CreateAliasRequest;
import com.relyon.economizai.dto.request.CreateProductRequest;
import com.relyon.economizai.exception.EanConflictException;
import com.relyon.economizai.exception.ProductAliasConflictException;
import com.relyon.economizai.exception.ProductNotFoundException;
import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.ProductAlias;
import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.ReceiptItem;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.repository.ProductAliasRepository;
import com.relyon.economizai.repository.ProductRepository;
import com.relyon.economizai.repository.ReceiptItemRepository;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private ProductAliasRepository aliasRepository;
    @Mock private ReceiptItemRepository receiptItemRepository;
    @Mock private ProductExtractor productExtractor;

    @InjectMocks private ProductService productService;

    private User buildUser() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        return User.builder().id(UUID.randomUUID()).email("john@test.com").household(household).build();
    }

    private ReceiptItem itemFor(Receipt receipt, String desc, String ean) {
        var item = ReceiptItem.builder()
                .id(UUID.randomUUID())
                .lineNumber(1)
                .rawDescription(desc)
                .ean(ean)
                .quantity(BigDecimal.ONE)
                .totalPrice(new BigDecimal("9.99"))
                .build();
        receipt.addItem(item);
        return item;
    }

    @Test
    void createProduct_savesAndBackfillsByEan() {
        var request = new CreateProductRequest("789", "Arroz Tio Joao", null, "Tio Joao", ProductCategory.GROCERIES, "UN", null, null);
        when(productExtractor.extract(any())).thenReturn(ProductExtraction.EMPTY);
        when(productRepository.findByEan("789")).thenReturn(Optional.empty());
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> {
            var p = inv.<Product>getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });
        when(receiptItemRepository.linkByEan(any(Product.class), any())).thenReturn(3);

        var response = productService.create(request);

        assertEquals("Arroz Tio Joao", response.normalizedName());
        verify(receiptItemRepository).linkByEan(any(Product.class), any());
    }

    @Test
    void createProduct_rejectsDuplicateEan() {
        var request = new CreateProductRequest("789", "Arroz", null, null, null, null, null, null);
        when(productRepository.findByEan("789")).thenReturn(Optional.of(Product.builder().id(UUID.randomUUID()).build()));

        assertThrows(EanConflictException.class, () -> productService.create(request));
        verify(productRepository, never()).save(any());
    }

    @Test
    void addAlias_persistsAndBackfillsMatchingItems() {
        var user = buildUser();
        var product = Product.builder().id(UUID.randomUUID()).normalizedName("Banana").build();
        var receipt = Receipt.builder().id(UUID.randomUUID()).user(user).household(user.getHousehold()).build();
        var matching = itemFor(receipt, "BANANA CATURRA KG", null);
        var nonMatching = itemFor(receipt, "MAMAO PAPAYA KG", null);

        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(aliasRepository.existsByNormalizedDescription(anyString())).thenReturn(false);
        when(receiptItemRepository.findUnmatchedForHousehold(user.getHousehold().getId()))
                .thenReturn(List.of(matching, nonMatching));

        productService.addAlias(user, product.getId(), new CreateAliasRequest("Banana Caturra KG"));

        verify(aliasRepository).save(any(ProductAlias.class));
        verify(receiptItemRepository, times(1)).save(matching);
        verify(receiptItemRepository, never()).save(nonMatching);
        assertEquals(product, matching.getProduct());
    }

    @Test
    void addAlias_throwsWhenDuplicate() {
        var user = buildUser();
        var product = Product.builder().id(UUID.randomUUID()).normalizedName("Arroz").build();
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(aliasRepository.existsByNormalizedDescription("arroz tio joao")).thenReturn(true);

        assertThrows(ProductAliasConflictException.class,
                () -> productService.addAlias(user, product.getId(), new CreateAliasRequest("ARROZ TIO JOAO")));
    }

    @Test
    void addAlias_throwsWhenProductMissing() {
        var user = buildUser();
        var id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(ProductNotFoundException.class,
                () -> productService.addAlias(user, id, new CreateAliasRequest("ARROZ")));
    }

    @Test
    void listUnmatched_returnsCurrentHouseholdsItems() {
        var user = buildUser();
        var receipt = Receipt.builder().id(UUID.randomUUID()).marketName("Mercado X")
                .user(user).household(user.getHousehold()).build();
        var item = itemFor(receipt, "ITEM X", null);
        when(receiptItemRepository.findUnmatchedForHousehold(user.getHousehold().getId()))
                .thenReturn(List.of(item));

        var unmatched = productService.listUnmatched(user);

        assertEquals(1, unmatched.size());
        assertEquals("ITEM X", unmatched.get(0).rawDescription());
        assertEquals("Mercado X", unmatched.get(0).marketName());
    }
}
