package com.relyon.economizai.controller;

import com.relyon.economizai.config.SecurityConfig;
import com.relyon.economizai.security.JwtService;
import com.relyon.economizai.service.ContactService;
import com.relyon.economizai.service.LocalizedMessageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContactController.class)
@Import(SecurityConfig.class)
class ContactControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ContactService contactService;
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private LocalizedMessageService localizedMessageService;

    @Test
    void submit_validMessage_accepted_noAuthNeeded() throws Exception {
        var body = """
                {"name":"John Doe","email":"john@test.com","message":"Sugestão: adicionem modo escuro."}
                """;

        mockMvc.perform(post("/api/v1/contact").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());

        verify(contactService).submit(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void submit_blankMessage_rejected() throws Exception {
        var body = """
                {"name":"John","email":"john@test.com","message":""}
                """;

        mockMvc.perform(post("/api/v1/contact").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        verify(contactService, never()).submit(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void submit_invalidEmail_rejected() throws Exception {
        var body = """
                {"name":"John","email":"not-an-email","message":"oi"}
                """;

        mockMvc.perform(post("/api/v1/contact").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }
}
