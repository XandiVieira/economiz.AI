package com.relyon.economizai.dto.response;

import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.Role;
import com.relyon.economizai.model.enums.SubscriptionTier;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String name,
        String email,
        Role role,
        SubscriptionTier subscriptionTier,
        boolean contributionOptIn,
        BigDecimal homeLatitude,
        BigDecimal homeLongitude,
        LocalDateTime createdAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getRole(),
                user.getSubscriptionTier(),
                user.isContributionOptIn(),
                user.getHomeLatitude(),
                user.getHomeLongitude(),
                user.getCreatedAt()
        );
    }
}
