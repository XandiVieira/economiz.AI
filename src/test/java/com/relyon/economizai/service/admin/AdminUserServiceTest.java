package com.relyon.economizai.service.admin;

import com.relyon.economizai.exception.UserNotFoundException;
import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.ReceiptStatus;
import com.relyon.economizai.repository.InsightsRepository;
import com.relyon.economizai.repository.ReceiptRepository;
import com.relyon.economizai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private ReceiptRepository receiptRepository;
    @Mock private InsightsRepository insightsRepository;

    @InjectMocks private AdminUserService service;

    @Test
    void getBundlesUserWithHouseholdAndSpendStats() {
        var householdId = UUID.randomUUID();
        var household = Household.builder().id(householdId).inviteCode("ABC123").build();
        var user = User.builder()
                .id(UUID.randomUUID())
                .name("Maria")
                .email("maria@test.com")
                .household(household)
                .build();

        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(receiptRepository.countByHouseholdIdAndStatus(householdId, ReceiptStatus.PENDING_CONFIRMATION)).thenReturn(2L);
        when(receiptRepository.countByHouseholdIdAndStatus(householdId, ReceiptStatus.CONFIRMED)).thenReturn(7L);
        when(receiptRepository.countByHouseholdIdAndStatus(householdId, ReceiptStatus.REJECTED)).thenReturn(0L);
        when(receiptRepository.countByHouseholdIdAndStatus(householdId, ReceiptStatus.FAILED_PARSE)).thenReturn(1L);
        when(insightsRepository.totalSpend(eq(householdId), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(new BigDecimal("321.45"));
        when(userRepository.countByHouseholdId(householdId)).thenReturn(2L);

        var detail = service.get(user.getId());

        assertEquals("Maria", detail.name());
        assertEquals(householdId, detail.householdId());
        assertEquals(2L, detail.householdMemberCount());
        assertEquals(7L, detail.receipts().confirmed());
        assertEquals(2L, detail.receipts().pendingConfirmation());
        assertEquals(0, new BigDecimal("321.45").compareTo(detail.spendLast30Days()));
    }

    @Test
    void getThrowsWhenUserMissing() {
        var id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());
        assertThrows(UserNotFoundException.class, () -> service.get(id));
    }

    @Test
    void getNullSpendFallsBackToZero() {
        var householdId = UUID.randomUUID();
        var household = Household.builder().id(householdId).inviteCode("ABC123").build();
        var user = User.builder()
                .id(UUID.randomUUID())
                .name("Joao")
                .email("joao@test.com")
                .household(household)
                .build();

        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(receiptRepository.countByHouseholdIdAndStatus(eq(householdId), any(ReceiptStatus.class))).thenReturn(0L);
        when(insightsRepository.totalSpend(eq(householdId), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(null);
        when(userRepository.countByHouseholdId(householdId)).thenReturn(1L);

        var detail = service.get(user.getId());

        assertEquals(BigDecimal.ZERO, detail.spendLast30Days());
    }

    @Test
    void listUnsortedPageableDefaultsToCreatedAtDescAndMapsSummaries() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        var user = User.builder()
                .id(UUID.randomUUID())
                .name("John Doe")
                .email("john@test.com")
                .household(household)
                .build();
        user.setCreatedAt(LocalDateTime.now());
        var sortedPageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(user)));

        var page = service.list(null, PageRequest.of(0, 20));

        assertEquals(1, page.getTotalElements());
        assertEquals("john@test.com", page.getContent().get(0).email());

        verify(userRepository).findAll(any(Specification.class), sortedPageableCaptor.capture());
        var appliedSort = sortedPageableCaptor.getValue().getSort().getOrderFor("createdAt");
        assertTrue(appliedSort != null && appliedSort.getDirection() == Sort.Direction.DESC);
    }

    @Test
    void listPreservesCallerSortWhenAlreadySorted() {
        var requested = PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "email"));
        var sortedPageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.list("  john  ", requested);

        verify(userRepository).findAll(any(Specification.class), sortedPageableCaptor.capture());
        assertEquals(requested, sortedPageableCaptor.getValue());
    }
}
