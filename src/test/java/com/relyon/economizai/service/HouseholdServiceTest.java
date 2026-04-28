package com.relyon.economizai.service;

import com.relyon.economizai.dto.request.JoinHouseholdRequest;
import com.relyon.economizai.exception.AlreadyInHouseholdException;
import com.relyon.economizai.exception.InvalidInviteCodeException;
import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.User;
import com.relyon.economizai.repository.HouseholdRepository;
import com.relyon.economizai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HouseholdServiceTest {

    @Mock
    private HouseholdRepository householdRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private HouseholdService householdService;

    private User buildUser(Household household) {
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

    private Household buildHousehold(String code) {
        var h = Household.builder().id(UUID.randomUUID()).inviteCode(code).build();
        h.setCreatedAt(LocalDateTime.now());
        h.setUpdatedAt(LocalDateTime.now());
        return h;
    }

    @Test
    void createSoloHousehold_persistsWithGeneratedInviteCode() {
        when(householdRepository.existsByInviteCode(any())).thenReturn(false);
        when(householdRepository.save(any(Household.class))).thenAnswer(inv -> {
            var h = inv.<Household>getArgument(0);
            h.setId(UUID.randomUUID());
            return h;
        });

        var saved = householdService.createSoloHousehold();

        assertNotNull(saved.getInviteCode());
        assertEquals(6, saved.getInviteCode().length());
    }

    @Test
    void getMine_returnsHouseholdWithMembers() {
        var household = buildHousehold("ABC123");
        var user = buildUser(household);
        when(userRepository.findAllByHouseholdId(household.getId())).thenReturn(List.of(user));

        var response = householdService.getMine(user);

        assertEquals(household.getId(), response.id());
        assertEquals("ABC123", response.inviteCode());
        assertEquals(1, response.members().size());
        assertEquals("john@test.com", response.members().get(0).email());
    }

    @Test
    void join_movesUserToTargetHouseholdAndDeletesEmptyPrevious() {
        var previous = buildHousehold("OLD123");
        var target = buildHousehold("NEW456");
        var user = buildUser(previous);

        when(householdRepository.findByInviteCode("NEW456")).thenReturn(Optional.of(target));
        when(userRepository.countByHouseholdId(previous.getId())).thenReturn(0L);
        when(userRepository.findAllByHouseholdId(target.getId())).thenReturn(List.of(user));

        var response = householdService.join(user, new JoinHouseholdRequest("new456"));

        assertEquals(target.getId(), user.getHousehold().getId());
        assertEquals(target.getId(), response.id());
        verify(householdRepository).delete(previous);
    }

    @Test
    void join_keepsPreviousHouseholdWhenStillHasMembers() {
        var previous = buildHousehold("OLD123");
        var target = buildHousehold("NEW456");
        var user = buildUser(previous);

        when(householdRepository.findByInviteCode("NEW456")).thenReturn(Optional.of(target));
        when(userRepository.countByHouseholdId(previous.getId())).thenReturn(2L);
        when(userRepository.findAllByHouseholdId(target.getId())).thenReturn(List.of(user));

        householdService.join(user, new JoinHouseholdRequest("NEW456"));

        verify(householdRepository, never()).delete(previous);
    }

    @Test
    void join_throwsForInvalidCode() {
        var household = buildHousehold("ABC123");
        var user = buildUser(household);
        when(householdRepository.findByInviteCode("XYZ999")).thenReturn(Optional.empty());

        assertThrows(InvalidInviteCodeException.class,
                () -> householdService.join(user, new JoinHouseholdRequest("XYZ999")));
    }

    @Test
    void join_throwsWhenAlreadyInTargetHousehold() {
        var household = buildHousehold("ABC123");
        var user = buildUser(household);
        when(householdRepository.findByInviteCode("ABC123")).thenReturn(Optional.of(household));

        assertThrows(AlreadyInHouseholdException.class,
                () -> householdService.join(user, new JoinHouseholdRequest("ABC123")));
    }

    @Test
    void leave_movesUserToFreshSoloHouseholdAndDeletesEmptyPrevious() {
        var previous = buildHousehold("OLD123");
        var user = buildUser(previous);

        when(householdRepository.existsByInviteCode(any())).thenReturn(false);
        when(householdRepository.save(any(Household.class))).thenAnswer(inv -> {
            var h = inv.<Household>getArgument(0);
            h.setId(UUID.randomUUID());
            h.setCreatedAt(LocalDateTime.now());
            return h;
        });
        when(userRepository.countByHouseholdId(previous.getId())).thenReturn(0L);
        when(userRepository.findAllByHouseholdId(any())).thenReturn(List.of(user));

        var response = householdService.leave(user);

        assertNotEquals(previous.getId(), user.getHousehold().getId());
        assertNotNull(response.inviteCode());
        verify(householdRepository).delete(previous);
    }
}
