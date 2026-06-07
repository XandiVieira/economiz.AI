package com.relyon.economizai.controller;

import com.relyon.economizai.dto.request.SubscriptionWebhookRequest;
import com.relyon.economizai.exception.InvalidWebhookSecretException;
import com.relyon.economizai.exception.UserNotFoundException;
import com.relyon.economizai.repository.UserRepository;
import com.relyon.economizai.service.subscription.SubscriptionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider-agnostic billing webhook — the integration seam for a real payment
 * provider (Stripe / Mercado Pago). The provider maps its own webhook event to
 * {@link SubscriptionWebhookRequest} and posts here.
 *
 * <p>Public route (permitAll in SecurityConfig), authenticated instead by a
 * shared secret in the {@code X-Webhook-Secret} header, checked against
 * {@code economizai.billing.webhook-secret}. When the configured secret is
 * empty (dev) the check is skipped.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
@Tag(name = "Webhooks", description = "Provider-agnostic billing webhook (subscription lifecycle)")
public class SubscriptionWebhookController {

    private final SubscriptionService subscriptionService;
    private final UserRepository userRepository;

    @Value("${economizai.billing.webhook-secret:}")
    private String webhookSecret;

    @PostMapping("/subscription")
    public ResponseEntity<Void> subscription(
            @RequestHeader(value = "X-Webhook-Secret", required = false) String providedSecret,
            @Valid @RequestBody SubscriptionWebhookRequest request) {
        verifySecret(providedSecret);
        var user = userRepository.findByEmail(request.userEmail())
                .orElseThrow(() -> new UserNotFoundException(request.userEmail()));
        switch (request.action()) {
            case ACTIVATE -> subscriptionService.activatePro(
                    user, request.provider(), request.providerRef(), request.currentPeriodEnd());
            case CANCEL -> subscriptionService.cancel(user);
        }
        log.info("webhook.subscription action={} user={} provider={}",
                request.action(), request.userEmail(), request.provider());
        return ResponseEntity.ok().build();
    }

    private void verifySecret(String providedSecret) {
        if (webhookSecret == null || webhookSecret.isBlank()) return; // dev: check skipped
        if (!webhookSecret.equals(providedSecret)) {
            log.warn("webhook.secret.mismatch");
            throw new InvalidWebhookSecretException();
        }
    }
}
