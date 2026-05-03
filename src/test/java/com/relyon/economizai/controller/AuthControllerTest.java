package com.relyon.economizai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizai.config.SecurityConfig;
import com.relyon.economizai.dto.request.LoginRequest;
import com.relyon.economizai.dto.request.RegisterRequest;
import com.relyon.economizai.dto.response.AuthResponse;
import com.relyon.economizai.dto.response.UserResponse;
import com.relyon.economizai.exception.EmailAlreadyExistsException;
import com.relyon.economizai.exception.InvalidCredentialsException;
import com.relyon.economizai.model.enums.Role;
import com.relyon.economizai.model.enums.SubscriptionTier;
import com.relyon.economizai.security.JwtService;
import com.relyon.economizai.service.LocalizedMessageService;
import com.relyon.economizai.service.UserService;
import com.relyon.economizai.service.auth.EmailVerificationService;
import com.relyon.economizai.service.auth.PasswordResetService;
import com.relyon.economizai.service.auth.RefreshTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private LocalizedMessageService localizedMessageService;

    @MockitoBean
    private PasswordResetService passwordResetService;

    @MockitoBean
    private EmailVerificationService emailVerificationService;

    @MockitoBean
    private RefreshTokenService refreshTokenService;

    private UserResponse sampleUserResponse() {
        return new UserResponse(
                UUID.randomUUID(),
                "John",
                "john@test.com",
                Role.USER,
                SubscriptionTier.FREE,
                true,
                null,
                null,
                LocalDateTime.now()
        );
    }

    @Test
    void register_shouldReturn201WithToken() throws Exception {
        var request = new RegisterRequest("John", "john@test.com", "password123", "1.0", "1.0");
        var response = new AuthResponse("jwt-token", "refresh-token", sampleUserResponse());
        when(userService.register(any(RegisterRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("jwt-token"))
                .andExpect(jsonPath("$.user.name").value("John"))
                .andExpect(jsonPath("$.user.subscriptionTier").value("FREE"));
    }

    @Test
    void register_shouldReturn409WhenEmailExists() throws Exception {
        var request = new RegisterRequest("John", "john@test.com", "password123", "1.0", "1.0");
        when(userService.register(any(RegisterRequest.class)))
                .thenThrow(new EmailAlreadyExistsException("john@test.com"));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    void register_shouldReturn400ForInvalidInput() throws Exception {
        var request = new RegisterRequest("", "not-an-email", "short", "", "");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_shouldReturn200WithToken() throws Exception {
        var request = new LoginRequest("john@test.com", "password123");
        var response = new AuthResponse("jwt-token", "refresh-token", sampleUserResponse());
        when(userService.login(any(LoginRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt-token"));
    }

    @Test
    void login_shouldReturn401ForInvalidCredentials() throws Exception {
        var request = new LoginRequest("john@test.com", "wrong");
        when(userService.login(any(LoginRequest.class))).thenThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }
}
