package com.relyon.economizai.service.subscription;

import com.relyon.economizai.model.Subscription;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.SubscriptionStatus;
import com.relyon.economizai.model.enums.SubscriptionTier;
import com.relyon.economizai.repository.SubscriptionRepository;
import com.relyon.economizai.repository.UserRepository;
import com.relyon.economizai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Owns the subscription lifecycle and keeps {@link User#getSubscriptionTier()}
 * in sync with the {@link Subscription} record. Called by the admin endpoint
 * (manual promos/ops) and the provider-agnostic billing webhook.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;

    /**
     * Upsert the user's subscription to ACTIVE and set their tier to PRO. Used
     * for both real provider activations and manual grants (provider/providerRef
     * may be null/"manual").
     */
    @Transactional
    public Subscription activatePro(User user, String provider, String providerRef, LocalDateTime periodEnd) {
        var subscription = subscriptionRepository.findByUserId(user.getId())
                .orElseGet(() -> Subscription.builder().user(user).build());
        subscription.setProvider(provider);
        subscription.setProviderRef(providerRef);
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setCurrentPeriodEnd(periodEnd);
        var saved = subscriptionRepository.save(subscription);

        user.setSubscriptionTier(SubscriptionTier.PRO);
        userRepository.save(user);
        log.info("subscription.activated user={} provider={} periodEnd={}",
                LogMasker.email(user.getEmail()), provider, periodEnd);
        return saved;
    }

    /**
     * Mark a lapsed subscription EXPIRED and drop its user to FREE. Used by the
     * expiry sweep and by provider "expiration" webhooks. The subscription is
     * passed in with its user already loaded.
     */
    @Transactional
    public void expire(Subscription subscription) {
        subscription.setStatus(SubscriptionStatus.EXPIRED);
        subscriptionRepository.save(subscription);
        var user = subscription.getUser();
        user.setSubscriptionTier(SubscriptionTier.FREE);
        userRepository.save(user);
        log.info("subscription.expired user={} periodEnd={}",
                LogMasker.email(user.getEmail()), subscription.getCurrentPeriodEnd());
    }

    /**
     * Mark the user's subscription CANCELED and drop their tier to FREE. No-op
     * on the subscription row when none exists (tier still forced to FREE).
     */
    @Transactional
    public void cancel(User user) {
        subscriptionRepository.findByUserId(user.getId()).ifPresent(subscription -> {
            subscription.setStatus(SubscriptionStatus.CANCELED);
            subscriptionRepository.save(subscription);
        });
        user.setSubscriptionTier(SubscriptionTier.FREE);
        userRepository.save(user);
        log.info("subscription.canceled user={}", LogMasker.email(user.getEmail()));
    }
}
