package com.relyon.economizai.repository;

import com.relyon.economizai.model.ShoppingList;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ShoppingListRepository extends JpaRepository<ShoppingList, UUID> {

    List<ShoppingList> findAllByHouseholdIdOrderByCreatedAtDesc(UUID householdId);
}
