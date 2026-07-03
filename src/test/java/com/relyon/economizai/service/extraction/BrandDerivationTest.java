package com.relyon.economizai.service.extraction;

import com.relyon.economizai.model.BrandRegistryEntry;
import com.relyon.economizai.repository.BrandRegistryEntryRepository;
import com.relyon.economizai.repository.CategorizationBenchmarkEntryRepository;
import com.relyon.economizai.repository.CuratedDictionaryEntryRepository;
import com.relyon.economizai.repository.EanCatalogRepository;
import com.relyon.economizai.repository.EanCatalogRepository.BrandOccurrence;
import com.relyon.economizai.repository.LearnedDictionaryRepository;
import com.relyon.economizai.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrandDerivationTest {

    @Mock private LearnedDictionaryRepository learnedRepository;
    @Mock private ProductRepository productRepository;
    @Mock private DictionaryClassifier dictionaryClassifier;
    @Mock private BrandExtractor brandExtractor;
    @Mock private CuratedDictionaryEntryRepository curatedRepository;
    @Mock private BrandRegistryEntryRepository brandRepository;
    @Mock private CategorizationBenchmarkEntryRepository benchmarkRepository;
    @Mock private EanCatalogRepository eanCatalogRepository;

    @InjectMocks private CategorizerAdminService categorizerAdminService;

    private BrandOccurrence occurrence(String brand, long count) {
        return new BrandOccurrence() {
            @Override public String getBrand() { return brand; }
            @Override public long getOccurrences() { return count; }
        };
    }

    @Test
    void derive_groupsVariantsByNormalizedKeyAndPicksMostFrequentDisplay() {
        when(eanCatalogRepository.countByBrand()).thenReturn(List.of(
                occurrence("Nestlé", 40),
                occurrence("NESTLE", 3),
                occurrence("nestle", 2)));
        when(brandRepository.findByNormalizedKey("nestle")).thenReturn(Optional.empty());

        var outcome = categorizerAdminService.deriveBrandsFromEanCatalog(2);

        assertEquals(1, outcome.created());
        var entryCaptor = ArgumentCaptor.forClass(BrandRegistryEntry.class);
        verify(brandRepository).save(entryCaptor.capture());
        assertEquals("nestle", entryCaptor.getValue().getNormalizedKey());
        assertEquals("Nestlé", entryCaptor.getValue().getDisplayName(), "most frequent variant wins");
        verify(brandExtractor).reload();
    }

    @Test
    void derive_dropsBrandsBelowThreshold() {
        when(eanCatalogRepository.countByBrand()).thenReturn(List.of(
                occurrence("Marca Obscura", 1)));

        var outcome = categorizerAdminService.deriveBrandsFromEanCatalog(2);

        assertEquals(0, outcome.created());
        assertEquals(1, outcome.belowThreshold());
        verify(brandRepository, never()).save(any());
        verify(brandExtractor, never()).reload();
    }

    @Test
    void derive_neverOverwritesExistingRegistryEntries() {
        when(eanCatalogRepository.countByBrand()).thenReturn(List.of(
                occurrence("Tio João", 25)));
        when(brandRepository.findByNormalizedKey("tio joao")).thenReturn(Optional.of(
                BrandRegistryEntry.builder().normalizedKey("tio joao").displayName("Tio João").build()));

        var outcome = categorizerAdminService.deriveBrandsFromEanCatalog(2);

        assertEquals(0, outcome.created());
        assertEquals(1, outcome.skippedExisting());
        verify(brandRepository, never()).save(any());
    }

    @Test
    void derive_skipsJunkKeys() {
        when(eanCatalogRepository.countByBrand()).thenReturn(List.of(
                occurrence("7", 50),            // numeric-only
                occurrence("x", 50)));          // too short after normalization

        var outcome = categorizerAdminService.deriveBrandsFromEanCatalog(1);

        assertEquals(0, outcome.created());
        verify(brandRepository, never()).save(any());
    }
}
