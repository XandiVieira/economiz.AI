package com.relyon.economizai.service.extraction;

import com.relyon.economizai.model.EanCatalogEntry;
import com.relyon.economizai.model.enums.EanCatalogSource;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.repository.EanCatalogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EanCatalogServiceTest {

    @Mock private EanCatalogRepository eanCatalogRepository;
    @InjectMocks private EanCatalogService service;

    @Test
    void lookup_returnsEntryWhenEanFound() {
        var entry = EanCatalogEntry.builder()
                .ean("7894900010015").category(ProductCategory.BEVERAGES).build();
        when(eanCatalogRepository.findByEan("7894900010015")).thenReturn(Optional.of(entry));

        var result = service.lookup("7894900010015");

        assertTrue(result.isPresent());
        assertEquals(ProductCategory.BEVERAGES, result.get().getCategory());
    }

    @Test
    void lookup_returnsEmptyWhenEanNotFound() {
        when(eanCatalogRepository.findByEan("9999999999999")).thenReturn(Optional.empty());

        var result = service.lookup("9999999999999");

        assertFalse(result.isPresent());
    }

    @Test
    void bulkImport_insertsNewEntries() {
        when(eanCatalogRepository.findByEanIn(anyCollection())).thenReturn(List.of());
        when(eanCatalogRepository.count()).thenReturn(2L);

        var entries = List.of(
                new EanCatalogService.EanImportRequest("7894900010015", "Coca-Cola", "Coca-Cola",
                        ProductCategory.BEVERAGES, EanCatalogSource.OPEN_FOOD_FACTS),
                new EanCatalogService.EanImportRequest("7891000455296", "KitKat", "Nestlé",
                        ProductCategory.GROCERIES, EanCatalogSource.OPEN_FOOD_FACTS)
        );

        var outcome = service.bulkImport(entries);

        assertEquals(2, outcome.imported());
        assertEquals(0, outcome.skipped());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EanCatalogEntry>> captor = ArgumentCaptor.captor();
        verify(eanCatalogRepository).saveAll(captor.capture());
        assertEquals(2, captor.getValue().size());
        assertEquals("7894900010015", captor.getValue().get(0).getEan());
    }

    @Test
    void bulkImport_updatesExistingEntry() {
        var existing = EanCatalogEntry.builder()
                .ean("7894900010015").category(ProductCategory.OTHER).build();
        when(eanCatalogRepository.findByEanIn(anyCollection())).thenReturn(List.of(existing));
        when(eanCatalogRepository.count()).thenReturn(1L);

        service.bulkImport(List.of(
                new EanCatalogService.EanImportRequest("7894900010015", "Coca-Cola", "Coca-Cola",
                        ProductCategory.BEVERAGES, EanCatalogSource.OPEN_FOOD_FACTS)
        ));

        assertEquals(ProductCategory.BEVERAGES, existing.getCategory());
        assertEquals("Coca-Cola", existing.getGenericName());
    }

    @Test
    void bulkImport_skipsEntriesWithNullOrBlankEan() {
        when(eanCatalogRepository.findByEanIn(any())).thenReturn(List.of());
        when(eanCatalogRepository.count()).thenReturn(0L);

        var outcome = service.bulkImport(List.of(
                new EanCatalogService.EanImportRequest(null, "Produto", null, ProductCategory.GROCERIES, null),
                new EanCatalogService.EanImportRequest("  ", "Outro", null, ProductCategory.GROCERIES, null)
        ));

        assertEquals(0, outcome.imported());
        assertEquals(2, outcome.skipped());
    }

    @Test
    void bulkImport_returnsZeroOnEmptyList() {
        var outcome = service.bulkImport(List.of());

        assertEquals(0, outcome.imported());
        assertEquals(0, outcome.skipped());
    }

    @Test
    void parseOpenFoodFactsRow_mapsBeverages() {
        var req = EanCatalogService.parseOpenFoodFactsRow(
                "7894900010015", "Coca-Cola 350ml", "Coca-Cola", "en:beverages, en:sodas");

        assertEquals("7894900010015", req.ean());
        assertEquals("Coca-Cola 350ml", req.genericName());
        assertEquals("Coca-Cola", req.brand());
        assertEquals(ProductCategory.BEVERAGES, req.category());
        assertEquals(EanCatalogSource.OPEN_FOOD_FACTS, req.source());
    }

    @Test
    void parseOpenFoodFactsRow_mapsGroceriesForCereals() {
        var req = EanCatalogService.parseOpenFoodFactsRow(
                "123", "Granola", "Naturovos", "en:cereals-and-potatoes, en:cereals");

        assertEquals(ProductCategory.GROCERIES, req.category());
    }

    @Test
    void parseOpenFoodFactsRow_returnsNullCategoryForUnknownTags() {
        var req = EanCatalogService.parseOpenFoodFactsRow(
                "123", "Produto Desconhecido", null, "xx:unknown-tag");

        assertNull(req.category());
    }

    @Test
    void parseOpenFoodFactsRow_handlesNullCategoryTags() {
        var req = EanCatalogService.parseOpenFoodFactsRow("123", "Nome", "Marca", null);

        assertNull(req.category());
    }

    @Test
    void parseOpenFoodFactsRow_takesFirstBrandFromCommaList() {
        var req = EanCatalogService.parseOpenFoodFactsRow(
                "123", "Produto", "Marca Principal, Marca Secundária", "en:beverages");

        assertEquals("Marca Principal", req.brand());
    }
}
