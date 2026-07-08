package com.relyon.economizai.service.extraction;

import com.relyon.economizai.model.EanCatalogEntry;
import com.relyon.economizai.service.extraction.EanCatalogService.BulkImportOutcome;
import com.relyon.economizai.service.extraction.EanCatalogService.OpenFoodFactsRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EanCatalogEnrichmentServiceTest {

    private final OpenFoodFactsApiClient apiClient = mock(OpenFoodFactsApiClient.class);
    private final EanCatalogService catalogService = mock(EanCatalogService.class);
    private final EanCatalogEnrichmentService service =
            new EanCatalogEnrichmentService(apiClient, catalogService);

    @Test
    void enrichMissing_noopWhenDisabled() {
        when(apiClient.isEnabled()).thenReturn(false);

        assertEquals(0, service.enrichMissing(List.of("7891000098950")));

        verify(apiClient, never()).fetch(any());
        verify(catalogService, never()).bulkImportOpenFoodFacts(anyList());
    }

    @Test
    void enrichMissing_skipsEansAlreadyInCatalog() {
        when(apiClient.isEnabled()).thenReturn(true);
        when(catalogService.lookup("111")).thenReturn(Optional.of(mock(EanCatalogEntry.class)));

        assertEquals(0, service.enrichMissing(List.of("111")));

        verify(apiClient, never()).fetch("111");
        verify(catalogService, never()).bulkImportOpenFoodFacts(anyList());
    }

    @Test
    void enrichMissing_fetchesMissingAndCachesThrough() {
        when(apiClient.isEnabled()).thenReturn(true);
        when(catalogService.lookup(any())).thenReturn(Optional.empty());
        var row = new OpenFoodFactsRow("222", "Arroz", "Tio", "en:rice");
        when(apiClient.fetch("222")).thenReturn(Optional.of(row));
        when(apiClient.fetch("333")).thenReturn(Optional.empty()); // unknown barcode
        when(catalogService.bulkImportOpenFoodFacts(anyList())).thenReturn(new BulkImportOutcome(1, 0));

        var written = service.enrichMissing(List.of("222", "333", "222")); // dupe collapsed

        assertEquals(1, written);
        verify(apiClient).fetch("222");
        verify(apiClient).fetch("333");
        verify(catalogService).bulkImportOpenFoodFacts(List.of(row));
    }
}
