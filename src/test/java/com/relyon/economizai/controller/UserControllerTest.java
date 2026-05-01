package com.relyon.economizai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizai.config.SecurityConfig;
import com.relyon.economizai.dto.request.ChangePasswordRequest;
import com.relyon.economizai.dto.request.UpdateUserRequest;
import com.relyon.economizai.dto.response.UserResponse;
import com.relyon.economizai.exception.InvalidCurrentPasswordException;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.Role;
import com.relyon.economizai.model.enums.SubscriptionTier;
import com.relyon.economizai.security.JwtService;
import com.relyon.economizai.service.LocalizedMessageService;
import com.relyon.economizai.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
class UserControllerTest {

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

    private User buildUser() {
        var user = User.builder()
                .id(UUID.randomUUID())
                .name("John")
                .email("john@test.com")
                .password("encoded")
                .role(Role.USER)
                .subscriptionTier(SubscriptionTier.FREE)
                .contributionOptIn(true)
                .active(true)
                .build();
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        return user;
    }

    private UserResponse responseFor(User user, String name) {
        return new UserResponse(
                user.getId(),
                name,
                user.getEmail(),
                user.getRole(),
                user.getSubscriptionTier(),
                user.isContributionOptIn(),
                user.getCreatedAt()
        );
    }

    @Test
    void getProfile_shouldReturn200() throws Exception {
        var user = buildUser();
        when(userService.getProfile(any(User.class))).thenReturn(responseFor(user, "John"));

        mockMvc.perform(get("/api/v1/users/me")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("John"))
                .andExpect(jsonPath("$.email").value("john@test.com"))
                .andExpect(jsonPath("$.subscriptionTier").value("FREE"));
    }

    @Test
    void getProfile_shouldReturn401WhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateProfile_shouldReturn200() throws Exception {
        var user = buildUser();
        var request = new UpdateUserRequest("New Name");
        when(userService.updateProfile(any(User.class), any(UpdateUserRequest.class)))
                .thenReturn(responseFor(user, "New Name"));

        mockMvc.perform(put("/api/v1/users/me")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New Name"));
    }

    @Test
    void updateProfile_shouldReturn400ForBlankName() throws Exception {
        var user = buildUser();
        var request = new UpdateUserRequest("");

        mockMvc.perform(put("/api/v1/users/me")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void changePassword_shouldReturn200OnSuccess() throws Exception {
        var user = buildUser();
        var request = new ChangePasswordRequest("currentPass", "newPassword123");
        when(localizedMessageService.translate("user.password.changed"))
                .thenReturn("Password changed successfully.");

        mockMvc.perform(put("/api/v1/users/me/password")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password changed successfully."));
    }

    @Test
    void changePassword_shouldReturn400ForWrongCurrentPassword() throws Exception {
        var user = buildUser();
        var request = new ChangePasswordRequest("wrongPass", "newPassword123");
        doThrow(new InvalidCurrentPasswordException()).when(userService).changePassword(any(User.class), any(ChangePasswordRequest.class));

        mockMvc.perform(put("/api/v1/users/me/password")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateContribution_returns200() throws Exception {
        var user = buildUser();
        var request = new com.relyon.economizai.dto.request.UpdateContributionRequest(false);
        var response = new UserResponse(user.getId(), user.getName(), user.getEmail(),
                user.getRole(), user.getSubscriptionTier(), false, user.getCreatedAt());
        org.mockito.Mockito.when(userService.updateContribution(any(User.class),
                any(com.relyon.economizai.dto.request.UpdateContributionRequest.class)))
                .thenReturn(response);

        mockMvc.perform(patch("/api/v1/users/me/contribution")
                        .with(SecurityMockMvcRequestPostProcessors.user(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contributionOptIn").value(false));
    }

    @Test
    void exportData_returns200WithJson() throws Exception {
        var user = buildUser();
        var ur = new UserResponse(user.getId(), user.getName(), user.getEmail(),
                user.getRole(), user.getSubscriptionTier(), true, user.getCreatedAt());
        var hr = new com.relyon.economizai.dto.response.HouseholdResponse(
                java.util.UUID.randomUUID(), "ABC123",
                java.util.List.of(new com.relyon.economizai.dto.response.HouseholdResponse.HouseholdMember(
                        user.getId(), user.getName(), user.getEmail())),
                LocalDateTime.now());
        var export = new com.relyon.economizai.dto.response.UserDataExportResponse(
                ur, hr, java.util.List.of(), LocalDateTime.now());
        org.mockito.Mockito.when(userService.exportData(any(User.class))).thenReturn(export);

        mockMvc.perform(get("/api/v1/users/me/export")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(user.getEmail()))
                .andExpect(jsonPath("$.household.inviteCode").value("ABC123"))
                .andExpect(jsonPath("$.receipts").isArray());
    }

    @Test
    void deleteAccount_returns200() throws Exception {
        var user = buildUser();
        org.mockito.Mockito.when(localizedMessageService.translate("user.account.deleted"))
                .thenReturn("Conta excluida.");

        mockMvc.perform(delete("/api/v1/users/me")
                        .with(SecurityMockMvcRequestPostProcessors.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Conta excluida."));
    }
}
