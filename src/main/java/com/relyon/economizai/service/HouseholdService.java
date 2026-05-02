package com.relyon.economizai.service;

import com.relyon.economizai.dto.request.JoinHouseholdRequest;
import com.relyon.economizai.dto.response.HouseholdResponse;
import com.relyon.economizai.exception.AlreadyInHouseholdException;
import com.relyon.economizai.exception.InvalidInviteCodeException;
import com.relyon.economizai.exception.NotInHouseholdException;
import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.User;
import com.relyon.economizai.repository.HouseholdRepository;
import com.relyon.economizai.repository.UserRepository;
import com.relyon.economizai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class HouseholdService {

    private static final String INVITE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int INVITE_LENGTH = 6;
    private static final int MAX_INVITE_ATTEMPTS = 10;
    private static final int INVITE_TTL_HOURS = 48;

    private final HouseholdRepository householdRepository;
    private final UserRepository userRepository;
    private final SecureRandom random = new SecureRandom();

    @Transactional
    public Household createSoloHousehold() {
        var household = Household.builder()
                .inviteCode(generateUniqueInviteCode())
                .inviteCodeExpiresAt(LocalDateTime.now().plusHours(INVITE_TTL_HOURS))
                .build();
        var saved = householdRepository.save(household);
        log.info("Household {} created with invite code {} (expires {})",
                saved.getId(), saved.getInviteCode(), saved.getInviteCodeExpiresAt());
        return saved;
    }

    @Transactional
    public HouseholdResponse regenerateInviteCode(User user) {
        var household = householdRepository.findById(user.getHousehold().getId())
                .orElseThrow(() -> new IllegalStateException("Household missing for user " + LogMasker.email(user.getEmail())));
        household.setInviteCode(generateUniqueInviteCode());
        household.setInviteCodeExpiresAt(LocalDateTime.now().plusHours(INVITE_TTL_HOURS));
        var saved = householdRepository.save(household);
        log.info("Household {} invite code rotated (expires {})", saved.getId(), saved.getInviteCodeExpiresAt());
        return HouseholdResponse.from(saved, userRepository.findAllByHouseholdId(saved.getId()));
    }

    @Transactional
    public HouseholdResponse removeMember(User actor, UUID memberId) {
        var household = householdRepository.findById(actor.getHousehold().getId())
                .orElseThrow(() -> new IllegalStateException("Household missing for user " + LogMasker.email(actor.getEmail())));
        if (actor.getId().equals(memberId)) {
            throw new IllegalArgumentException("Use POST /households/me/leave to leave on your own — kick is for others");
        }
        var target = userRepository.findById(memberId).orElseThrow(NotInHouseholdException::new);
        if (!household.getId().equals(target.getHousehold().getId())) {
            throw new NotInHouseholdException();
        }
        var fresh = createSoloHousehold();
        target.setHousehold(fresh);
        userRepository.save(target);
        log.info("User {} kicked user {} from household {} (moved to new solo household {})",
                LogMasker.email(actor.getEmail()), LogMasker.email(target.getEmail()), household.getId(), fresh.getId());
        return HouseholdResponse.from(household, userRepository.findAllByHouseholdId(household.getId()));
    }

    @Transactional(readOnly = true)
    public HouseholdResponse getMine(User user) {
        var household = householdRepository.findById(user.getHousehold().getId())
                .orElseThrow(() -> new IllegalStateException("Household missing for user " + LogMasker.email(user.getEmail())));
        var members = userRepository.findAllByHouseholdId(household.getId());
        return HouseholdResponse.from(household, members);
    }

    @Transactional
    public HouseholdResponse join(User user, JoinHouseholdRequest request) {
        var code = request.inviteCode().trim().toUpperCase();
        var target = householdRepository.findByInviteCode(code)
                .orElseThrow(() -> new InvalidInviteCodeException(code));

        if (target.getInviteCodeExpiresAt() != null
                && target.getInviteCodeExpiresAt().isBefore(LocalDateTime.now())) {
            throw new InvalidInviteCodeException(code);
        }

        if (target.getId().equals(user.getHousehold().getId())) {
            throw new AlreadyInHouseholdException();
        }

        var previous = user.getHousehold();
        user.setHousehold(target);
        userRepository.save(user);
        log.info("User {} joined household {} (left {})", LogMasker.email(user.getEmail()), target.getId(), previous.getId());

        if (userRepository.countByHouseholdId(previous.getId()) == 0) {
            householdRepository.delete(previous);
            log.info("Household {} deleted (no members left)", previous.getId());
        }

        var members = userRepository.findAllByHouseholdId(target.getId());
        return HouseholdResponse.from(target, members);
    }

    @Transactional
    public HouseholdResponse leave(User user) {
        var previous = user.getHousehold();
        var fresh = createSoloHousehold();
        user.setHousehold(fresh);
        userRepository.save(user);
        log.info("User {} left household {} for new solo household {}", LogMasker.email(user.getEmail()), previous.getId(), fresh.getId());

        if (userRepository.countByHouseholdId(previous.getId()) == 0) {
            householdRepository.delete(previous);
            log.info("Household {} deleted (no members left)", previous.getId());
        }

        return HouseholdResponse.from(fresh, userRepository.findAllByHouseholdId(fresh.getId()));
    }

    private String generateUniqueInviteCode() {
        for (var attempt = 0; attempt < MAX_INVITE_ATTEMPTS; attempt++) {
            var code = randomCode();
            if (!householdRepository.existsByInviteCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Could not generate a unique invite code after " + MAX_INVITE_ATTEMPTS + " attempts");
    }

    private String randomCode() {
        var sb = new StringBuilder(INVITE_LENGTH);
        for (var i = 0; i < INVITE_LENGTH; i++) {
            sb.append(INVITE_ALPHABET.charAt(random.nextInt(INVITE_ALPHABET.length())));
        }
        return sb.toString();
    }
}
