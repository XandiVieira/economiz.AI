package com.relyon.economizai.service.extraction;

import com.relyon.economizai.dto.response.CategorizationBenchmarkResponse;
import com.relyon.economizai.model.CategorizationQualitySnapshot;
import com.relyon.economizai.model.enums.CategorizationQualityTrigger;
import com.relyon.economizai.repository.CategorizationQualitySnapshotRepository;
import com.relyon.economizai.repository.ProductRepository;
import com.relyon.economizai.service.extraction.ml.MlClassifierService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategorizationQualityServiceTest {

    @Mock private CategorizationBenchmarkService benchmarkService;
    @Mock private ProductRepository productRepository;
    @Mock private MlClassifierService mlClassifier;
    @Mock private CategorizationQualitySnapshotRepository snapshotRepository;

    private CategorizationQualityService service() {
        return new CategorizationQualityService(benchmarkService, productRepository, mlClassifier, snapshotRepository);
    }

    @Test
    void record_persistsAccuracyAndComputesCoverage() {
        var report = new CategorizationBenchmarkResponse(34, 34, 100.0, 0, 0, List.of());
        when(productRepository.count()).thenReturn(200L);
        when(productRepository.countByCategoryNotNull()).thenReturn(150L);
        when(mlClassifier.isReady()).thenReturn(true);
        when(snapshotRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service().record(CategorizationQualityTrigger.BENCHMARK, report);

        var captor = ArgumentCaptor.forClass(CategorizationQualitySnapshot.class);
        verify(snapshotRepository).save(captor.capture());
        var saved = captor.getValue();
        assertEquals(0, new BigDecimal("100.00").compareTo(saved.getAccuracyPct()));
        assertEquals(0, new BigDecimal("75.00").compareTo(saved.getCatalogCoveragePct())); // 150/200
        assertEquals(CategorizationQualityTrigger.BENCHMARK, saved.getTrigger());
        assertEquals(0, new BigDecimal("75.00").compareTo(response.catalogCoveragePct()));
    }

    @Test
    void measureAndRecord_runsBenchmark() {
        when(benchmarkService.run()).thenReturn(new CategorizationBenchmarkResponse(10, 9, 90.0, 1, 0, List.of()));
        when(productRepository.count()).thenReturn(0L);
        when(snapshotRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service().measureAndRecord(CategorizationQualityTrigger.BACKFILL);

        verify(benchmarkService).run();
        verify(snapshotRepository).save(any());
    }

    @Test
    void record_zeroCatalog_isZeroCoverageNotDivByZero() {
        when(productRepository.count()).thenReturn(0L);
        when(snapshotRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service().record(CategorizationQualityTrigger.BENCHMARK,
                new CategorizationBenchmarkResponse(0, 0, 0.0, 0, 0, List.of()));

        assertEquals(0, BigDecimal.ZERO.compareTo(response.catalogCoveragePct()));
    }
}
