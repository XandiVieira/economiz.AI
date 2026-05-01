package com.relyon.economizai.repository;

import com.relyon.economizai.model.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Page<Notification> findAllByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}
