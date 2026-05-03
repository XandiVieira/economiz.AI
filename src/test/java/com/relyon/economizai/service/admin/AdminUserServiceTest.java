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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
}
