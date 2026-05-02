package com.relyon.economizai.service.shopping;

import com.relyon.economizai.config.CollaborativeProperties;
import com.relyon.economizai.dto.request.OptimizeShoppingListRequest;
import com.relyon.economizai.dto.response.ShoppingPlanResponse;
import com.relyon.economizai.dto.response.ShoppingPlanResponse.MarketPlan;
import com.relyon.economizai.dto.response.ShoppingPlanResponse.PlanItem;
import com.relyon.economizai.dto.response.ShoppingPlanResponse.UnpricedItem;
import com.relyon.economizai.exception.ProductNotFoundException;
import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.User;
import com.relyon.economizai.repository.PriceObservationAuditRepository;
import com.relyon.economizai.repository.PriceObservationRepository;
import com.relyon.economizai.repository.ProductRepository;
import com.relyon.economizai.repository.ReceiptItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * PRO-52 — given a shopping list, return per-market grouping that minimizes
 * total cost. Greedy: for each item, pick the cheapest known market. We
 * group resulting items per market for the FE to show as "go to market A
 * for these, market B for those". No travel-time / distance modeling yet —
 * acceptable for V1, real basket-optimization needs to wait until we have
 * meaningful cross-market price coverage.
 *
 * <p>Price source priority (per item):
 * <ol>
 *   <li><b>Local history</b> — household's own most-recent purchase of this
 *       product at any market. Most accurate signal because it's the price
 *       the household actually paid.</li>
 *   <li><b>Community index</b> — median observation per market from the
 *       collaborative panel, k-anon-protected. Used only when local
 *       history doesn't cover this market.</li>
 *   <li><b>Unpriced</b> — neither source has data → moved to the
 *       {@code unpriced} bucket. FE shows a "preço indisponível" badge.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShoppingListOptimizer {

    private final ReceiptItemRepository receiptItemRepository;
    private final PriceObservationRepository observationRepository;
    private final PriceObservationAuditRepository auditRepository;
    private final ProductRepository productRepository;
    private final CollaborativeProperties properties;

    @Transactional(readOnly = true)
    public ShoppingPlanResponse optimize(User user, OptimizeShoppingListRequest request) {
        var householdId = user.getHousehold().getId();
        var perMarket = new HashMap<String, MarketPlanBuilder>();
        var unpriced = new ArrayList<UnpricedItem>();
        var totalCost = BigDecimal.ZERO;

        for (var requested : request.items()) {
            var product = productRepository.findById(requested.productId())
                    .orElseThrow(ProductNotFoundException::new);
            var cheapest = findCheapestMarket(product, householdId);
            if (cheapest == null) {
                unpriced.add(new UnpricedItem(product.getId(), product.getNormalizedName(),
                        requested.quantity(), "no observed price (local or community)"));
                continue;
            }
            var subtotal = cheapest.unitPrice.multiply(requested.quantity()).setScale(2, RoundingMode.HALF_UP);
            var planItem = new PlanItem(product.getId(), product.getNormalizedName(),
                    requested.quantity(), cheapest.unitPrice, subtotal, cheapest.source);
            perMarket.computeIfAbsent(cheapest.cnpj, k -> new MarketPlanBuilder(cheapest.cnpj, cheapest.marketName))
                    .add(planItem);
            totalCost = totalCost.add(subtotal);
        }

        var plans = perMarket.values().stream()
                .map(MarketPlanBuilder::build)
                .sorted(Comparator.comparing(MarketPlan::subtotal).reversed())
                .toList();
        log.info("shopping_list.optimize household={} requested={} markets={} unpriced={} estimated_total={}",
                householdId, request.items().size(), plans.size(), unpriced.size(), totalCost);
        return new ShoppingPlanResponse(plans, totalCost, unpriced);
    }

    private MarketCandidate findCheapestMarket(Product product, UUID householdId) {
        // Local history first — most recent purchase at each market the household has been to.
        var localCandidates = receiptItemRepository
                .findHouseholdHistoryForProduct(product.getId(), householdId).stream()
                .filter(item -> item.getReceipt().getCnpjEmitente() != null && item.getUnitPrice() != null)
                .collect(java.util.stream.Collectors.toMap(
                        item -> item.getReceipt().getCnpjEmitente(),
                        item -> new MarketCandidate(item.getReceipt().getCnpjEmitente(),
                                item.getReceipt().getMarketName(), item.getUnitPrice(),
                                PlanItem.PriceSource.LOCAL_HISTORY),
                        (existing, replacement) -> existing));

        // Community panel as fallback — median per market that local history misses.
        if (properties.getCollaborative().isEnabled()) {
            var since = LocalDateTime.now().minusDays(properties.getCollaborative().getLookbackDays());
            var observations = observationRepository.findRecentByProduct(product.getId(), since);
            var byMarket = observations.stream()
                    .collect(java.util.stream.Collectors.groupingBy(o -> o.getMarketCnpj()));
            for (var entry : byMarket.entrySet()) {
                if (localCandidates.containsKey(entry.getKey())) continue;
                var rows = entry.getValue();
                if (rows.size() < properties.getCollaborative().getMinObservationsPerProductMarket()) continue;
                var distinct = auditRepository.countDistinctHouseholdsForProductMarket(
                        product.getId(), entry.getKey(), since);
                if (distinct < properties.getCollaborative().getMinHouseholdsForPublic()) continue;
                var prices = rows.stream().map(o -> o.getUnitPrice()).sorted().toList();
                var median = prices.get(prices.size() / 2);
                localCandidates.put(entry.getKey(),
                        new MarketCandidate(entry.getKey(), rows.get(0).getMarketName(), median,
                                PlanItem.PriceSource.COMMUNITY_INDEX));
            }
        }

        return localCandidates.values().stream()
                .min(Comparator.comparing(c -> c.unitPrice))
                .orElse(null);
    }

    private record MarketCandidate(String cnpj, String marketName, BigDecimal unitPrice,
                                   PlanItem.PriceSource source) {}

    private static final class MarketPlanBuilder {
        private final String cnpj;
        private final String name;
        private final List<PlanItem> items = new ArrayList<>();
        private BigDecimal subtotal = BigDecimal.ZERO;

        MarketPlanBuilder(String cnpj, String name) {
            this.cnpj = cnpj;
            this.name = name;
        }

        void add(PlanItem item) {
            items.add(item);
            subtotal = subtotal.add(item.estimatedSubtotal());
        }

        MarketPlan build() {
            return new MarketPlan(cnpj, name, subtotal, items.size(), items);
        }
    }
}
