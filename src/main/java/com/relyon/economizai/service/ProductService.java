package com.relyon.economizai.service;

import com.relyon.economizai.dto.request.CreateAliasRequest;
import com.relyon.economizai.dto.request.CreateProductRequest;
import com.relyon.economizai.dto.request.UpdateProductRequest;
import com.relyon.economizai.dto.response.ProductResponse;
import com.relyon.economizai.dto.response.UnmatchedItemResponse;
import com.relyon.economizai.exception.EanConflictException;
import com.relyon.economizai.exception.ProductAliasConflictException;
import com.relyon.economizai.exception.ProductNotFoundException;
import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.ProductAlias;
import com.relyon.economizai.model.ReceiptItem;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.CategorizationSource;
import com.relyon.economizai.repository.ProductAliasRepository;
import com.relyon.economizai.repository.ProductRepository;
import com.relyon.economizai.repository.PriceObservationRepository;
import com.relyon.economizai.repository.ReceiptItemRepository;
import com.relyon.economizai.service.canonicalization.DescriptionNormalizer;
import com.relyon.economizai.service.extraction.ProductExtractor;
import com.relyon.economizai.service.geo.DistanceCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private static final double PRODUCT_SEARCH_NEARBY_RADIUS_KM = 20.0;

    private final ProductRepository productRepository;
    private final ProductAliasRepository aliasRepository;
    private final ReceiptItemRepository receiptItemRepository;
    private final PriceObservationRepository priceObservationRepository;
    private final ProductExtractor productExtractor;

    @Transactional(readOnly = true)
    public Page<ProductResponse> search(String query, User user, Pageable pageable) {
        var trimmedQuery = (query == null || query.isBlank()) ? null : query.trim();
        var products = productRepository.searchAll(trimmedQuery);
        if (products.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        var householdId = user.getHousehold().getId();
        var productIds = products.stream().map(Product::getId).toList();
        var ranking = ProductSearchRanking.from(productIds, householdId, user,
                receiptItemRepository, priceObservationRepository);
        var sorted = products.stream()
                .sorted(ranking.comparator())
                .map(product -> ProductResponse.from(product, ranking.wasBought(product.getId())))
                .toList();
        return page(sorted, pageable);
    }

    @Transactional(readOnly = true)
    public ProductResponse get(UUID id) {
        return ProductResponse.from(loadProduct(id));
    }

    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        if (request.ean() != null && !request.ean().isBlank()
                && productRepository.findByEan(request.ean()).isPresent()) {
            throw new EanConflictException(request.ean());
        }
        var extracted = productExtractor.extract(request.normalizedName());
        var category = request.category() != null ? request.category() : extracted.category();
        // When the user didn't pick a category, the source follows the extractor —
        // but only if it actually produced one; otherwise there's no categorization.
        var extractedSource = category != null ? extracted.categorizationSource() : CategorizationSource.NONE;
        var source = request.category() != null ? CategorizationSource.USER : extractedSource;
        var product = productRepository.save(Product.builder()
                .ean(blankToNull(request.ean()))
                .normalizedName(request.normalizedName())
                .genericName(firstNonBlank(request.genericName(), extracted.genericName()))
                .brand(firstNonBlank(request.brand(), extracted.brand()))
                .category(category)
                .unit(blankToNull(request.unit()))
                .packSize(request.packSize() != null ? request.packSize() : extracted.packSize())
                .packUnit(firstNonBlank(request.packUnit(), extracted.packUnit()))
                .categorizationSource(source)
                .build());
        log.info("Product {} created (ean={}, name='{}')", product.getId(), product.getEan(), product.getNormalizedName());
        if (product.getEan() != null) {
            var linked = receiptItemRepository.linkByEan(product, product.getEan());
            log.info("Backfilled {} receipt items by EAN {}", linked, product.getEan());
        }
        return ProductResponse.from(product);
    }

    @Transactional
    public ProductResponse update(UUID id, UpdateProductRequest request) {
        var product = loadProduct(id);
        product.setNormalizedName(request.normalizedName());
        product.setGenericName(blankToNull(request.genericName()));
        product.setBrand(blankToNull(request.brand()));
        product.setCategory(request.category());
        product.setUnit(blankToNull(request.unit()));
        product.setPackSize(request.packSize());
        product.setPackUnit(blankToNull(request.packUnit()));
        // any manual update (PATCH) becomes the highest-trust signal — USER overrides any prior source
        product.setCategorizationSource(request.category() != null
                ? CategorizationSource.USER
                : CategorizationSource.NONE);
        var saved = productRepository.save(product);
        log.info("Product {} updated source=USER", saved.getId());
        return ProductResponse.from(saved);
    }

    @Transactional
    public ProductResponse addAlias(User user, UUID productId, CreateAliasRequest request) {
        var product = loadProduct(productId);
        var normalized = DescriptionNormalizer.normalize(request.rawDescription());
        if (normalized.isBlank() || aliasRepository.existsByNormalizedDescription(normalized)) {
            throw new ProductAliasConflictException(request.rawDescription());
        }
        aliasRepository.save(ProductAlias.builder()
                .product(product)
                .rawDescription(request.rawDescription())
                .normalizedDescription(normalized)
                .build());

        var unmatched = receiptItemRepository.findUnmatchedForHousehold(user.getHousehold().getId());
        var linked = backfillByDescription(unmatched, normalized, product);
        log.info("Alias '{}' → product {}; backfilled {} items in household {}",
                normalized, product.getId(), linked, user.getHousehold().getId());
        return ProductResponse.from(product);
    }

    @Transactional(readOnly = true)
    public List<UnmatchedItemResponse> listUnmatched(User user) {
        return receiptItemRepository.findUnmatchedForHousehold(user.getHousehold().getId()).stream()
                .map(this::toUnmatchedResponse)
                .toList();
    }

    private int backfillByDescription(List<ReceiptItem> candidates, String normalized, Product product) {
        var linked = 0;
        for (var item : candidates) {
            var itemNormalized = DescriptionNormalizer.normalize(item.getRawDescription());
            if (itemNormalized.equals(normalized)) {
                item.setProduct(product);
                receiptItemRepository.save(item);
                linked++;
            }
        }
        return linked;
    }

    private Product loadProduct(UUID id) {
        return productRepository.findById(id).orElseThrow(ProductNotFoundException::new);
    }

    private Page<ProductResponse> page(List<ProductResponse> sorted, Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) {
            return new PageImpl<>(sorted);
        }
        var start = (int) Math.min(pageable.getOffset(), sorted.size());
        var end = Math.min(start + pageable.getPageSize(), sorted.size());
        return new PageImpl<>(sorted.subList(start, end), pageable, sorted.size());
    }

    private UnmatchedItemResponse toUnmatchedResponse(ReceiptItem item) {
        var receipt = item.getReceipt();
        return new UnmatchedItemResponse(
                item.getId(),
                receipt.getId(),
                receipt.getMarketName(),
                receipt.getIssuedAt(),
                item.getRawDescription(),
                item.getEan(),
                item.getUnitPrice(),
                item.getTotalPrice(),
                item.getUnit()
        );
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private static String firstNonBlank(String preferred, String fallback) {
        var preferredValue = blankToNull(preferred);
        return preferredValue != null ? preferredValue : blankToNull(fallback);
    }

    private record ProductSearchRanking(Set<UUID> bought,
                                        Set<UUID> observedAtVisitedMarkets,
                                        Set<UUID> observedNearby,
                                        Set<UUID> observedInHouseholdCities) {

        private static ProductSearchRanking from(List<UUID> productIds, UUID householdId, User user,
                                                 ReceiptItemRepository receiptItemRepository,
                                                 PriceObservationRepository priceObservationRepository) {
            var bought = Set.copyOf(
                    receiptItemRepository.findProductIdsWithHistoryForHousehold(productIds, householdId));
            var visitedMarkets = Set.copyOf(
                    priceObservationRepository.findProductIdsObservedAtVisitedMarkets(productIds, householdId));
            var householdCities = Set.copyOf(
                    priceObservationRepository.findProductIdsObservedInHouseholdCities(productIds, householdId));
            var nearby = nearbyProductIds(productIds, user, priceObservationRepository);
            return new ProductSearchRanking(bought, visitedMarkets, nearby, householdCities);
        }

        private static Set<UUID> nearbyProductIds(List<UUID> productIds, User user,
                                                  PriceObservationRepository priceObservationRepository) {
            if (user.getHomeLatitude() == null || user.getHomeLongitude() == null) {
                return Set.of();
            }
            return priceObservationRepository.findProductMarketCoordinates(productIds).stream()
                    .filter(row -> DistanceCalculator.kmBetween(
                            user.getHomeLatitude(), user.getHomeLongitude(),
                            row.getLatitude(), row.getLongitude()) <= PRODUCT_SEARCH_NEARBY_RADIUS_KM)
                    .map(PriceObservationRepository.ProductMarketCoordinates::getProductId)
                    .collect(java.util.stream.Collectors.toSet());
        }

        private Comparator<Product> comparator() {
            return Comparator
                    .comparingInt((Product product) -> bought.contains(product.getId()) ? 0 : 1)
                    .thenComparingInt(product -> observedAtVisitedMarkets.contains(product.getId()) ? 0 : 1)
                    .thenComparingInt(product -> observedNearby.contains(product.getId()) ? 0 : 1)
                    .thenComparingInt(product -> observedInHouseholdCities.contains(product.getId()) ? 0 : 1)
                    .thenComparing(Product::getNormalizedName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
        }

        private boolean wasBought(UUID productId) {
            return bought.contains(productId);
        }
    }
}
