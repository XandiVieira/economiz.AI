package com.relyon.economizai.service.canonicalization;

import com.relyon.economizai.config.MdcContextFilter;
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
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

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
            if (item.isExcluded()) continue;
            MDC.put(MdcContextFilter.ITEM_ID, abbrev(item.getId()));
            try {
                var result = canonicalizeItem(item);
                switch (result) {
                    case MATCHED -> matched++;
                    case CREATED -> created++;
                    case UNMATCHED -> unmatched++;
                }
            } finally {
                MDC.remove(MdcContextFilter.ITEM_ID);
            }
        }
        log.info("canonicalize done matched={} created={} unmatched={}", matched, created, unmatched);
        return new CanonicalizationOutcome(matched, created, unmatched);
    }

    private ItemResult canonicalizeItem(ReceiptItem item) {
        if (item.getProduct() != null) {
            log.info("item.skip already linked product={}", abbrev(item.getProduct().getId()));
            return ItemResult.MATCHED;
        }

        var normalized = DescriptionNormalizer.normalize(item.getRawDescription());

        if (hasEan(item)) {
            var byEan = productRepository.findByEan(item.getEan());
            if (byEan.isPresent()) {
                item.setProduct(byEan.get());
                ensureAlias(byEan.get(), item.getRawDescription(), normalized);
                log.info("item.matched_by_ean ean={} product={}", item.getEan(), abbrev(byEan.get().getId()));
                return ItemResult.MATCHED;
            }
            var extraction = productExtractor.extract(item.getRawDescription());
            var created = productRepository.save(buildEnrichedProduct(item, extraction));
            item.setProduct(created);
            ensureAlias(created, item.getRawDescription(), normalized);
            log.info("item.created_from_ean ean={} product={} description='{}' extracted={}",
                    item.getEan(), abbrev(created.getId()), item.getRawDescription(), extraction);
            return ItemResult.CREATED;
        }

        var byAlias = aliasRepository.findByNormalizedDescription(normalized);
        if (byAlias.isPresent()) {
            item.setProduct(byAlias.get().getProduct());
            log.info("item.matched_by_alias product={} normalized='{}'",
                    abbrev(byAlias.get().getProduct().getId()), normalized);
            return ItemResult.MATCHED;
        }
        log.info("item.unmatched description='{}' (no EAN, no alias) — needs review", item.getRawDescription());
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
                .categorizationSource(extraction.categorizationSource())
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

    private static String abbrev(UUID id) {
        return id == null ? "" : id.toString().substring(0, 8);
    }

    public enum ItemResult { MATCHED, CREATED, UNMATCHED }

    public record CanonicalizationOutcome(int matched, int created, int unmatched) {}
}
