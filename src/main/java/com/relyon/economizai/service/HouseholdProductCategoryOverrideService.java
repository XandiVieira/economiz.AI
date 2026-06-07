package com.relyon.economizai.service;

import com.relyon.economizai.model.HouseholdProductCategoryOverride;
import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.repository.HouseholdProductCategoryOverrideRepository;
import com.relyon.economizai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Household-scoped category corrections — "evidence, not truth". Storing a
 * correction does NOT mutate the global {@link Product}; it overrides only what
 * the correcting household sees, and the row stands as a vote for a later
 * cross-household consensus pass.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HouseholdProductCategoryOverrideService {

    private final HouseholdProductCategoryOverrideRepository overrideRepository;

    /** Upsert the household's category correction for a product. */
    @Transactional
    public void setOverride(User user, Product product, ProductCategory category) {
        var householdId = user.getHousehold().getId();
        var existing = overrideRepository.findByHouseholdIdAndProductId(householdId, product.getId());
        var override = existing.orElseGet(() -> HouseholdProductCategoryOverride.builder()
                .household(user.getHousehold())
                .product(product)
                .build());
        override.setCategory(category);
        overrideRepository.save(override);
        log.info("category.override.set household={} product={} category={}",
                householdId, product.getId(), category);
    }

    /** Map of productId → corrected category for a household, for the given products. */
    @Transactional(readOnly = true)
    public Map<UUID, ProductCategory> overridesByProduct(UUID householdId, List<UUID> productIds) {
        if (productIds.isEmpty()) return Map.of();
        return overrideRepository.findByHouseholdIdAndProductIdIn(householdId, productIds).stream()
                .collect(Collectors.toMap(o -> o.getProduct().getId(), HouseholdProductCategoryOverride::getCategory));
    }
}
