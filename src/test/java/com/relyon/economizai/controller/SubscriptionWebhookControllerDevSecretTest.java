package com.relyon.economizai.controller;

import com.relyon.economizai.config.SecurityConfig;
import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.User;
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

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Dev posture: empty {@code economizai.billing.webhook-secret} → the header
 * check is skipped and the webhook is processed without any secret.
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
    void emptyConfiguredSecret_skipsCheckAndProcesses() throws Exception {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        var user = User.builder().id(UUID.randomUUID()).email("u@test.com").household(household).build();
        when(userRepository.findByEmail("u@test.com")).thenReturn(Optional.of(user));

        mockMvc.perform(post("/api/v1/webhooks/subscription")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userEmail\":\"u@test.com\",\"action\":\"CANCEL\"}"))
                .andExpect(status().isOk());

        verify(subscriptionService).cancel(user);
    }
}
