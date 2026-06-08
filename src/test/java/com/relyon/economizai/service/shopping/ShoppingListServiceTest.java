package com.relyon.economizai.service.shopping;

import com.relyon.economizai.dto.request.AddShoppingListItemRequest;
import com.relyon.economizai.dto.request.CreateShoppingListRequest;
import com.relyon.economizai.dto.request.UpdateShoppingListRequest;
import com.relyon.economizai.exception.InvalidShoppingListItemException;
import com.relyon.economizai.exception.ProductNotFoundException;
import com.relyon.economizai.exception.ShoppingListNotFoundException;
import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.ShoppingList;
import com.relyon.economizai.model.ShoppingListItem;
import com.relyon.economizai.model.User;
import com.relyon.economizai.repository.ProductRepository;
import com.relyon.economizai.repository.ShoppingListItemRepository;
import com.relyon.economizai.repository.ShoppingListRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShoppingListServiceTest {

    @Mock private ShoppingListRepository listRepository;
    @Mock private ShoppingListItemRepository itemRepository;
    @Mock private ProductRepository productRepository;

    private ShoppingListService service;
    private User user;
    private Household household;

    @BeforeEach
    void setUp() {
        service = new ShoppingListService(listRepository, itemRepository, productRepository);
        household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        user = User.builder().id(UUID.randomUUID()).email("owner@economizai").household(household).build();
    }

    @Test
    void listForHousehold_mapsAllListsToResponses() {
        var listOne = list("Semana", List.of());
        var listTwo = list("Festa", List.of());
        when(listRepository.findAllByHouseholdIdOrderByCreatedAtDesc(household.getId()))
                .thenReturn(List.of(listOne, listTwo));

        var responses = service.listForHousehold(user);

        assertEquals(2, responses.size());
        assertEquals("Semana", responses.get(0).name());
        assertEquals("Festa", responses.get(1).name());
    }

    @Test
    void get_returnsOwnedList() {
        var list = list("Semana", List.of());
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));

        var response = service.get(user, list.getId());

        assertEquals(list.getId(), response.id());
        assertEquals("Semana", response.name());
    }

    @Test
    void get_throwsWhenListMissing() {
        var listId = UUID.randomUUID();
        when(listRepository.findById(listId)).thenReturn(Optional.empty());

        assertThrows(ShoppingListNotFoundException.class, () -> service.get(user, listId));
    }

    @Test
    void get_throwsWhenListBelongsToAnotherHousehold() {
        var otherHousehold = Household.builder().id(UUID.randomUUID()).inviteCode("XYZ999").build();
        var foreignList = ShoppingList.builder()
                .id(UUID.randomUUID())
                .household(otherHousehold)
                .createdBy(user)
                .name("Alheia")
                .build();
        when(listRepository.findById(foreignList.getId())).thenReturn(Optional.of(foreignList));

        assertThrows(ShoppingListNotFoundException.class, () -> service.get(user, foreignList.getId()));
    }

    @Test
    void create_withoutItems_savesAndReturnsEmptyList() {
        when(listRepository.save(any())).thenAnswer(invocation -> {
            var toSave = (ShoppingList) invocation.getArgument(0);
            toSave.setId(UUID.randomUUID());
            return toSave;
        });

        var request = new CreateShoppingListRequest("Mercado", null);
        var response = service.create(user, request);

        assertEquals("Mercado", response.name());
        assertEquals(0, response.totalItems());
        verify(itemRepository, never()).save(any());
    }

    @Test
    void create_withItems_persistsEachItemWithIncreasingPositions() {
        var product = product("Cafe");
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(listRepository.save(any())).thenAnswer(invocation -> {
            var toSave = (ShoppingList) invocation.getArgument(0);
            toSave.setId(UUID.randomUUID());
            return toSave;
        });

        var items = List.of(
                new CreateShoppingListRequest.Item(product.getId(), null, new BigDecimal("2")),
                new CreateShoppingListRequest.Item(null, "papel higienico", null)
        );
        var response = service.create(user, new CreateShoppingListRequest("Compras", items));

        assertEquals(2, response.totalItems());
        verify(itemRepository, times(2)).save(any());
    }

    @Test
    void rename_updatesNameAndSaves() {
        var list = list("Antigo", List.of());
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(listRepository.save(list)).thenReturn(list);

        var response = service.rename(user, list.getId(), new UpdateShoppingListRequest("Novo"));

        assertEquals("Novo", response.name());
        verify(listRepository).save(list);
    }

    @Test
    void delete_removesOwnedList() {
        var list = list("Descartavel", List.of());
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));

        service.delete(user, list.getId());

        verify(listRepository).delete(list);
    }

    @Test
    void addItem_appendsAtNextPosition() {
        var existing = item("pao", 0);
        var list = list("Compras", List.of(existing));
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));

        var request = new AddShoppingListItemRequest(null, "leite", new BigDecimal("3"));
        var response = service.addItem(user, list.getId(), request);

        assertEquals(2, response.totalItems());
        verify(itemRepository).save(any());
        // newly appended item should be at position 1 (max existing 0 + 1)
        var appended = list.getItems().get(1);
        assertEquals(1, appended.getPosition());
        assertEquals("leite", appended.getFreeText());
    }

    @Test
    void addItem_onEmptyListUsesPositionZero() {
        var list = list("Vazia", List.of());
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));

        service.addItem(user, list.getId(), new AddShoppingListItemRequest(null, "ovos", null));

        assertEquals(0, list.getItems().get(0).getPosition());
        // default quantity is ONE when not provided
        assertEquals(0, list.getItems().get(0).getQuantity().compareTo(BigDecimal.ONE));
    }

    @Test
    void addItem_throwsWhenNeitherProductNorFreeText() {
        var list = list("Compras", List.of());
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));

        var request = new AddShoppingListItemRequest(null, "   ", BigDecimal.ONE);
        assertThrows(InvalidShoppingListItemException.class,
                () -> service.addItem(user, list.getId(), request));
    }

    @Test
    void addItem_throwsWhenBothProductAndFreeText() {
        var list = list("Compras", List.of());
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));

        // Exactly-one rule: providing BOTH productId and freeText is invalid.
        var request = new AddShoppingListItemRequest(UUID.randomUUID(), "leite", BigDecimal.ONE);
        assertThrows(InvalidShoppingListItemException.class,
                () -> service.addItem(user, list.getId(), request));
        verify(itemRepository, never()).save(any());
        verify(productRepository, never()).findById(any());
    }

    @Test
    void create_throwsWhenItemHasBothProductAndFreeText() {
        when(listRepository.save(any())).thenAnswer(invocation -> {
            var toSave = (ShoppingList) invocation.getArgument(0);
            toSave.setId(UUID.randomUUID());
            return toSave;
        });

        var items = List.of(new CreateShoppingListRequest.Item(UUID.randomUUID(), "papel", BigDecimal.ONE));
        assertThrows(InvalidShoppingListItemException.class,
                () -> service.create(user, new CreateShoppingListRequest("Compras", items)));
        verify(itemRepository, never()).save(any());
    }

    @Test
    void addItem_throwsWhenProductMissing() {
        var list = list("Compras", List.of());
        var missingProductId = UUID.randomUUID();
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(productRepository.findById(missingProductId)).thenReturn(Optional.empty());

        var request = new AddShoppingListItemRequest(missingProductId, null, BigDecimal.ONE);
        assertThrows(ProductNotFoundException.class,
                () -> service.addItem(user, list.getId(), request));
    }

    @Test
    void toggleItem_checksUncheckedItemAndStampsTime() {
        var target = item("arroz", 0);
        target.setChecked(false);
        var list = list("Compras", List.of(target));
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));

        var response = service.toggleItem(user, list.getId(), target.getId());

        assertTrue(target.isChecked());
        assertNotNull(target.getCheckedAt());
        assertEquals(1, response.checkedItems());
    }

    @Test
    void toggleItem_unchecksCheckedItemAndClearsTime() {
        var target = item("arroz", 0);
        target.setChecked(true);
        target.setCheckedAt(LocalDateTime.now());
        var list = list("Compras", List.of(target));
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));

        service.toggleItem(user, list.getId(), target.getId());

        assertFalse(target.isChecked());
        assertNull(target.getCheckedAt());
    }

    @Test
    void toggleItem_throwsWhenItemNotOnList() {
        var list = list("Compras", List.of(item("arroz", 0)));
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));

        assertThrows(ShoppingListNotFoundException.class,
                () -> service.toggleItem(user, list.getId(), UUID.randomUUID()));
    }

    @Test
    void removeItem_deletesAndDetachesFromList() {
        var keep = item("arroz", 0);
        var drop = item("feijao", 1);
        var list = list("Compras", List.of(keep, drop));
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));

        var response = service.removeItem(user, list.getId(), drop.getId());

        assertEquals(1, response.totalItems());
        assertFalse(list.getItems().contains(drop));
        verify(itemRepository).delete(drop);
    }

    @Test
    void removeItem_throwsWhenItemNotOnList() {
        var list = list("Compras", List.of(item("arroz", 0)));
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));

        assertThrows(ShoppingListNotFoundException.class,
                () -> service.removeItem(user, list.getId(), UUID.randomUUID()));
    }

    private ShoppingList list(String name, List<ShoppingListItem> items) {
        var list = ShoppingList.builder()
                .id(UUID.randomUUID())
                .household(household)
                .createdBy(user)
                .name(name)
                .build();
        items.forEach(list::addItem);
        return list;
    }

    private ShoppingListItem item(String freeText, int position) {
        return ShoppingListItem.builder()
                .id(UUID.randomUUID())
                .freeText(freeText)
                .quantity(BigDecimal.ONE)
                .position(position)
                .build();
    }

    private Product product(String name) {
        return Product.builder().id(UUID.randomUUID()).normalizedName(name).build();
    }
}
