package com.relyon.economizai.service.canonicalization;

import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.ProductAlias;
import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.ReceiptItem;
import com.relyon.economizai.repository.ProductAliasRepository;
import com.relyon.economizai.repository.ProductRepository;
import com.relyon.economizai.service.extraction.ProductExtraction;
import com.relyon.economizai.service.extraction.ProductExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CanonicalizationService {

    private final ProductRepository productRepository;
    private final ProductAliasRepository aliasRepository;
    private final ProductExtractor productExtractor;

    @Transactional
    public CanonicalizationOutcome canonicalize(Receipt receipt) {
        var matched = 0;
        var created = 0;
        var unmatched = 0;
        for (var item : receipt.getItems()) {
            var result = canonicalizeItem(item);
            switch (result) {
                case MATCHED -> matched++;
                case CREATED -> created++;
                case UNMATCHED -> unmatched++;
            }
        }
        log.info("Receipt {} canonicalized: matched={}, created={}, unmatched={}",
                receipt.getId(), matched, created, unmatched);
        return new CanonicalizationOutcome(matched, created, unmatched);
    }

    private ItemResult canonicalizeItem(ReceiptItem item) {
        if (item.getProduct() != null) {
            return ItemResult.MATCHED;
        }

        var normalized = DescriptionNormalizer.normalize(item.getRawDescription());

        if (hasEan(item)) {
            var byEan = productRepository.findByEan(item.getEan());
            if (byEan.isPresent()) {
                item.setProduct(byEan.get());
                ensureAlias(byEan.get(), item.getRawDescription(), normalized);
                return ItemResult.MATCHED;
            }
            var extraction = productExtractor.extract(item.getRawDescription());
            var created = productRepository.save(buildEnrichedProduct(item, extraction));
            item.setProduct(created);
            ensureAlias(created, item.getRawDescription(), normalized);
            return ItemResult.CREATED;
        }

        var byAlias = aliasRepository.findByNormalizedDescription(normalized);
        if (byAlias.isPresent()) {
            item.setProduct(byAlias.get().getProduct());
            return ItemResult.MATCHED;
        }
        return ItemResult.UNMATCHED;
    }

    private boolean hasEan(ReceiptItem item) {
        return item.getEan() != null && !item.getEan().isBlank();
    }

    private Product buildEnrichedProduct(ReceiptItem item, ProductExtraction extraction) {
        return Product.builder()
                .ean(item.getEan())
                .normalizedName(item.getRawDescription())
                .unit(item.getUnit())
                .genericName(extraction.genericName())
                .brand(extraction.brand())
                .category(extraction.category())
                .packSize(extraction.packSize())
                .packUnit(extraction.packUnit())
                .build();
    }

    private void ensureAlias(Product product, String rawDescription, String normalized) {
        if (normalized.isBlank() || aliasRepository.existsByNormalizedDescription(normalized)) {
            return;
        }
        aliasRepository.save(ProductAlias.builder()
                .product(product)
                .rawDescription(rawDescription)
                .normalizedDescription(normalized)
                .build());
    }

    public enum ItemResult { MATCHED, CREATED, UNMATCHED }

    public record CanonicalizationOutcome(int matched, int created, int unmatched) {}
}
