package com.relyon.economizai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizai.config.SecurityConfig;
import com.relyon.economizai.dto.request.SubmitReceiptRequest;
import com.relyon.economizai.dto.request.UpdateReceiptItemRequest;
import com.relyon.economizai.dto.response.ConfirmReceiptResponse;
import com.relyon.economizai.dto.response.ReceiptItemResponse;
import com.relyon.economizai.dto.response.ReceiptResponse;
import com.relyon.economizai.dto.response.ReceiptSummaryResponse;
import com.relyon.economizai.exception.ReceiptAlreadyIngestedException;
import com.relyon.economizai.exception.ReceiptNotEditableException;
import com.relyon.economizai.exception.ReceiptNotFoundException;
import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.model.enums.ReceiptStatus;
import com.relyon.economizai.model.enums.UnidadeFederativa;
import com.relyon.economizai.security.JwtService;
import com.relyon.economizai.service.LocalizedMessageService;
import com.relyon.economizai.service.ReceiptService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReceiptController.class)
@Import(SecurityConfig.class)
class ReceiptControllerTest {

    private static final String CHAVE_RS = "43260412345678000190650010000123451123456780";

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private ReceiptService receiptService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private LocalizedMessageService localizedMessageService;

    private User buildUser() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        var user = User.builder()
                .id(UUID.randomUUID())
                .name("John")
                .email("john@test.com")
                .password("encoded")
                .household(household)
                .build();
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        return user;
    }

    private ReceiptResponse sampleReceipt(ReceiptStatus status) {
        return new ReceiptResponse(
                UUID.randomUUID(),
                CHAVE_RS,
                UnidadeFederativa.RS,
                "12345678000190",
                "Mercado X",
                "Rua Y, 123",
                LocalDateTime.now(),
                new BigDecimal("57.80"),
                new BigDecimal("57.80"),
                status,
                status == ReceiptStatus.CONFIRMED ? LocalDateTime.now() : null,
                LocalDateTime.now(),
                List.of(new ReceiptItemResponse(
                        UUID.randomUUID(),
                        1,
                        "ARROZ TIO J 5KG",
                        "7891234567890",
                        new BigDecimal("2"),
                        "UN",
                        new BigDecimal("28.90"),
                        new BigDecimal("57.80"),
                        false
                ))
        );
    }

    @Test
    void submit_returns201WithParsedReceipt() throws Exception {
        var user = buildUser();
        when(receiptService.submit(any(User.class), any(SubmitReceiptRequest.class)))
                .thenReturn(sampleReceipt(ReceiptStatus.PENDING_CONFIRMATION));

        mockMvc.perform(post("/api/v1/receipts")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SubmitReceiptRequest(CHAVE_RS))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_CONFIRMATION"))
                .andExpect(jsonPath("$.items[0].rawDescription").value("ARROZ TIO J 5KG"))
                .andExpect(jsonPath("$.uf").value("RS"));
    }

    @Test
    void submit_returns409WhenChaveAlreadyIngested() throws Exception {
        var user = buildUser();
        when(receiptService.submit(any(User.class), any(SubmitReceiptRequest.class)))
                .thenThrow(new ReceiptAlreadyIngestedException(CHAVE_RS));

        mockMvc.perform(post("/api/v1/receipts")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SubmitReceiptRequest(CHAVE_RS))))
                .andExpect(status().isConflict());
    }

    @Test
    void submit_returns400ForBlankPayload() throws Exception {
        var user = buildUser();
        mockMvc.perform(post("/api/v1/receipts")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SubmitReceiptRequest(""))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submit_requiresAuth() throws Exception {
        mockMvc.perform(post("/api/v1/receipts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SubmitReceiptRequest(CHAVE_RS))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void list_returnsPagedSummaries() throws Exception {
        var user = buildUser();
        var summary = new ReceiptSummaryResponse(UUID.randomUUID(), "Mercado X", LocalDateTime.now(),
                new BigDecimal("57.80"), 1, ReceiptStatus.CONFIRMED);
        Page<ReceiptSummaryResponse> page = new PageImpl<>(List.of(summary));
        when(receiptService.list(any(User.class), isNull(), isNull(), isNull(), isNull(ProductCategory.class), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/receipts")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].marketName").value("Mercado X"))
                .andExpect(jsonPath("$.content[0].itemCount").value(1));
    }

    @Test
    void get_returnsReceipt() throws Exception {
        var user = buildUser();
        var id = UUID.randomUUID();
        when(receiptService.get(any(User.class), eq(id))).thenReturn(sampleReceipt(ReceiptStatus.PENDING_CONFIRMATION));

        mockMvc.perform(get("/api/v1/receipts/" + id)
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chaveAcesso").value(CHAVE_RS));
    }

    @Test
    void get_returns404WhenMissing() throws Exception {
        var user = buildUser();
        var id = UUID.randomUUID();
        when(receiptService.get(any(User.class), eq(id))).thenThrow(new ReceiptNotFoundException());

        mockMvc.perform(get("/api/v1/receipts/" + id)
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isNotFound());
    }

    @Test
    void confirm_returnsConfirmedReceiptWithPromos() throws Exception {
        var user = buildUser();
        var id = UUID.randomUUID();
        var confirmResponse = new ConfirmReceiptResponse(
                sampleReceipt(ReceiptStatus.CONFIRMED), List.of());
        when(receiptService.confirm(any(User.class), eq(id), any())).thenReturn(confirmResponse);

        mockMvc.perform(post("/api/v1/receipts/" + id + "/confirm")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receipt.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.personalPromos").isArray());
    }

    @Test
    void confirm_returns400WhenAlreadyConfirmed() throws Exception {
        var user = buildUser();
        var id = UUID.randomUUID();
        when(receiptService.confirm(any(User.class), eq(id), any()))
                .thenThrow(new ReceiptNotEditableException("CONFIRMED"));

        mockMvc.perform(post("/api/v1/receipts/" + id + "/confirm")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateItem_returnsUpdatedReceipt() throws Exception {
        var user = buildUser();
        var id = UUID.randomUUID();
        var itemId = UUID.randomUUID();
        var request = new UpdateReceiptItemRequest("ARROZ TIO JOAO 5KG", "7891234567890",
                new BigDecimal("2"), "UN", new BigDecimal("28.90"), new BigDecimal("57.80"), null);
        when(receiptService.updateItem(any(User.class), eq(id), eq(itemId), any(UpdateReceiptItemRequest.class)))
                .thenReturn(sampleReceipt(ReceiptStatus.PENDING_CONFIRMATION));

        mockMvc.perform(patch("/api/v1/receipts/" + id + "/items/" + itemId)
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    void reject_returnsRejectedReceipt() throws Exception {
        var user = buildUser();
        var id = UUID.randomUUID();
        when(receiptService.reject(any(User.class), eq(id))).thenReturn(sampleReceipt(ReceiptStatus.REJECTED));

        mockMvc.perform(post("/api/v1/receipts/" + id + "/reject")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }
}
