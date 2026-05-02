package com.relyon.economizai.service;

import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.HouseholdProductAlias;
import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.ReceiptItem;
import com.relyon.economizai.repository.HouseholdProductAliasRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class HouseholdProductAliasService {

    private final HouseholdProductAliasRepository repository;

    /**
     * If the user named this item AND it's linked to a Product, remember
     * the name household-wide so future receipts of the same product
     * inherit it.
     */
    @Transactional
    public void rememberFromItem(Household household, ReceiptItem item) {
        if (item.getProduct() == null) return;
        if (item.getFriendlyDescription() == null || item.getFriendlyDescription().isBlank()) return;
        upsert(household, item.getProduct(), item.getFriendlyDescription());
    }

    @Transactional
    public void upsert(Household household, Product product, String friendlyName) {
        var existing = repository.findByHouseholdIdAndProductId(household.getId(), product.getId());
        var alias = existing.orElseGet(() -> HouseholdProductAlias.builder()
                .household(household).product(product).build());
        alias.setFriendlyName(friendlyName);
        repository.save(alias);
        log.info("household_product_alias.upsert household={} product={} name='{}'",
                household.getId(), product.getId(), friendlyName);
    }

    /**
     * Pulls the household's existing friendly name for this product, if any.
     * Used by canonicalization to seed friendlyDescription on newly-linked items.
     */
    @Transactional(readOnly = true)
    public String findFor(Household household, Product product) {
        if (product == null) return null;
        return repository.findByHouseholdIdAndProductId(household.getId(), product.getId())
                .map(HouseholdProductAlias::getFriendlyName)
                .orElse(null);
    }
}
