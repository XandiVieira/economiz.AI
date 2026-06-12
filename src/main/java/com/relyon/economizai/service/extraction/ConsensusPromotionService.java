package com.relyon.economizai.service.extraction;

import com.relyon.economizai.model.LearnedDictionaryEntry;
import com.relyon.economizai.model.enums.CategorizationSource;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.repository.HouseholdProductCategoryOverrideRepository;
import com.relyon.economizai.repository.LearnedDictionaryRepository;
import com.relyon.economizai.repository.ProductRepository;
import com.relyon.economizai.service.canonicalization.DescriptionNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns user category corrections into deterministic knowledge — the
 * "evidence → truth on consensus" step. Source #2 of the cascade
 * ({@code learned_dictionary}) is fed here, NOT just by ML auto-promotion.
 *
 * <p>A per-household correction (see {@code HouseholdProductCategoryOverride})
 * is only evidence. When at least {@code min-households} distinct households
 * correct the SAME product to the SAME category, that product **graduates**:
 * its global category is set (source USER, so everyone sees it). Tokens that
 * recur across consensus products (≥ {@code min-token-products}, all agreeing)
 * are also promoted into the learned dictionary so similar future products
 * inherit the category. A single household never changes anything globally.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsensusPromotionService {

    private static final int MAX_PHRASE_TOKENS = 3;

    private final HouseholdProductCategoryOverrideRepository overrideRepository;
    private final ProductRepository productRepository;
    private final LearnedDictionaryRepository learnedRepository;
    private final DictionaryClassifier dictionaryClassifier;

    // Self-reference so the scheduled trigger's call to @Transactional promote()
    // goes through the Spring proxy. Defaults to `this` for plain unit tests.
    @Lazy
    @Autowired
    private ConsensusPromotionService self = this;

    @Value("${economizai.categorizer.consensus.min-households:2}")
    private int minHouseholds;

    @Value("${economizai.categorizer.consensus.min-token-products:2}")
    private int minTokenProducts;

    @Scheduled(fixedDelayString = "${economizai.categorizer.consensus.interval-ms:86400000}",
               initialDelayString = "${economizai.categorizer.consensus.interval-ms:86400000}")
    public void scheduledPromote() {
        log.info("consensus_promote.scheduled trigger");
        self.promote();
    }

    @Transactional
    public synchronized ConsensusOutcome promote() {
        // productId -> (category -> distinct households that corrected it to that category)
        var votesByProduct = new HashMap<UUID, Map<ProductCategory, Integer>>();
        for (var override : overrideRepository.findAll()) {
            // Custom-category overrides are household-specific and never graduate
            // to the global enum — only enum corrections count toward consensus.
            if (override.getCategory() == null) continue;
            votesByProduct
                    .computeIfAbsent(override.getProduct().getId(), key -> new HashMap<>())
                    .merge(override.getCategory(), 1, Integer::sum);
        }

        // Resolve consensus first, then load the winning products in one query
        // instead of a findById per product.
        var consensusByProduct = new HashMap<UUID, ProductCategory>();
        votesByProduct.forEach((productId, votes) -> {
            var consensusCategory = categoryWithConsensus(votes);
            if (consensusCategory != null) consensusByProduct.put(productId, consensusCategory);
        });

        var graduatedProducts = 0;
        var tokenVotes = new HashMap<String, Map<ProductCategory, Integer>>();

        for (var product : productRepository.findAllById(consensusByProduct.keySet())) {
            var consensusCategory = consensusByProduct.get(product.getId());

            // Graduate the exact product to global truth (source USER).
            if (product.getCategory() != consensusCategory
                    || product.getCategorizationSource() != CategorizationSource.USER) {
                product.setCategory(consensusCategory);
                product.setCategorizationSource(CategorizationSource.USER);
                productRepository.save(product);
                graduatedProducts++;
                log.info("consensus_promote.product_graduated product={} category={}",
                        product.getId(), consensusCategory);
            }

            // Feed token consensus for generalization to similar future products.
            for (var token : phraseTokens(product.getNormalizedName())) {
                tokenVotes.computeIfAbsent(token, key -> new HashMap<>())
                        .merge(consensusCategory, 1, Integer::sum);
            }
        }

        var learnedTokens = promoteAgreedTokens(tokenVotes);
        var totalLearned = reloadLearnedEntries();
        var outcome = new ConsensusOutcome(graduatedProducts, learnedTokens, totalLearned);
        log.info("consensus_promote.done {}", outcome);
        return outcome;
    }

    /** Category corrected by enough distinct households; null if no clear winner. */
    private ProductCategory categoryWithConsensus(Map<ProductCategory, Integer> householdsByCategory) {
        ProductCategory winner = null;
        var winnerHouseholds = 0;
        var tie = false;
        for (var entry : householdsByCategory.entrySet()) {
            if (entry.getValue() > winnerHouseholds) {
                winner = entry.getKey();
                winnerHouseholds = entry.getValue();
                tie = false;
            } else if (entry.getValue() == winnerHouseholds) {
                tie = true;
            }
        }
        return (!tie && winnerHouseholds >= minHouseholds) ? winner : null;
    }

    /** Learn tokens that recur across consensus products with no disagreement. */
    private int promoteAgreedTokens(Map<String, Map<ProductCategory, Integer>> tokenVotes) {
        var learned = 0;
        for (var entry : tokenVotes.entrySet()) {
            var categories = entry.getValue();
            if (categories.size() != 1) continue; // any disagreement → skip
            var category = categories.keySet().iterator().next();
            if (categories.get(category) < minTokenProducts) continue; // not recurrent enough
            upsertLearnedEntry(entry.getKey(), category);
            learned++;
            log.info("consensus_promote.token_learned token='{}' category={} products={}",
                    entry.getKey(), category, categories.get(category));
        }
        return learned;
    }

    private void upsertLearnedEntry(String token, ProductCategory category) {
        var existing = learnedRepository.findByNormalizedToken(token).orElseGet(() ->
                LearnedDictionaryEntry.builder().normalizedToken(token).build());
        existing.setCategory(category);
        existing.setPromotedAt(LocalDateTime.now());
        learnedRepository.save(existing);
    }

    private int reloadLearnedEntries() {
        var entries = learnedRepository.findAll();
        var map = new LinkedHashMap<String, DictionaryClassifier.DictEntry>();
        for (var entry : entries) {
            map.put(entry.getNormalizedToken(), new DictionaryClassifier.DictEntry(
                    entry.getGenericName(), entry.getCategory(), CategorizationSource.LEARNED_DICTIONARY));
        }
        dictionaryClassifier.replaceLearnedEntries(map);
        return entries.size();
    }

    private List<String> phraseTokens(String text) {
        var normalized = DescriptionNormalizer.normalize(text);
        if (normalized.isBlank()) return List.of();
        var tokens = normalized.split("\\s+");
        var phrases = new ArrayList<String>();
        for (var size = MAX_PHRASE_TOKENS; size >= 1; size--) {
            for (var start = 0; start + size <= tokens.length; start++) {
                phrases.add(String.join(" ", Arrays.copyOfRange(tokens, start, start + size)));
            }
        }
        return phrases;
    }

    public record ConsensusOutcome(int productsGraduated, int tokensLearned, int learnedTotal) {}
}
