package com.relyon.economizai.service.admin;

import com.relyon.economizai.dto.request.MergeProductRequest;
import com.relyon.economizai.dto.request.SetProductBrandRequest;
import com.relyon.economizai.exception.InvalidProductMergeException;
import com.relyon.economizai.exception.ProductNotFoundException;
import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.ProductAlias;
import com.relyon.economizai.repository.ConsumptionSnoozeRepository;
import com.relyon.economizai.repository.HouseholdProductAliasRepository;
import com.relyon.economizai.repository.ManualPurchaseRepository;
import com.relyon.economizai.repository.PriceObservationRepository;
import com.relyon.economizai.repository.ProductAliasRepository;
import com.relyon.economizai.repository.ProductRepository;
import com.relyon.economizai.repository.ReceiptItemRepository;
import com.relyon.economizai.repository.ShoppingListItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminProductServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private ProductAliasRepository aliasRepository;
    @Mock private ReceiptItemRepository receiptItemRepository;
    @Mock private PriceObservationRepository priceObservationRepository;
    @Mock private ManualPurchaseRepository manualPurchaseRepository;
    @Mock private ShoppingListItemRepository shoppingListItemRepository;
    @Mock private HouseholdProductAliasRepository householdProductAliasRepository;
    @Mock private ConsumptionSnoozeRepository consumptionSnoozeRepository;

    @InjectMocks private AdminProductService service;

    @Test
    void listMissingBrandReturnsProductsWithSampleAliases() {
        var product = Product.builder().id(UUID.randomUUID()).normalizedName("Arroz").build();
        var alias1 = ProductAlias.builder().product(product)
                .rawDescription("ARROZ TIO J 5KG").normalizedDescription("arroz tio j 5kg").build();
        var alias2 = ProductAlias.builder().product(product)
                .rawDescription("ARROZ TIO JOAO 5KG").normalizedDescription("arroz tio joao 5kg").build();
        var pageable = PageRequest.of(0, 20);
        when(productRepository.findMissingBrand(pageable)).thenReturn(new PageImpl<>(List.of(product), pageable, 1));
        when(aliasRepository.findByProductIdIn(List.of(product.getId()))).thenReturn(List.of(alias1, alias2));

        var page = service.listMissingBrand(pageable);

        assertEquals(1, page.getTotalElements());
        var response = page.getContent().get(0);
        assertEquals(product.getId(), response.id());
        assertEquals(2, response.sampleDescriptions().size());
        assertEquals("ARROZ TIO J 5KG", response.sampleDescriptions().get(0));
    }

    @Test
    void listMissingBrandReturnsEmptySamplesWhenNoAliases() {
        var product = Product.builder().id(UUID.randomUUID()).normalizedName("X").build();
        var pageable = PageRequest.of(0, 20);
        when(productRepository.findMissingBrand(pageable)).thenReturn(new PageImpl<>(List.of(product), pageable, 1));
        when(aliasRepository.findByProductIdIn(List.of(product.getId()))).thenReturn(List.of());

        var page = service.listMissingBrand(pageable);

        assertEquals(1, page.getTotalElements());
        assertEquals(0, page.getContent().get(0).sampleDescriptions().size());
    }

    @Test
    void setBrandPersistsTrimmedBrandOnProduct() {
        var product = Product.builder().id(UUID.randomUUID()).normalizedName("Arroz").build();
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.setBrand(product.getId(), new SetProductBrandRequest("  Tio João  "));

        assertEquals("Tio João", response.brand());
        verify(productRepository).save(product);
    }

    @Test
    void setBrandThrowsForUnknownProduct() {
        var unknownId = UUID.randomUUID();
        when(productRepository.findById(unknownId)).thenReturn(Optional.empty());
        assertThrows(ProductNotFoundException.class,
                () -> service.setBrand(unknownId, new SetProductBrandRequest("X")));
    }

    @Test
    void listDuplicateGroupsBucketsByMetadata() {
        var p1 = Product.builder().id(UUID.randomUUID()).normalizedName("Arroz Tio Joao 5KG")
                .genericName("Arroz").brand("Tio Joao").packSize(new BigDecimal("5")).packUnit("KG").build();
        var p2 = Product.builder().id(UUID.randomUUID()).normalizedName("Arroz Tio J 5KG")
                .genericName("Arroz").brand("Tio Joao").packSize(new BigDecimal("5")).packUnit("KG").build();
        var p3 = Product.builder().id(UUID.randomUUID()).normalizedName("Feijao Camil 1KG")
                .genericName("Feijao").brand("Camil").packSize(new BigDecimal("1")).packUnit("KG").build();
        var p4 = Product.builder().id(UUID.randomUUID()).normalizedName("Feijao Camil Carioca 1KG")
                .genericName("Feijao").brand("Camil").packSize(new BigDecimal("1")).packUnit("KG").build();
        when(productRepository.findDuplicateCandidates()).thenReturn(List.of(p1, p2, p3, p4));

        var groups = service.listDuplicateGroups();

        assertEquals(2, groups.size());
        assertEquals(2, groups.get(0).products().size());
        assertEquals(2, groups.get(1).products().size());
    }

    @Test
    void mergeRejectsSelfMerge() {
        var id = UUID.randomUUID();
        assertThrows(InvalidProductMergeException.class,
                () -> service.merge(id, new MergeProductRequest(id, false)));
    }

    @Test
    void mergeDryRunReturnsCountsWithoutApplyingChanges() {
        var survivor = Product.builder().id(UUID.randomUUID()).normalizedName("Survivor").build();
        var absorbed = Product.builder().id(UUID.randomUUID()).normalizedName("Absorbed").build();
        when(productRepository.findById(survivor.getId())).thenReturn(Optional.of(survivor));
        when(productRepository.findById(absorbed.getId())).thenReturn(Optional.of(absorbed));
        when(aliasRepository.countByProduct(absorbed)).thenReturn(3L);
        when(receiptItemRepository.findAllByProductIdOrderByReceiptIssuedAtAsc(absorbed.getId()))
                .thenReturn(List.of());
        when(priceObservationRepository.countByProduct(absorbed)).thenReturn(7L);
        when(manualPurchaseRepository.countByProduct(absorbed)).thenReturn(0L);
        when(shoppingListItemRepository.countByProduct(absorbed)).thenReturn(1L);
        when(householdProductAliasRepository.countByProduct(absorbed)).thenReturn(2L);
        when(consumptionSnoozeRepository.countByProduct(absorbed)).thenReturn(0L);

        var result = service.merge(survivor.getId(),
                new MergeProductRequest(absorbed.getId(), true));

        assertTrue(result.dryRun());
        assertFalse(result.applied());
        assertEquals(3L, result.aliasesMigrated());
        assertEquals(7L, result.priceObservationsMigrated());
        verify(aliasRepository, never()).repointProduct(any(), any());
        verify(productRepository, never()).delete(any());
    }

    @Test
    void mergeAppliedRepointsAndDeletesAbsorbed() {
        var survivor = Product.builder().id(UUID.randomUUID()).normalizedName("Survivor").build();
        var absorbed = Product.builder().id(UUID.randomUUID()).normalizedName("Absorbed").build();
        when(productRepository.findById(survivor.getId())).thenReturn(Optional.of(survivor));
        when(productRepository.findById(absorbed.getId())).thenReturn(Optional.of(absorbed));
        when(receiptItemRepository.findAllByProductIdOrderByReceiptIssuedAtAsc(absorbed.getId()))
                .thenReturn(List.of());
        when(aliasRepository.repointProduct(absorbed, survivor)).thenReturn(2);
        when(receiptItemRepository.repointProduct(absorbed, survivor)).thenReturn(5);
        when(priceObservationRepository.repointProduct(absorbed, survivor)).thenReturn(7);
        when(manualPurchaseRepository.repointProduct(absorbed, survivor)).thenReturn(0);
        when(shoppingListItemRepository.repointProduct(absorbed, survivor)).thenReturn(1);
        when(householdProductAliasRepository.deleteAbsorbedConflictsWithSurvivor(absorbed, survivor)).thenReturn(1);
        when(householdProductAliasRepository.repointProduct(absorbed, survivor)).thenReturn(0);
        when(consumptionSnoozeRepository.deleteAbsorbedConflictsWithSurvivor(absorbed, survivor)).thenReturn(0);
        when(consumptionSnoozeRepository.repointProduct(absorbed, survivor)).thenReturn(0);

        var result = service.merge(survivor.getId(),
                new MergeProductRequest(absorbed.getId(), false));

        assertFalse(result.dryRun());
        assertTrue(result.applied());
        assertEquals(2L, result.aliasesMigrated());
        assertEquals(5L, result.receiptItemsRepointed());
        assertEquals(7L, result.priceObservationsMigrated());
        assertEquals(1L, result.householdAliasesDroppedAsConflict());
        verify(productRepository).delete(absorbed);
    }
}
