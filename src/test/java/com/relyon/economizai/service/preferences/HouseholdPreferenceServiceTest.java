package com.relyon.economizai.service.preferences;

import com.relyon.economizai.config.CollaborativeProperties;
import com.relyon.economizai.dto.response.HouseholdPreferenceResponse.BrandStrength;
import com.relyon.economizai.dto.response.HouseholdPreferenceResponse.Confidence;
import com.relyon.economizai.model.Household;
import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.ReceiptItem;
import com.relyon.economizai.model.User;
import com.relyon.economizai.repository.ReceiptItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HouseholdPreferenceServiceTest {

    @Mock private ReceiptItemRepository receiptItemRepository;

    private CollaborativeProperties properties;
    private HouseholdPreferenceService service;
    private User user;

    @BeforeEach
    void setUp() {
        properties = new CollaborativeProperties();
        service = new HouseholdPreferenceService(receiptItemRepository, properties);
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        user = User.builder().id(UUID.randomUUID()).email("u@e").household(household).build();
    }

    @Test
    void emptyWhenDisabled() {
        properties.getPreferences().setEnabled(false);
        assertTrue(service.derivePreferences(user).isEmpty());
    }

    @Test
    void skipsGenericsBelowMinPurchases() {
        var leite = product("Leite", "Italac", new BigDecimal("1"), "L");
        when(receiptItemRepository.findConfirmedHistoryForHousehold(any())).thenReturn(List.of(
                purchase(leite), purchase(leite), purchase(leite), purchase(leite)
        ));

        assertTrue(service.derivePreferences(user).isEmpty(),
                "must skip — only 4 purchases, default min is 5");
    }

    @Test
    void surfacesPreferenceWhenAtMinThreshold() {
        var leite = product("Leite", "Italac", new BigDecimal("1"), "L");
        var items = repeat(purchase(leite), 5);
        when(receiptItemRepository.findConfirmedHistoryForHousehold(any())).thenReturn(items);

        var prefs = service.derivePreferences(user);

        assertEquals(1, prefs.size());
        assertEquals("Leite", prefs.get(0).genericName());
        assertEquals(0, prefs.get(0).preferredPackSize().compareTo(new BigDecimal("1")));
        assertEquals("L", prefs.get(0).preferredPackUnit());
        assertEquals(5, prefs.get(0).sampleSize());
        assertEquals(Confidence.LOW, prefs.get(0).confidence());
    }

    @Test
    void detectsMustHaveBrandWhenSingleBrandDominates() {
        var leite = product("Leite", "Italac", new BigDecimal("1"), "L");
        var items = repeat(purchase(leite), 10);
        when(receiptItemRepository.findConfirmedHistoryForHousehold(any())).thenReturn(items);

        var prefs = service.derivePreferences(user);

        assertEquals(1, prefs.size());
        assertEquals("Italac", prefs.get(0).topBrand());
        assertEquals(BrandStrength.MUST_HAVE, prefs.get(0).brandStrength());
        assertEquals(Confidence.MEDIUM, prefs.get(0).confidence());
    }

    @Test
    void detectsPreferredBrandWhenSharedAroundSeventyPercent() {
        var italac = product("Leite", "Italac", new BigDecimal("1"), "L");
        var elege = product("Leite", "Elegê", new BigDecimal("1"), "L");
        var items = new ArrayList<ReceiptItem>();
        items.addAll(repeat(purchase(italac), 7));  // 70%
        items.addAll(repeat(purchase(elege), 3));   // 30%
        when(receiptItemRepository.findConfirmedHistoryForHousehold(any())).thenReturn(items);

        var prefs = service.derivePreferences(user);

        assertEquals(1, prefs.size());
        assertEquals("Italac", prefs.get(0).topBrand());
        assertEquals(BrandStrength.PREFERRED, prefs.get(0).brandStrength());
    }

    @Test
    void noBrandWhenSplitTooEvenly() {
        var italac = product("Leite", "Italac", new BigDecimal("1"), "L");
        var elege = product("Leite", "Elegê", new BigDecimal("1"), "L");
        var items = new ArrayList<ReceiptItem>();
        items.addAll(repeat(purchase(italac), 5));
        items.addAll(repeat(purchase(elege), 5));
        when(receiptItemRepository.findConfirmedHistoryForHousehold(any())).thenReturn(items);

        var prefs = service.derivePreferences(user);

        assertEquals(1, prefs.size(), "pack-size pref still emitted even if brand pref isn't");
        assertNull(prefs.get(0).topBrand());
        assertNull(prefs.get(0).brandStrength());
    }

    @Test
    void packSizePreferenceIsTheModeOfDominantUnit() {
        var oneL = product("Leite", "Italac", new BigDecimal("1"), "L");
        var twoL = product("Leite", "Italac", new BigDecimal("2"), "L");
        var items = new ArrayList<ReceiptItem>();
        items.addAll(repeat(purchase(oneL), 6));
        items.addAll(repeat(purchase(twoL), 2));
        when(receiptItemRepository.findConfirmedHistoryForHousehold(any())).thenReturn(items);

        var prefs = service.derivePreferences(user);

        assertEquals(1, prefs.size());
        assertEquals(0, prefs.get(0).preferredPackSize().compareTo(new BigDecimal("1")));
        assertEquals(0, prefs.get(0).minObservedPackSize().compareTo(new BigDecimal("1")));
        assertEquals(0, prefs.get(0).maxObservedPackSize().compareTo(new BigDecimal("2")));
    }

    @Test
    void skipsItemsWithoutGenericName() {
        var noGeneric = Product.builder()
                .id(UUID.randomUUID()).normalizedName("X").packSize(new BigDecimal("1")).packUnit("L")
                .build();
        when(receiptItemRepository.findConfirmedHistoryForHousehold(any()))
                .thenReturn(repeat(purchase(noGeneric), 10));

        var prefs = service.derivePreferences(user);

        assertEquals(1, prefs.size(), "falls back to normalizedName when genericName missing");
        assertEquals("X", prefs.get(0).genericName());
    }

    @Test
    void groupsAcrossDifferentProductsOfSameGeneric() {
        var italacOne = product("Leite", "Italac", new BigDecimal("1"), "L");
        var italacTwo = product("Leite", "Italac", new BigDecimal("2"), "L");
        var elege = product("Leite", "Elegê", new BigDecimal("1"), "L");
        var items = new ArrayList<ReceiptItem>();
        items.addAll(repeat(purchase(italacOne), 4));
        items.addAll(repeat(purchase(italacTwo), 2));
        items.addAll(repeat(purchase(elege), 4));
        when(receiptItemRepository.findConfirmedHistoryForHousehold(any())).thenReturn(items);

        var prefs = service.derivePreferences(user);

        assertEquals(1, prefs.size());
        assertEquals(10, prefs.get(0).sampleSize(), "all 10 'Leite' purchases collapse into one group");
    }

    private Product product(String genericName, String brand, BigDecimal packSize, String packUnit) {
        return Product.builder()
                .id(UUID.randomUUID())
                .normalizedName(genericName + " " + brand)
                .genericName(genericName)
                .brand(brand)
                .packSize(packSize)
                .packUnit(packUnit)
                .build();
    }

    private ReceiptItem purchase(Product product) {
        var receipt = Receipt.builder().id(UUID.randomUUID()).user(user).household(user.getHousehold()).build();
        return ReceiptItem.builder()
                .id(UUID.randomUUID())
                .receipt(receipt)
                .product(product)
                .lineNumber(1)
                .rawDescription(product.getNormalizedName())
                .quantity(BigDecimal.ONE)
                .unitPrice(BigDecimal.ONE)
                .totalPrice(BigDecimal.ONE)
                .build();
    }

    private List<ReceiptItem> repeat(ReceiptItem template, int times) {
        var out = new ArrayList<ReceiptItem>(times);
        for (int i = 0; i < times; i++) {
            out.add(ReceiptItem.builder()
                    .id(UUID.randomUUID())
                    .receipt(template.getReceipt())
                    .product(template.getProduct())
                    .lineNumber(i + 1)
                    .rawDescription(template.getRawDescription())
                    .quantity(BigDecimal.ONE)
                    .unitPrice(BigDecimal.ONE)
                    .totalPrice(BigDecimal.ONE)
                    .build());
        }
        return out;
    }
}
