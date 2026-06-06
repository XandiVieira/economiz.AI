package com.relyon.economizai.service.extraction;

import com.relyon.economizai.dto.response.CategorizationExplanation;
import com.relyon.economizai.dto.response.CategorizationExplanation.DictionaryHit;
import com.relyon.economizai.dto.response.CategorizationExplanation.MlGuess;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.service.extraction.ml.MlClassifierService;
import com.relyon.economizai.service.extraction.ml.MlPrediction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Dry-run categorization for debugging — runs the same cascade as ingestion
 * ({@link ProductExtractor}) over an arbitrary description and exposes both the
 * final decision and each layer's contribution, without persisting anything.
 *
 * <p>Powers {@code GET /categorizer/classify}. Use it to answer "why did
 * 'Milho' come out as PERSONAL_CARE?" — the {@code source} + dictionary/ML
 * breakdown say whether it was a bad dictionary entry or an over-confident ML guess.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategorizationDebugService {

    private final ProductExtractor productExtractor;
    private final DictionaryClassifier dictionaryClassifier;
    private final MlClassifierService mlClassifier;

    public CategorizationExplanation explain(String description) {
        var extraction = productExtractor.extract(description);
        var dict = dictionaryClassifier.classify(description);
        var threshold = mlClassifier.getConfidenceThreshold();
        var mlCat = mlClassifier.predictCategory(description);
        var mlGen = mlClassifier.predictGenericName(description);

        log.info("categorizer.explain input='{}' category={} source={}",
                description, extraction.category(), extraction.categorizationSource());

        return new CategorizationExplanation(
                description,
                extraction.category(),
                extraction.genericName(),
                extraction.brand(),
                extraction.packSize(),
                extraction.packUnit(),
                extraction.categorizationSource(),
                new DictionaryHit(dict.genericName(), dict.category(), dict.source()),
                categoryGuess(mlCat, threshold),
                genericNameGuess(mlGen, threshold),
                mlClassifier.isReady(),
                threshold);
    }

    public List<CategorizationExplanation> explainAll(List<String> descriptions) {
        if (descriptions == null) return List.of();
        return descriptions.stream().filter(Objects::nonNull).map(this::explain).toList();
    }

    private static MlGuess categoryGuess(MlPrediction<ProductCategory> prediction, double threshold) {
        var label = prediction.label() == null ? null : prediction.label().name();
        return new MlGuess(label, label == null ? null : prediction.confidence(), prediction.isConfident(threshold));
    }

    private static MlGuess genericNameGuess(MlPrediction<String> prediction, double threshold) {
        return new MlGuess(prediction.label(), prediction.label() == null ? null : prediction.confidence(),
                prediction.isConfident(threshold));
    }
}
