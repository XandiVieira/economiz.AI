package com.relyon.economizai.repository;

import com.relyon.economizai.model.NotificationRule;
import com.relyon.economizai.model.enums.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRuleRepository extends JpaRepository<NotificationRule, UUID> {

    @Query("""
        SELECT r FROM NotificationRule r
        LEFT JOIN FETCH r.product
        WHERE r.user.id = :userId
        ORDER BY r.isDefault DESC, r.createdAt DESC
    """)
    List<NotificationRule> findAllByUserIdFetchProduct(@Param("userId") UUID userId);

    Optional<NotificationRule> findByIdAndUserId(UUID id, UUID userId);

    Optional<NotificationRule> findByUserIdAndTypeAndProductId(UUID userId, NotificationType type, UUID productId);

    Optional<NotificationRule> findByUserIdAndTypeAndProductIsNull(UUID userId, NotificationType type);

    List<NotificationRule> findAllByUserId(UUID userId);

    /** Active product-scoped rules of the given types matching any product — write-path price evaluation. */
    @Query("""
        SELECT r FROM NotificationRule r
        JOIN FETCH r.user u
        JOIN FETCH r.product p
        WHERE r.type IN :types AND r.active = true AND p.id IN :productIds
    """)
    List<NotificationRule> findActiveProductRules(@Param("types") Collection<NotificationType> types,
                                                  @Param("productIds") Collection<UUID> productIds);

    /** Active rules of a given type (scheduled jobs: replenishment, budget, digest). */
    @Query("""
        SELECT r FROM NotificationRule r
        JOIN FETCH r.user u
        LEFT JOIN FETCH r.product p
        WHERE r.type = :type AND r.active = true
    """)
    List<NotificationRule> findActiveByTypeFetchUserAndProduct(@Param("type") NotificationType type);

    /**
     * Active default rules of the given type owned by users whose household has
     * a confirmed, non-excluded purchase of the product — community-default
     * targeting (CHEAPER_MARKET / PROMO_COMMUNITY) on the write path.
     */
    @Query("""
        SELECT r FROM NotificationRule r
        JOIN FETCH r.user u
        WHERE r.type = :type AND r.active = true AND r.isDefault = true
          AND u.household.id IN (
              SELECT ri.receipt.household.id FROM ReceiptItem ri
              WHERE ri.product.id = :productId
                AND ri.receipt.status = 'CONFIRMED'
                AND ri.excluded = false
          )
    """)
    List<NotificationRule> findActiveDefaultRuleOwnersWhoBought(@Param("type") NotificationType type,
                                                                @Param("productId") UUID productId);
}
