package com.relyon.economizai.service.shopping;

import com.relyon.economizai.dto.request.AddShoppingListItemRequest;
import com.relyon.economizai.dto.request.CreateShoppingListRequest;
import com.relyon.economizai.dto.request.UpdateShoppingListRequest;
import com.relyon.economizai.dto.response.ShoppingListResponse;
import com.relyon.economizai.exception.InvalidShoppingListItemException;
import com.relyon.economizai.exception.ProductNotFoundException;
import com.relyon.economizai.exception.ShoppingListNotFoundException;
import com.relyon.economizai.model.HouseholdProductAlias;
import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.ShoppingList;
import com.relyon.economizai.model.ShoppingListItem;
import com.relyon.economizai.model.User;
import com.relyon.economizai.repository.HouseholdProductAliasRepository;
import com.relyon.economizai.repository.ProductRepository;
import com.relyon.economizai.repository.ShoppingListItemRepository;
import com.relyon.economizai.repository.ShoppingListRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * CRUD for persistent household shopping lists. The existing
 * {@link ShoppingListOptimizer} stays as the stateless one-shot helper
 * for ad-hoc "optimize this list right now" calls; this service backs
 * the FE workflow of build → edit → check off as you shop.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShoppingListService {

    private final ShoppingListRepository listRepository;
    private final ShoppingListItemRepository itemRepository;
    private final ProductRepository productRepository;
    private final HouseholdProductAliasRepository householdProductAliasRepository;

    @Transactional(readOnly = true)
    public List<ShoppingListResponse> listForHousehold(User user) {
        return listRepository.findAllByHouseholdIdOrderByCreatedAtDesc(user.getHousehold().getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    /** Returns the household's single shopping list; throws {@link ShoppingListNotFoundException} when count ≠ 1. */
    @Transactional(readOnly = true)
    public ShoppingListResponse getSole(User user) {
        var lists = listRepository.findAllByHouseholdId(user.getHousehold().getId());
        if (lists.size() != 1) throw new ShoppingListNotFoundException();
        return toResponse(lists.get(0));
    }

    @Transactional(readOnly = true)
    public ShoppingListResponse get(User user, UUID listId) {
        return toResponse(loadOwned(user, listId));
    }

    @Transactional
    public ShoppingListResponse create(User user, CreateShoppingListRequest request) {
        var list = ShoppingList.builder()
                .household(user.getHousehold())
                .createdBy(user)
                .name(request.name())
                .build();
        var saved = listRepository.save(list);
        if (request.items() != null) {
            var pos = 0;
            for (var item : request.items()) {
                var entity = buildItem(item.productId(), item.freeText(), item.quantity(), pos++);
                saved.addItem(entity);
                itemRepository.save(entity);
            }
        }
        log.info("shopping_list.created household={} name='{}' items={}",
                user.getHousehold().getId(), request.name(), saved.getItems().size());
        return toResponse(saved);
    }

    @Transactional
    public ShoppingListResponse rename(User user, UUID listId, UpdateShoppingListRequest request) {
        var list = loadOwned(user, listId);
        list.setName(request.name());
        return toResponse(listRepository.save(list));
    }

    @Transactional
    public void delete(User user, UUID listId) {
        var list = loadOwned(user, listId);
        listRepository.delete(list);
        log.info("shopping_list.deleted id={}", listId);
    }

    @Transactional
    public ShoppingListResponse addItem(User user, UUID listId, AddShoppingListItemRequest request) {
        var list = loadOwned(user, listId);
        var nextPos = list.getItems().stream().mapToInt(ShoppingListItem::getPosition).max().orElse(-1) + 1;
        var item = buildItem(request.productId(), request.freeText(), request.quantity(), nextPos);
        list.addItem(item);
        itemRepository.save(item);
        return toResponse(list);
    }

    @Transactional
    public ShoppingListResponse toggleItem(User user, UUID listId, UUID itemId) {
        var list = loadOwned(user, listId);
        var item = list.getItems().stream()
                .filter(i -> i.getId().equals(itemId))
                .findFirst()
                .orElseThrow(ShoppingListNotFoundException::new);
        item.setChecked(!item.isChecked());
        item.setCheckedAt(item.isChecked() ? LocalDateTime.now() : null);
        itemRepository.save(item);
        return toResponse(list);
    }

    @Transactional
    public ShoppingListResponse removeItem(User user, UUID listId, UUID itemId) {
        var list = loadOwned(user, listId);
        var item = list.getItems().stream()
                .filter(i -> i.getId().equals(itemId))
                .findFirst()
                .orElseThrow(ShoppingListNotFoundException::new);
        list.getItems().remove(item);
        itemRepository.delete(item);
        return toResponse(list);
    }

    private ShoppingListResponse toResponse(ShoppingList list) {
        return ShoppingListResponse.from(list, friendlyNamesByProduct(list));
    }

    /** The household's own renames (household_product_aliases) for this list's linked products, keyed by product id. */
    private Map<UUID, String> friendlyNamesByProduct(ShoppingList list) {
        var productIds = list.getItems().stream()
                .map(ShoppingListItem::getProduct)
                .filter(Objects::nonNull)
                .map(Product::getId)
                .distinct()
                .toList();
        if (productIds.isEmpty()) return Map.of();
        return householdProductAliasRepository
                .findAllByHouseholdIdAndProductIdIn(list.getHousehold().getId(), productIds)
                .stream()
                .collect(Collectors.toMap(alias -> alias.getProduct().getId(), HouseholdProductAlias::getFriendlyName));
    }

    private ShoppingList loadOwned(User user, UUID listId) {
        var list = listRepository.findById(listId).orElseThrow(ShoppingListNotFoundException::new);
        if (!list.getHousehold().getId().equals(user.getHousehold().getId())) {
            throw new ShoppingListNotFoundException();
        }
        return list;
    }

    private ShoppingListItem buildItem(UUID productId, String freeText, BigDecimal quantity, int position) {
        var hasProduct = productId != null;
        var hasFreeText = freeText != null && !freeText.isBlank();
        if (hasProduct == hasFreeText) {
            // Exactly one of productId/freeText is required — reject neither and both.
            throw new InvalidShoppingListItemException();
        }
        var builder = ShoppingListItem.builder()
                .position(position)
                .quantity(quantity != null ? quantity : BigDecimal.ONE)
                .freeText(hasFreeText ? freeText : null);
        if (hasProduct) {
            builder.product(productRepository.findById(productId).orElseThrow(ProductNotFoundException::new));
        }
        return builder.build();
    }
}
