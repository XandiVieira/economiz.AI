package com.relyon.economizai.service;

import com.relyon.economizai.dto.request.JoinHouseholdRequest;
import com.relyon.economizai.dto.response.HouseholdResponse;
import com.relyon.economizai.exception.AlreadyInHouseholdException;
import com.relyon.economizai.exception.InvalidInviteCodeException;
import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.User;
import com.relyon.economizai.repository.HouseholdRepository;
import com.relyon.economizai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class HouseholdService {

    private static final String INVITE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int INVITE_LENGTH = 6;
    private static final int MAX_INVITE_ATTEMPTS = 10;

    private final HouseholdRepository householdRepository;
    private final UserRepository userRepository;
    private final SecureRandom random = new SecureRandom();

    @Transactional
    public Household createSoloHousehold() {
        var household = Household.builder()
                .inviteCode(generateUniqueInviteCode())
                .build();
        var saved = householdRepository.save(household);
        log.info("Household {} created with invite code {}", saved.getId(), saved.getInviteCode());
        return saved;
    }

    public HouseholdResponse getMine(User user) {
        var members = userRepository.findAllByHouseholdId(user.getHousehold().getId());
        return HouseholdResponse.from(user.getHousehold(), members);
    }

    @Transactional
    public HouseholdResponse join(User user, JoinHouseholdRequest request) {
        var code = request.inviteCode().trim().toUpperCase();
        var target = householdRepository.findByInviteCode(code)
                .orElseThrow(() -> new InvalidInviteCodeException(code));

        if (target.getId().equals(user.getHousehold().getId())) {
            throw new AlreadyInHouseholdException();
        }

        var previous = user.getHousehold();
        user.setHousehold(target);
        userRepository.save(user);
        log.info("User {} joined household {} (left {})", user.getEmail(), target.getId(), previous.getId());

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
        log.info("User {} left household {} for new solo household {}", user.getEmail(), previous.getId(), fresh.getId());

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
