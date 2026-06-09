package com.relyon.economizai.repository;

import com.relyon.economizai.model.NotificationRule;
import com.relyon.economizai.model.enums.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * Batched variant of {@link #findActiveDefaultRuleOwnersWhoBought(NotificationType, UUID)}:
     * for every product in {@code productIds}, the active default rules of the given type owned
     * by users whose household bought that product (confirmed, non-excluded). Returns one row per
     * distinct (productId, rule) pair so the write path can group owners by product in a single query.
     */
    @Query("""
        SELECT DISTINCT ri.product.id AS productId, r AS rule
        FROM NotificationRule r, ReceiptItem ri
        JOIN r.user u
        WHERE r.type = :type AND r.active = true AND r.isDefault = true
          AND ri.product.id IN :productIds
          AND ri.receipt.status = 'CONFIRMED'
          AND ri.excluded = false
          AND ri.receipt.household.id = u.household.id
    """)
    List<ProductRuleOwner> findActiveDefaultRuleOwnersWhoBought(@Param("type") NotificationType type,
                                                                @Param("productIds") Collection<UUID> productIds);

    /** Projection for {@link #findActiveDefaultRuleOwnersWhoBought(NotificationType, Collection)}. */
    interface ProductRuleOwner {
        UUID getProductId();
        NotificationRule getRule();
    }

    @Modifying
    @Query(nativeQuery = true, value = """
        INSERT INTO notification_rules (id, user_id, type, is_default, active, created_at, updated_at)
        SELECT gen_random_uuid(), u.id, :type, true, true, now(), now()
        FROM users u
        WHERE NOT EXISTS (
            SELECT 1 FROM notification_rules nr
            WHERE nr.user_id = u.id AND nr.type = :type AND nr.product_id IS NULL
        )
    """)
    int insertMissingDefaultRules(@Param("type") String type);
}
