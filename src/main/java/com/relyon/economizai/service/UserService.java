package com.relyon.economizai.service;

import com.relyon.economizai.dto.request.ChangePasswordRequest;
import com.relyon.economizai.dto.request.LoginRequest;
import com.relyon.economizai.dto.request.RegisterRequest;
import com.relyon.economizai.dto.request.UpdateContributionRequest;
import com.relyon.economizai.dto.request.UpdateHomeLocationRequest;
import com.relyon.economizai.dto.request.UpdateUserRequest;
import com.relyon.economizai.dto.response.AuthResponse;
import com.relyon.economizai.dto.response.HouseholdResponse;
import com.relyon.economizai.dto.response.ReceiptResponse;
import com.relyon.economizai.dto.response.UserDataExportResponse;
import com.relyon.economizai.dto.response.UserResponse;
import com.relyon.economizai.exception.EmailAlreadyExistsException;
import com.relyon.economizai.exception.InvalidCredentialsException;
import com.relyon.economizai.exception.InvalidCurrentPasswordException;
import com.relyon.economizai.exception.InvalidLegalVersionException;
import com.relyon.economizai.legal.LegalDocuments;
import com.relyon.economizai.model.User;
import com.relyon.economizai.repository.HouseholdRepository;
import com.relyon.economizai.repository.ReceiptRepository;
import com.relyon.economizai.repository.UserRepository;
import com.relyon.economizai.security.JwtService;
import com.relyon.economizai.service.auth.EmailVerificationService;
import com.relyon.economizai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final HouseholdRepository householdRepository;
    private final ReceiptRepository receiptRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final HouseholdService householdService;
    private final EmailVerificationService emailVerificationService;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException(request.email());
        }
        if (!LegalDocuments.CURRENT_TERMS_VERSION.equals(request.acceptedTermsVersion())) {
            throw new InvalidLegalVersionException("terms",
                    request.acceptedTermsVersion(), LegalDocuments.CURRENT_TERMS_VERSION);
        }
        if (!LegalDocuments.CURRENT_PRIVACY_VERSION.equals(request.acceptedPrivacyVersion())) {
            throw new InvalidLegalVersionException("privacy-policy",
                    request.acceptedPrivacyVersion(), LegalDocuments.CURRENT_PRIVACY_VERSION);
        }

        var household = householdService.createSoloHousehold();
        var user = User.builder()
                .name(request.name())
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .household(household)
                .acceptedTermsVersion(request.acceptedTermsVersion())
                .acceptedPrivacyVersion(request.acceptedPrivacyVersion())
                .acceptedLegalAt(LocalDateTime.now())
                .build();

        var savedUser = userRepository.save(user);
        emailVerificationService.sendVerificationFor(savedUser);
        var token = jwtService.generateToken(savedUser);
        log.info("New user registered: {} (household {}, terms v{}, privacy v{})",
                LogMasker.email(savedUser.getEmail()), household.getId(),
                savedUser.getAcceptedTermsVersion(), savedUser.getAcceptedPrivacyVersion());
        return new AuthResponse(token, UserResponse.from(savedUser));
    }

    public AuthResponse login(LoginRequest request) {
        var user = userRepository.findByEmail(request.email())
                .filter(u -> passwordEncoder.matches(request.password(), u.getPassword()))
                .orElseThrow(InvalidCredentialsException::new);

        var token = jwtService.generateToken(user);
        log.info("User logged in: {}", LogMasker.email(user.getEmail()));
        return new AuthResponse(token, UserResponse.from(user));
    }

    public UserResponse getProfile(User user) {
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse updateProfile(User user, UpdateUserRequest request) {
        user.setName(request.name());
        var updatedUser = userRepository.save(user);
        log.info("User profile updated: {}", LogMasker.email(updatedUser.getEmail()));
        return UserResponse.from(updatedUser);
    }

    @Transactional
    public void changePassword(User user, ChangePasswordRequest request) {
        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new InvalidCurrentPasswordException();
        }

        user.setPassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        log.info("User {} changed password", LogMasker.email(user.getEmail()));
    }

    @Transactional
    public UserResponse updateContribution(User user, UpdateContributionRequest request) {
        user.setContributionOptIn(request.contributionOptIn());
        var saved = userRepository.save(user);
        log.info("User {} contributionOptIn={}", LogMasker.email(saved.getEmail()), saved.isContributionOptIn());
        return UserResponse.from(saved);
    }

    @Transactional
    public UserResponse updateHomeLocation(User user, UpdateHomeLocationRequest request) {
        user.setHomeLatitude(request.latitude());
        user.setHomeLongitude(request.longitude());
        user.setHomeSetAt(LocalDateTime.now());
        var saved = userRepository.save(user);
        log.info("User {} home location set ({}, {})", LogMasker.email(saved.getEmail()), saved.getHomeLatitude(), saved.getHomeLongitude());
        return UserResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public UserDataExportResponse exportData(User user) {
        var household = householdRepository.findById(user.getHousehold().getId())
                .orElseThrow(() -> new IllegalStateException("Household missing for user " + LogMasker.email(user.getEmail())));
        var members = userRepository.findAllByHouseholdId(household.getId());
        var receipts = receiptRepository
                .findAll((root, query, cb) -> cb.equal(root.get("user").get("id"), user.getId())).stream()
                .map(ReceiptResponse::from)
                .toList();
        log.info("Data export for user {}: {} receipts", LogMasker.email(user.getEmail()), receipts.size());
        return new UserDataExportResponse(
                UserResponse.from(user),
                HouseholdResponse.from(household, members),
                receipts,
                LocalDateTime.now()
        );
    }

    @Transactional
    public void deleteAccount(User user) {
        var householdId = user.getHousehold().getId();
        userRepository.delete(user);
        log.info("User account deleted: {}", LogMasker.email(user.getEmail()));
        if (userRepository.countByHouseholdId(householdId) == 0) {
            householdRepository.deleteById(householdId);
            log.info("Household {} deleted (no members left after user deletion)", householdId);
        }
    }
}
