package com.relyon.economizai.repository;

import com.relyon.economizai.model.ShoppingListItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ShoppingListItemRepository extends JpaRepository<ShoppingListItem, UUID> {
}
