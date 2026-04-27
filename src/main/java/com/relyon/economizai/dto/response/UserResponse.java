package com.relyon.economizai.dto.response;

import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.Role;
import com.relyon.economizai.model.enums.SubscriptionTier;

import java.time.LocalDateTime;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String name,
        String email,
        Role role,
        SubscriptionTier subscriptionTier,
        boolean contributionOptIn,
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
                user.getCreatedAt()
        );
    }
}
