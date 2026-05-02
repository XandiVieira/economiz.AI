package com.relyon.economizai.service.shopping;

import com.relyon.economizai.dto.request.AddShoppingListItemRequest;
import com.relyon.economizai.dto.request.CreateShoppingListRequest;
import com.relyon.economizai.dto.request.UpdateShoppingListRequest;
import com.relyon.economizai.dto.response.ShoppingListResponse;
import com.relyon.economizai.exception.InvalidShoppingListItemException;
import com.relyon.economizai.exception.ProductNotFoundException;
import com.relyon.economizai.exception.ShoppingListNotFoundException;
import com.relyon.economizai.model.ShoppingList;
import com.relyon.economizai.model.ShoppingListItem;
import com.relyon.economizai.model.User;
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
import java.util.UUID;

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

    @Transactional(readOnly = true)
    public List<ShoppingListResponse> listForHousehold(User user) {
        return listRepository.findAllByHouseholdIdOrderByCreatedAtDesc(user.getHousehold().getId()).stream()
                .map(ShoppingListResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ShoppingListResponse get(User user, UUID listId) {
        return ShoppingListResponse.from(loadOwned(user, listId));
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
        return ShoppingListResponse.from(saved);
    }

    @Transactional
    public ShoppingListResponse rename(User user, UUID listId, UpdateShoppingListRequest request) {
        var list = loadOwned(user, listId);
        list.setName(request.name());
        return ShoppingListResponse.from(listRepository.save(list));
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
        return ShoppingListResponse.from(list);
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
        return ShoppingListResponse.from(list);
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
        return ShoppingListResponse.from(list);
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
        if (!hasProduct && !hasFreeText) {
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
