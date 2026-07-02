package com.relyon.economizai.service.extraction;

import com.relyon.economizai.model.LearnedDictionaryEntry;
import com.relyon.economizai.model.enums.CategorizationSource;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.repository.LearnedDictionaryRepository;
import com.relyon.economizai.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Admin-only operations for the categorization pipeline: restore to safe state
 * and bulk-seed the learned dictionary without a redeployment.
 *
 * <p><b>Reset-learned</b> wipes every auto-promoted/consensus-learned token
 * from the in-memory and DB layers, restoring the dictionary to the curated CSV
 * baseline. Safe to run at any time — the curated CSV is the authoritative
 * "factory defaults" and is never touched by this operation.</p>
 *
 * <p><b>Reset-consensus</b> reverts all products that were globally graduated
 * by {@code ConsensusPromotionService} (source = CONSENSUS) back to NONE so
 * they can be re-evaluated by the current pipeline. Intended for use after
 * suspected data poisoning (bad actors gaming the consensus threshold).</p>
 *
 * <p><b>Bulk-import</b> inserts entries directly into the learned dictionary
 * table with a high {@code sampleCount}, making them behave like curated
 * entries without requiring a redeployment. Ideal for seeding the dictionary
 * from external data (Open Food Facts, GS1 Brazil, etc.).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategorizerAdminService {

    private static final EnumSet<CategorizationSource> CONSENSUS_SOURCES =
            EnumSet.of(CategorizationSource.CONSENSUS);

    private final LearnedDictionaryRepository learnedRepository;
    private final ProductRepository productRepository;
    private final DictionaryClassifier dictionaryClassifier;

    @Transactional
    public ResetLearnedOutcome resetLearned() {
        var count = (int) learnedRepository.count();
        learnedRepository.deleteAllInBatch();
        dictionaryClassifier.replaceLearnedEntries(Map.of());
        log.info("categorizer.reset_learned removed={}", count);
        return new ResetLearnedOutcome(count);
    }

    @Transactional
    public ResetConsensusOutcome resetConsensus() {
        var consensusProducts = productRepository.findByCategorizationSourceIn(CONSENSUS_SOURCES);
        if (consensusProducts.isEmpty()) {
            log.info("categorizer.reset_consensus nothing_to_revert");
            return new ResetConsensusOutcome(0);
        }
        for (var product : consensusProducts) {
            product.setCategorizationSource(CategorizationSource.NONE);
            product.setCategory(null);
        }
        productRepository.saveAll(consensusProducts);
        log.info("categorizer.reset_consensus reverted={}", consensusProducts.size());
        return new ResetConsensusOutcome(consensusProducts.size());
    }

    @Transactional
    public BulkImportOutcome bulkImport(List<DictionaryImportRequest> entries) {
        if (entries == null || entries.isEmpty()) return new BulkImportOutcome(0, 0);
        var tokens = entries.stream().map(DictionaryImportRequest::token).toList();
        var existingByToken = learnedRepository.findByNormalizedTokenIn(tokens).stream()
                .collect(Collectors.toMap(
                        LearnedDictionaryEntry::getNormalizedToken,
                        Function.identity()));
        var now = LocalDateTime.now();
        var toSave = new ArrayList<LearnedDictionaryEntry>();
        var skipped = 0;
        for (var req : entries) {
            if (req.token() == null || req.token().isBlank() || req.category() == null) {
                skipped++;
                continue;
            }
            var entry = existingByToken.getOrDefault(req.token(),
                    LearnedDictionaryEntry.builder()
                            .normalizedToken(req.token().trim().toLowerCase())
                            .sampleCount(0)
                            .promotedAt(now)
                            .build());
            entry.setCategory(req.category());
            entry.setGenericName(req.genericName());
            // High sample count signals high confidence — this entry won't be overridden by low-count promotions
            entry.setSampleCount(req.sampleCount() > 0 ? req.sampleCount() : 999);
            entry.setPromotedAt(now);
            toSave.add(entry);
        }
        learnedRepository.saveAll(toSave);

        var learnedTotal = reloadLearnedSnapshot();
        log.info("categorizer.bulk_import imported={} skipped={} learnedTotal={}", toSave.size(), skipped, learnedTotal);
        return new BulkImportOutcome(toSave.size(), skipped);
    }

    /** Rebuilds the in-memory classifier snapshot from the DB immediately; returns the learned-entry total. */
    private int reloadLearnedSnapshot() {
        var allEntries = learnedRepository.findAll();
        var snapshot = new LinkedHashMap<String, DictionaryClassifier.DictEntry>();
        for (var entry : allEntries) {
            snapshot.put(entry.getNormalizedToken(), new DictionaryClassifier.DictEntry(
                    entry.getGenericName(), entry.getCategory(), CategorizationSource.LEARNED_DICTIONARY));
        }
        dictionaryClassifier.replaceLearnedEntries(snapshot);
        return allEntries.size();
    }

    public List<ConsensusProductView> listConsensus() {
        return productRepository.findByCategorizationSourceIn(CONSENSUS_SOURCES).stream()
                .map(product -> new ConsensusProductView(
                        product.getId(), product.getEan(), product.getNormalizedName(),
                        product.getGenericName(), product.getBrand(), product.getCategory()))
                .toList();
    }

    public record DictionaryImportRequest(String token, String genericName, ProductCategory category, int sampleCount) {}

    public record ResetLearnedOutcome(int removedEntries) {}

    public record ResetConsensusOutcome(int revertedProducts) {}

    public record BulkImportOutcome(int imported, int skipped) {}

    public record ConsensusProductView(
            UUID id, String ean, String normalizedName,
            String genericName, String brand, ProductCategory category) {}
}
