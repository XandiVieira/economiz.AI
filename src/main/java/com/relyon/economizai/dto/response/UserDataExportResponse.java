package com.relyon.economizai.dto.response;

import com.relyon.economizai.model.enums.SubscriptionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The full LGPD personal-data export for one authenticated user: their account,
 * household (with members and household-scoped customizations), and every table
 * that holds data attributable to them. Built entirely inside the read-only
 * export transaction so lazy associations are resolved before the entity
 * manager closes.
 */
public record UserDataExportResponse(
        UserResponse user,
        AccountExtras accountExtras,
        HouseholdResponse household,
        List<ReceiptResponse> receipts,
        List<NotificationRuleResponse> notificationRules,
        List<String> watchedMarketCnpjs,
        SubscriptionSummary subscription,
        List<MarketAlias> marketAliases,
        List<CustomCategory> customCategories,
        List<CategoryOverride> categoryOverrides,
        List<ManualPurchaseSummary> manualPurchases,
        List<ShoppingListSummary> shoppingLists,
        List<NotificationResponse> notifications,
        LocalDateTime exportedAt
) {

    /** Account fields not surfaced by {@link UserResponse}. */
    public record AccountExtras(
            String pushDeviceToken,
            boolean emailVerified,
            LocalDateTime emailVerifiedAt,
            boolean contributionOptIn
    ) {}

    public record SubscriptionSummary(
            String provider,
            SubscriptionStatus status,
            LocalDateTime currentPeriodEnd
    ) {}

    public record MarketAlias(String marketCnpj, String customName) {}

    public record CustomCategory(UUID id, String name) {}

    public record CategoryOverride(UUID productId, String effectiveLabel) {}

    public record ManualPurchaseSummary(
            UUID productId,
            BigDecimal quantity,
            LocalDateTime purchasedAt
    ) {}

    public record ShoppingListSummary(UUID id, String name) {}
}
