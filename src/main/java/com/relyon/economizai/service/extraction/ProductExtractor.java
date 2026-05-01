package com.relyon.economizai.service.extraction;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductExtractor {

    private final BrandExtractor brandExtractor;
    private final DictionaryClassifier dictionaryClassifier;

    public ProductExtraction extract(String rawDescription) {
        if (rawDescription == null || rawDescription.isBlank()) {
            return ProductExtraction.EMPTY;
        }
        var packSize = PackSizeExtractor.extract(rawDescription);
        var brand = brandExtractor.find(rawDescription);
        var dictHit = dictionaryClassifier.classify(rawDescription);
        var extraction = new ProductExtraction(
                dictHit.genericName(),
                brand,
                packSize.size(),
                packSize.unit(),
                dictHit.category()
        );
        log.debug("Extracted from '{}': {}", rawDescription, extraction);
        return extraction;
    }
}
