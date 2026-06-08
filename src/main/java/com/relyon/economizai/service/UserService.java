package com.relyon.economizai.service;

import com.relyon.economizai.dto.request.ChangePasswordRequest;
import com.relyon.economizai.dto.request.LoginRequest;
import com.relyon.economizai.dto.request.RegisterRequest;
import com.relyon.economizai.dto.request.UpdateContributionRequest;
import com.relyon.economizai.dto.request.UpdateHomeLocationRequest;
import com.relyon.economizai.dto.request.UpdateUserRequest;
import com.relyon.economizai.dto.response.AuthResponse;
import com.relyon.economizai.dto.response.HouseholdResponse;
import com.relyon.economizai.dto.response.NotificationResponse;
import com.relyon.economizai.dto.response.NotificationRuleResponse;
import com.relyon.economizai.dto.response.ReceiptResponse;
import com.relyon.economizai.dto.response.UserDataExportResponse;
import com.relyon.economizai.dto.response.UserDataExportResponse.AccountExtras;
import com.relyon.economizai.dto.response.UserDataExportResponse.CategoryOverride;
import com.relyon.economizai.dto.response.UserDataExportResponse.CustomCategory;
import com.relyon.economizai.dto.response.UserDataExportResponse.ManualPurchaseSummary;
import com.relyon.economizai.dto.response.UserDataExportResponse.MarketAlias;
import com.relyon.economizai.dto.response.UserDataExportResponse.ShoppingListSummary;
import com.relyon.economizai.dto.response.UserDataExportResponse.SubscriptionSummary;
import com.relyon.economizai.dto.response.UserResponse;
import com.relyon.economizai.exception.EmailAlreadyExistsException;
import com.relyon.economizai.exception.InvalidCredentialsException;
import com.relyon.economizai.exception.InvalidCurrentPasswordException;
import com.relyon.economizai.exception.InvalidLegalVersionException;
import com.relyon.economizai.legal.LegalDocuments;
import com.relyon.economizai.model.User;
import com.relyon.economizai.repository.HouseholdCustomCategoryRepository;
import com.relyon.economizai.repository.HouseholdMarketAliasRepository;
import com.relyon.economizai.repository.HouseholdProductCategoryOverrideRepository;
import com.relyon.economizai.repository.HouseholdRepository;
import com.relyon.economizai.repository.ManualPurchaseRepository;
import com.relyon.economizai.repository.NotificationRepository;
import com.relyon.economizai.repository.NotificationRuleRepository;
import com.relyon.economizai.repository.ReceiptRepository;
import com.relyon.economizai.repository.ShoppingListRepository;
import com.relyon.economizai.repository.SubscriptionRepository;
import com.relyon.economizai.repository.UserRepository;
import com.relyon.economizai.repository.UserWatchedMarketRepository;
import com.relyon.economizai.model.UserWatchedMarket;
import com.relyon.economizai.security.JwtService;
import com.relyon.economizai.service.auth.EmailVerificationService;
import com.relyon.economizai.service.auth.RefreshTokenService;
import com.relyon.economizai.service.notifications.NotificationRuleService;
import com.relyon.economizai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
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
    private final NotificationRuleRepository notificationRuleRepository;
    private final UserWatchedMarketRepository userWatchedMarketRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final HouseholdMarketAliasRepository householdMarketAliasRepository;
    private final HouseholdCustomCategoryRepository householdCustomCategoryRepository;
    private final HouseholdProductCategoryOverrideRepository householdProductCategoryOverrideRepository;
    private final ManualPurchaseRepository manualPurchaseRepository;
    private final ShoppingListRepository shoppingListRepository;
    private final NotificationRepository notificationRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final HouseholdService householdService;
    private final EmailVerificationService emailVerificationService;
    private final RefreshTokenService refreshTokenService;
    private final NotificationRuleService notificationRuleService;

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
        notificationRuleService.ensureDefaults(savedUser);
        emailVerificationService.sendVerificationFor(savedUser);
        var token = jwtService.generateToken(savedUser);
        var refreshToken = refreshTokenService.issue(savedUser);
        log.info("New user registered: {} (household {}, terms v{}, privacy v{})",
                LogMasker.email(savedUser.getEmail()), household.getId(),
                savedUser.getAcceptedTermsVersion(), savedUser.getAcceptedPrivacyVersion());
        return new AuthResponse(token, refreshToken, UserResponse.from(savedUser));
    }

    public AuthResponse login(LoginRequest request) {
        var user = userRepository.findByEmail(request.email())
                .filter(u -> passwordEncoder.matches(request.password(), u.getPassword()))
                .orElseThrow(InvalidCredentialsException::new);

        var token = jwtService.generateToken(user);
        var refreshToken = refreshTokenService.issue(user);
        log.info("User logged in: {}", LogMasker.email(user.getEmail()));
        return new AuthResponse(token, refreshToken, UserResponse.from(user));
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
        var userId = user.getId();
        var householdId = user.getHousehold().getId();
        var household = householdRepository.findById(householdId)
                .orElseThrow(() -> new IllegalStateException("Household missing for user " + LogMasker.email(user.getEmail())));
        var members = userRepository.findAllByHouseholdId(householdId);
        var receipts = receiptRepository
                .findAll((root, query, cb) -> cb.equal(root.get("user").get("id"), userId)).stream()
                .map(ReceiptResponse::from)
                .toList();

        var accountExtras = new AccountExtras(
                user.getPushDeviceToken(),
                user.isEmailVerified(),
                user.getEmailVerifiedAt(),
                user.isContributionOptIn());

        var notificationRules = notificationRuleRepository.findAllByUserId(userId).stream()
                .map(NotificationRuleResponse::from)
                .toList();

        var watchedMarketCnpjs = userWatchedMarketRepository.findAllByUserId(userId).stream()
                .map(UserWatchedMarket::getMarketCnpj)
                .toList();

        var subscription = subscriptionRepository.findByUserId(userId)
                .map(record -> new SubscriptionSummary(
                        record.getProvider(), record.getStatus(), record.getCurrentPeriodEnd()))
                .orElse(null);

        var marketAliases = householdMarketAliasRepository.findAllByHouseholdId(householdId).stream()
                .map(alias -> new MarketAlias(alias.getMarketCnpj(), alias.getCustomName()))
                .toList();

        var customCategories = householdCustomCategoryRepository.findByHouseholdIdOrderByName(householdId).stream()
                .map(category -> new CustomCategory(category.getId(), category.getName()))
                .toList();

        var categoryOverrides = householdProductCategoryOverrideRepository.findAllByHouseholdId(householdId).stream()
                .map(override -> new CategoryOverride(override.getProduct().getId(), override.effectiveLabel()))
                .toList();

        var manualPurchases = manualPurchaseRepository.findAllByHouseholdId(householdId).stream()
                .map(purchase -> new ManualPurchaseSummary(
                        purchase.getProduct().getId(), purchase.getQuantity(), purchase.getPurchasedAt()))
                .toList();

        var shoppingLists = shoppingListRepository.findAllByHouseholdIdOrderByCreatedAtDesc(householdId).stream()
                .map(list -> new ShoppingListSummary(list.getId(), list.getName()))
                .toList();

        var notifications = notificationRepository
                .findAllByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 500))
                .getContent().stream()
                .map(NotificationResponse::from)
                .toList();

        log.info("Data export for user {}: {} receipts, {} rules, {} watched, {} aliases, {} overrides, {} manual, {} lists, {} notifications",
                LogMasker.email(user.getEmail()), receipts.size(), notificationRules.size(),
                watchedMarketCnpjs.size(), marketAliases.size(), categoryOverrides.size(),
                manualPurchases.size(), shoppingLists.size(), notifications.size());

        return new UserDataExportResponse(
                UserResponse.from(user),
                accountExtras,
                HouseholdResponse.from(household, members),
                receipts,
                notificationRules,
                watchedMarketCnpjs,
                subscription,
                marketAliases,
                customCategories,
                categoryOverrides,
                manualPurchases,
                shoppingLists,
                notifications,
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
