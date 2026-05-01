package com.relyon.economizai.service.extraction.ml;

import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.enums.CategorizationSource;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.repository.ProductRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;

/**
 * Owns the two trained classifiers (category, genericName) and exposes
 * predict() to the rest of the app. Trains on startup and on a periodic
 * schedule. Training data: only Products whose categorizationSource is
 * one of the TRUSTED_SOURCES (DICTIONARY, LEARNED_DICTIONARY, USER) —
 * never trains on its own ML predictions to avoid feedback contamination.
 *
 * Models live in memory only. Cheap to retrain (NB + ~1k examples is
 * sub-second), so persisting them isn't worth the complexity.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MlClassifierService {

    private static final EnumSet<CategorizationSource> TRUSTED_SOURCES = EnumSet.of(
            CategorizationSource.DICTIONARY,
            CategorizationSource.LEARNED_DICTIONARY,
            CategorizationSource.USER
    );
    private static final int MIN_TRAINING_SIZE = 30;

    private final ProductRepository productRepository;

    @Value("${economizai.ml.confidence-threshold:0.75}")
    private double confidenceThreshold;

    private final MultinomialNaiveBayes<ProductCategory> categoryModel = new MultinomialNaiveBayes<>();
    private final MultinomialNaiveBayes<String> genericNameModel = new MultinomialNaiveBayes<>();
    private volatile boolean ready = false;
    private volatile LocalDateTime lastTrainedAt;

    @PostConstruct
    void trainOnStartup() {
        retrain();
    }

    @Scheduled(fixedDelayString = "${economizai.ml.retrain-interval-ms:604800000}",
               initialDelayString = "${economizai.ml.retrain-interval-ms:604800000}")
    public void scheduledRetrain() {
        log.info("ml.retrain.scheduled trigger");
        retrain();
    }

    @Transactional(readOnly = true)
    public synchronized RetrainOutcome retrain() {
        var started = System.nanoTime();
        var trustedProducts = productRepository.findAll().stream()
                .filter(p -> TRUSTED_SOURCES.contains(p.getCategorizationSource()))
                .toList();
        var categoryExamples = trustedProducts.stream()
                .filter(p -> p.getCategory() != null)
                .map(p -> new MultinomialNaiveBayes.Example<>(p.getNormalizedName(), p.getCategory()))
                .toList();
        var genericNameExamples = trustedProducts.stream()
                .filter(p -> p.getGenericName() != null)
                .map(p -> new MultinomialNaiveBayes.Example<>(p.getNormalizedName(), p.getGenericName()))
                .toList();

        if (categoryExamples.size() < MIN_TRAINING_SIZE) {
            log.info("ml.retrain.skipped reason=insufficient-data trustedProducts={} categoryExamples={} minRequired={}",
                    trustedProducts.size(), categoryExamples.size(), MIN_TRAINING_SIZE);
            ready = false;
            return new RetrainOutcome(false, categoryExamples.size(), genericNameExamples.size(), Duration.ZERO);
        }

        categoryModel.train(categoryExamples, CharNGramFeatureExtractor::extract);
        genericNameModel.train(genericNameExamples, CharNGramFeatureExtractor::extract);
        ready = true;
        lastTrainedAt = LocalDateTime.now();
        var elapsed = Duration.ofNanos(System.nanoTime() - started);
        log.info("ml.retrain.done categoryExamples={} categoryLabels={} genericExamples={} genericLabels={} vocab={} elapsedMs={}",
                categoryExamples.size(), categoryModel.labelCount(),
                genericNameExamples.size(), genericNameModel.labelCount(),
                categoryModel.vocabularySize(), elapsed.toMillis());
        return new RetrainOutcome(true, categoryExamples.size(), genericNameExamples.size(), elapsed);
    }

    public MlPrediction<ProductCategory> predictCategory(String text) {
        if (!ready) return MlPrediction.empty();
        return categoryModel.predict(CharNGramFeatureExtractor.extract(text));
    }

    public MlPrediction<String> predictGenericName(String text) {
        if (!ready) return MlPrediction.empty();
        return genericNameModel.predict(CharNGramFeatureExtractor.extract(text));
    }

    public boolean isReady() {
        return ready;
    }

    public LocalDateTime getLastTrainedAt() {
        return lastTrainedAt;
    }

    public double getConfidenceThreshold() {
        return confidenceThreshold;
    }

    public record RetrainOutcome(boolean trained, int categoryExamples, int genericNameExamples, Duration elapsed) {}
}
