package com.relyon.economizai.service;

import com.relyon.economizai.dto.request.ChangePasswordRequest;
import com.relyon.economizai.dto.request.LoginRequest;
import com.relyon.economizai.dto.request.RegisterRequest;
import com.relyon.economizai.dto.request.UpdateContributionRequest;
import com.relyon.economizai.dto.request.UpdateUserRequest;
import com.relyon.economizai.exception.EmailAlreadyExistsException;
import com.relyon.economizai.exception.InvalidCredentialsException;
import com.relyon.economizai.exception.InvalidCurrentPasswordException;
import com.relyon.economizai.exception.InvalidLegalVersionException;
import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.Role;
import com.relyon.economizai.model.enums.SubscriptionTier;
import com.relyon.economizai.repository.HouseholdRepository;
import com.relyon.economizai.repository.ReceiptRepository;
import com.relyon.economizai.repository.UserRepository;
import com.relyon.economizai.security.JwtService;
import com.relyon.economizai.service.auth.EmailVerificationService;
import com.relyon.economizai.service.auth.RefreshTokenService;
import org.mockito.ArgumentMatchers;
import org.springframework.data.jpa.domain.Specification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private HouseholdRepository householdRepository;

    @Mock
    private ReceiptRepository receiptRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private HouseholdService householdService;

    @Mock
    private EmailVerificationService emailVerificationService;

    @Mock
    private RefreshTokenService refreshTokenService;

    @InjectMocks
    private UserService userService;

    private User buildUser() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        var user = User.builder()
                .id(UUID.randomUUID())
                .name("John")
                .email("john@test.com")
                .password("encoded")
                .role(Role.USER)
                .subscriptionTier(SubscriptionTier.FREE)
                .contributionOptIn(true)
                .active(true)
                .household(household)
                .acceptedTermsVersion("1.0")
                .acceptedPrivacyVersion("1.0")
                .acceptedLegalAt(LocalDateTime.now())
                .build();
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        return user;
    }

    @Test
    void register_shouldCreateUserAndReturnToken() {
        var request = new RegisterRequest("John", "john@test.com", "password123", "1.0", "1.0");
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        when(userRepository.existsByEmail("john@test.com")).thenReturn(false);
        when(householdService.createSoloHousehold()).thenReturn(household);
        when(passwordEncoder.encode("password123")).thenReturn("encoded");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            var user = inv.<User>getArgument(0);
            user.setId(UUID.randomUUID());
            user.setCreatedAt(LocalDateTime.now());
            user.setUpdatedAt(LocalDateTime.now());
            return user;
        });
        when(jwtService.generateToken(any(User.class))).thenReturn("jwt-token");

        var response = userService.register(request);

        assertNotNull(response);
        assertEquals("jwt-token", response.token());
        assertEquals("John", response.user().name());
        assertEquals("john@test.com", response.user().email());
        assertEquals(SubscriptionTier.FREE, response.user().subscriptionTier());
        verify(userRepository).save(any(User.class));
    }

    @Test
    void register_shouldThrowWhenEmailExists() {
        var request = new RegisterRequest("John", "john@test.com", "password123", "1.0", "1.0");
        when(userRepository.existsByEmail("john@test.com")).thenReturn(true);

        assertThrows(EmailAlreadyExistsException.class, () -> userService.register(request));
        verify(userRepository, never()).save(any());
    }

    @Test
    void login_shouldReturnTokenForValidCredentials() {
        var request = new LoginRequest("john@test.com", "password123");
        var user = buildUser();
        when(userRepository.findByEmail("john@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "encoded")).thenReturn(true);
        when(jwtService.generateToken(user)).thenReturn("jwt-token");

        var response = userService.login(request);

        assertNotNull(response);
        assertEquals("jwt-token", response.token());
        assertEquals("john@test.com", response.user().email());
    }

    @Test
    void login_shouldThrowForInvalidPassword() {
        var request = new LoginRequest("john@test.com", "wrong");
        var user = buildUser();
        when(userRepository.findByEmail("john@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "encoded")).thenReturn(false);

        assertThrows(InvalidCredentialsException.class, () -> userService.login(request));
    }

    @Test
    void login_shouldThrowForNonExistentEmail() {
        var request = new LoginRequest("noone@test.com", "password");
        when(userRepository.findByEmail("noone@test.com")).thenReturn(Optional.empty());

        assertThrows(InvalidCredentialsException.class, () -> userService.login(request));
    }

    @Test
    void getProfile_shouldReturnUserResponse() {
        var user = buildUser();

        var response = userService.getProfile(user);

        assertEquals(user.getName(), response.name());
        assertEquals(user.getEmail(), response.email());
        assertEquals(SubscriptionTier.FREE, response.subscriptionTier());
    }

    @Test
    void updateProfile_shouldUpdateName() {
        var user = buildUser();
        var request = new UpdateUserRequest("New Name");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = userService.updateProfile(user, request);

        assertEquals("New Name", response.name());
        verify(userRepository).save(user);
    }

    @Test
    void changePassword_shouldUpdatePassword() {
        var user = buildUser();
        var request = new ChangePasswordRequest("currentPass", "newPassword123");
        when(passwordEncoder.matches("currentPass", user.getPassword())).thenReturn(true);
        when(passwordEncoder.encode("newPassword123")).thenReturn("newEncoded");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        userService.changePassword(user, request);

        assertEquals("newEncoded", user.getPassword());
        verify(userRepository).save(user);
    }

    @Test
    void changePassword_shouldThrowForWrongCurrentPassword() {
        var user = buildUser();
        var request = new ChangePasswordRequest("wrongPass", "newPassword123");
        when(passwordEncoder.matches("wrongPass", user.getPassword())).thenReturn(false);

        assertThrows(InvalidCurrentPasswordException.class, () -> userService.changePassword(user, request));
        verify(userRepository, never()).save(any());
    }

    @Test
    void register_shouldRejectStaleTermsVersion() {
        var request = new RegisterRequest("John", "john@test.com", "password123", "0.9", "1.0");
        when(userRepository.existsByEmail("john@test.com")).thenReturn(false);

        assertThrows(InvalidLegalVersionException.class,
                () -> userService.register(request));
        verify(userRepository, never()).save(any());
    }

    @Test
    void register_shouldRejectStalePrivacyVersion() {
        var request = new RegisterRequest("John", "john@test.com", "password123", "1.0", "0.9");
        when(userRepository.existsByEmail("john@test.com")).thenReturn(false);

        assertThrows(InvalidLegalVersionException.class,
                () -> userService.register(request));
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateContribution_togglesOptInFlag() {
        var user = buildUser();
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = userService.updateContribution(user, new UpdateContributionRequest(false));

        assertEquals(false, response.contributionOptIn());
        assertEquals(false, user.isContributionOptIn());
    }

    @Test
    void deleteAccount_removesUserAndEmptyHousehold() {
        var user = buildUser();
        when(userRepository.countByHouseholdId(user.getHousehold().getId())).thenReturn(0L);

        userService.deleteAccount(user);

        verify(userRepository).delete(user);
        verify(householdRepository).deleteById(user.getHousehold().getId());
    }

    @Test
    void deleteAccount_keepsHouseholdWithRemainingMembers() {
        var user = buildUser();
        when(userRepository.countByHouseholdId(user.getHousehold().getId())).thenReturn(2L);

        userService.deleteAccount(user);

        verify(userRepository).delete(user);
        verify(householdRepository, never()).deleteById(any());
    }

    @Test
    void exportData_returnsUserHouseholdAndReceipts() {
        var user = buildUser();
        when(householdRepository.findById(user.getHousehold().getId())).thenReturn(Optional.of(user.getHousehold()));
        when(userRepository.findAllByHouseholdId(user.getHousehold().getId())).thenReturn(List.of(user));
        when(receiptRepository.findAll(ArgumentMatchers.<Specification<Receipt>>any()))
                .thenReturn(List.of());

        var response = userService.exportData(user);

        assertNotNull(response.user());
        assertNotNull(response.household());
        assertEquals(0, response.receipts().size());
        assertNotNull(response.exportedAt());
    }
}
