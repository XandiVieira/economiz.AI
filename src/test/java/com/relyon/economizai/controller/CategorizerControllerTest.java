package com.relyon.economizai.controller;

import com.relyon.economizai.config.SecurityConfig;
import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.User;
import com.relyon.economizai.security.JwtService;
import com.relyon.economizai.service.LocalizedMessageService;
import com.relyon.economizai.service.extraction.AutoPromotionService;
import com.relyon.economizai.service.extraction.ml.MlClassifierService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CategorizerController.class)
@Import(SecurityConfig.class)
class CategorizerControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private MlClassifierService mlClassifier;
    @MockitoBean private AutoPromotionService autoPromotionService;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    private User principal() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        return User.builder().id(UUID.randomUUID()).email("u@e").household(household).build();
    }

    @Test
    void status_returnsClassifierState() throws Exception {
        when(mlClassifier.isReady()).thenReturn(true);
        when(mlClassifier.getLastTrainedAt()).thenReturn(LocalDateTime.now());
        when(mlClassifier.getConfidenceThreshold()).thenReturn(0.75);

        mockMvc.perform(get("/api/v1/categorizer/status")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.confidenceThreshold").value(0.75));
    }

    @Test
    void retrain_returnsOutcome() throws Exception {
        when(mlClassifier.retrain()).thenReturn(
                new MlClassifierService.RetrainOutcome(true, 100, 80, Duration.ofMillis(45)));

        mockMvc.perform(post("/api/v1/categorizer/retrain")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trained").value(true))
                .andExpect(jsonPath("$.categoryExamples").value(100));
    }

    @Test
    void autoPromote_returnsOutcome() throws Exception {
        when(autoPromotionService.promote()).thenReturn(
                new AutoPromotionService.PromotionOutcome(3, 1, 2, 18, 5));

        mockMvc.perform(post("/api/v1/categorizer/auto-promote")
                        .with(SecurityMockMvcRequestPostProcessors.user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.promoted").value(3))
                .andExpect(jsonPath("$.learnedTotal").value(5));
    }
}
