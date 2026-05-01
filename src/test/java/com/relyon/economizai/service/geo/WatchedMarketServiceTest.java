package com.relyon.economizai.service.geo;

import com.relyon.economizai.exception.MarketNotFoundException;
import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.MarketLocation;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.UserWatchedMarket;
import com.relyon.economizai.repository.MarketLocationRepository;
import com.relyon.economizai.repository.ReceiptRepository;
import com.relyon.economizai.repository.UserWatchedMarketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WatchedMarketServiceTest {

    @Mock private UserWatchedMarketRepository watchedRepository;
    @Mock private MarketLocationRepository marketRepository;
    @Mock private ReceiptRepository receiptRepository;

    private WatchedMarketService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new WatchedMarketService(watchedRepository, marketRepository, receiptRepository);
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        user = User.builder().id(UUID.randomUUID()).email("u@e").household(household).build();
    }

    @Test
    void watch_throwsWhenMarketUnknown() {
        when(marketRepository.findByCnpj(eq("99999999000111"))).thenReturn(Optional.empty());

        assertThrows(MarketNotFoundException.class,
                () -> service.watch(user, "99999999000111"));
        verify(watchedRepository, never()).save(any());
    }

    @Test
    void watch_isIdempotent() {
        var loc = market("11111111000111", "Mercado A");
        when(marketRepository.findByCnpj(eq("11111111000111"))).thenReturn(Optional.of(loc));
        when(watchedRepository.findByUserIdAndMarketCnpj(eq(user.getId()), eq("11111111000111")))
                .thenReturn(Optional.of(UserWatchedMarket.builder().user(user).marketCnpj("11111111000111").build()));
        when(receiptRepository.findDistinctCnpjsByHousehold(eq(user.getHousehold().getId())))
                .thenReturn(List.of());

        var response = service.watch(user, "11111111000111");

        assertTrue(response.watching());
        verify(watchedRepository, never()).save(any()); // already pinned → no insert
    }

    @Test
    void unwatch_delegatesToRepo() {
        service.unwatch(user, "11111111000111");
        verify(watchedRepository).deleteByUserIdAndMarketCnpj(user.getId(), "11111111000111");
    }

    @Test
    void watchedCnpjs_returnsSet() {
        when(watchedRepository.findAllByUserId(eq(user.getId()))).thenReturn(List.of(
                UserWatchedMarket.builder().marketCnpj("11111111000111").build(),
                UserWatchedMarket.builder().marketCnpj("22222222000111").build()
        ));

        var set = service.watchedCnpjs(user);

        assertEquals(2, set.size());
        assertTrue(set.contains("11111111000111"));
    }

    @Test
    void listWatched_returnsHydratedRowsAndFlagsVisited() {
        var pinned = List.of(
                UserWatchedMarket.builder().marketCnpj("11111111000111").build(),
                UserWatchedMarket.builder().marketCnpj("22222222000111").build()
        );
        when(watchedRepository.findAllByUserId(eq(user.getId()))).thenReturn(pinned);
        when(marketRepository.findAllByCnpjIn(any())).thenReturn(List.of(
                market("11111111000111", "Mercado A"),
                market("22222222000111", "Mercado B")
        ));
        when(receiptRepository.findDistinctCnpjsByHousehold(eq(user.getHousehold().getId())))
                .thenReturn(List.of("11111111000111"));

        var rows = service.listWatched(user);

        assertEquals(2, rows.size());
        var a = rows.stream().filter(r -> r.cnpj().equals("11111111000111")).findFirst().orElseThrow();
        var b = rows.stream().filter(r -> r.cnpj().equals("22222222000111")).findFirst().orElseThrow();
        assertTrue(a.visited());
        assertFalse(b.visited());
        assertTrue(a.watching());
        assertTrue(b.watching());
    }

    @Test
    void listForPicker_putsWatchedFirstThenVisited() {
        when(receiptRepository.findDistinctCnpjsByHousehold(eq(user.getHousehold().getId())))
                .thenReturn(List.of("33333333000111"));
        when(watchedRepository.findAllByUserId(eq(user.getId()))).thenReturn(List.of(
                UserWatchedMarket.builder().marketCnpj("11111111000111").build()
        ));
        when(marketRepository.findAllByCnpjIn(any())).thenReturn(List.of(
                market("11111111000111", "Watched"),
                market("33333333000111", "Visited")
        ));

        var rows = service.listForPicker(user, null);

        assertEquals(2, rows.size());
        assertEquals("11111111000111", rows.get(0).cnpj()); // watched first
        assertEquals("33333333000111", rows.get(1).cnpj()); // then visited
    }

    private MarketLocation market(String cnpj, String name) {
        return MarketLocation.builder()
                .cnpj(cnpj).cnpjRoot(cnpj.substring(0, 8)).name(name)
                .latitude(new BigDecimal("-30.0500000")).longitude(new BigDecimal("-51.2200000"))
                .build();
    }
}
