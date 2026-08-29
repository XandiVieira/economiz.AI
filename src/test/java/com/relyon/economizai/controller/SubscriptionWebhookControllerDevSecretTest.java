package com.relyon.economizai.controller;

import com.relyon.economizai.config.SecurityConfig;
import com.relyon.economizai.repository.UserRepository;
import com.relyon.economizai.security.JwtService;
import com.relyon.economizai.service.LocalizedMessageService;
import com.relyon.economizai.service.subscription.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Dev posture: blank {@code economizai.billing.webhook-secret} → the webhook is
 * DISABLED (fail-closed). Every call is rejected with 401 until a secret is set;
 * dev grants PRO via the admin set-tier endpoint instead.
 */
@WebMvcTest(SubscriptionWebhookController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "economizai.billing.webhook-secret=")
class SubscriptionWebhookControllerDevSecretTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private SubscriptionService subscriptionService;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    @Test
    void blankConfiguredSecret_failsClosedAndRejects() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/subscription")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userEmail\":\"u@test.com\",\"action\":\"CANCEL\"}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(subscriptionService);
        verifyNoInteractions(userRepository);
    }

    @Test
    void blankConfiguredSecret_rejectsEvenWithProvidedHeader() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/subscription")
                        .header("X-Webhook-Secret", "anything")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userEmail\":\"u@test.com\",\"action\":\"CANCEL\"}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(subscriptionService);
    }
}
