package com.relyon.economizai.repository;

import com.relyon.economizai.model.ReceiptItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ReceiptItemRepository extends JpaRepository<ReceiptItem, UUID> {
}
