package com.relyon.economizai.service;

import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.HouseholdProductAlias;
import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.ReceiptItem;
import com.relyon.economizai.repository.HouseholdProductAliasRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HouseholdProductAliasServiceTest {

    @Mock private HouseholdProductAliasRepository repository;
    @InjectMocks private HouseholdProductAliasService service;

    private Household household() {
        return Household.builder().id(UUID.randomUUID()).build();
    }

    private Product product() {
        return Product.builder().id(UUID.randomUUID()).normalizedName("arroz").build();
    }

    @Test
    void rememberFromItem_nullProduct_doesNothing() {
        var item = ReceiptItem.builder().friendlyDescription("Arroz do bom").build();

        service.rememberFromItem(household(), item);

        verifyNoInteractions(repository);
    }

    @Test
    void rememberFromItem_nullFriendlyDescription_doesNothing() {
        var item = ReceiptItem.builder().product(product()).friendlyDescription(null).build();

        service.rememberFromItem(household(), item);

        verifyNoInteractions(repository);
    }

    @Test
    void rememberFromItem_blankFriendlyDescription_doesNothing() {
        var item = ReceiptItem.builder().product(product()).friendlyDescription("   ").build();

        service.rememberFromItem(household(), item);

        verifyNoInteractions(repository);
    }

    @Test
    void rememberFromItem_validItem_upsertsAlias() {
        var household = household();
        var product = product();
        var item = ReceiptItem.builder().product(product).friendlyDescription("Arroz do bom").build();
        when(repository.findByHouseholdIdAndProductId(household.getId(), product.getId()))
                .thenReturn(Optional.empty());

        service.rememberFromItem(household, item);

        var captor = ArgumentCaptor.forClass(HouseholdProductAlias.class);
        verify(repository).save(captor.capture());
        var saved = captor.getValue();
        assertEquals("Arroz do bom", saved.getFriendlyName());
        assertEquals(product.getId(), saved.getProduct().getId());
        assertEquals(household.getId(), saved.getHousehold().getId());
    }

    @Test
    void upsert_createsWhenAbsent() {
        var household = household();
        var product = product();
        when(repository.findByHouseholdIdAndProductId(household.getId(), product.getId()))
                .thenReturn(Optional.empty());

        service.upsert(household, product, "Feijao preto");

        var captor = ArgumentCaptor.forClass(HouseholdProductAlias.class);
        verify(repository).save(captor.capture());
        assertEquals("Feijao preto", captor.getValue().getFriendlyName());
    }

    @Test
    void upsert_updatesExisting() {
        var household = household();
        var product = product();
        var existing = HouseholdProductAlias.builder()
                .household(household).product(product).friendlyName("old name").build();
        when(repository.findByHouseholdIdAndProductId(household.getId(), product.getId()))
                .thenReturn(Optional.of(existing));

        service.upsert(household, product, "new name");

        verify(repository).save(existing);
        assertEquals("new name", existing.getFriendlyName());
    }

    @Test
    void findFor_nullProduct_returnsNull() {
        var result = service.findFor(household(), null);

        assertNull(result);
        verify(repository, never()).findByHouseholdIdAndProductId(any(), any());
    }

    @Test
    void findFor_presentAlias_returnsFriendlyName() {
        var household = household();
        var product = product();
        var alias = HouseholdProductAlias.builder()
                .household(household).product(product).friendlyName("Cafe forte").build();
        when(repository.findByHouseholdIdAndProductId(household.getId(), product.getId()))
                .thenReturn(Optional.of(alias));

        assertSame("Cafe forte", service.findFor(household, product));
    }

    @Test
    void findFor_absentAlias_returnsNull() {
        var household = household();
        var product = product();
        when(repository.findByHouseholdIdAndProductId(household.getId(), product.getId()))
                .thenReturn(Optional.empty());

        assertNull(service.findFor(household, product));
    }
}
