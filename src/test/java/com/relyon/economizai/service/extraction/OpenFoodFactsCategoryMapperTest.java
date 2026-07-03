package com.relyon.economizai.service.extraction;

import com.relyon.economizai.model.enums.ProductCategory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenFoodFactsCategoryMapperTest {

    @Test
    void coffee_mapsToBeveragesNotProduce() {
        // Coffee carries en:coffees AND the broad en:plant-based-foods — the old
        // mapper let the broad tag win and bucketed it as PRODUCE.
        var tags = "en:plant-based-foods-and-beverages,en:plant-based-foods,en:coffees,en:ground-coffees";
        assertEquals(ProductCategory.BEVERAGES, OpenFoodFactsCategoryMapper.map(tags));
    }

    @Test
    void sodas_mapToBeverages() {
        assertEquals(ProductCategory.BEVERAGES,
                OpenFoodFactsCategoryMapper.map("en:beverages,en:sodas,pt:refrigerantes"));
    }

    @Test
    void condensedMilk_mapsToMeatDairy() {
        assertEquals(ProductCategory.MEAT_DAIRY,
                OpenFoodFactsCategoryMapper.map("en:dairies,en:sweetened-condensed-milks"));
    }

    @Test
    void freshVegetable_mapsToProduce() {
        assertEquals(ProductCategory.PRODUCE,
                OpenFoodFactsCategoryMapper.map("en:fresh-foods,en:vegetables,en:fresh-vegetables"));
    }

    @Test
    void pasta_mapsToGroceriesNotProduce() {
        // Pasta also carries the broad plant-based tag; must land in GROCERIES.
        assertEquals(ProductCategory.GROCERIES,
                OpenFoodFactsCategoryMapper.map("en:plant-based-foods,en:cereals-and-potatoes,en:pastas"));
    }

    @Test
    void chocolate_mapsToGroceries() {
        assertEquals(ProductCategory.GROCERIES,
                OpenFoodFactsCategoryMapper.map("en:snacks,en:sweet-snacks,en:chocolates"));
    }

    @Test
    void cleaningProduct_mapsToCleaning() {
        assertEquals(ProductCategory.CLEANING,
                OpenFoodFactsCategoryMapper.map("en:household-supplies,en:laundry"));
    }

    @Test
    void unknownOrEmptyTags_mapToOther() {
        assertEquals(ProductCategory.OTHER, OpenFoodFactsCategoryMapper.map(""));
        assertEquals(ProductCategory.OTHER, OpenFoodFactsCategoryMapper.map(null));
        assertEquals(ProductCategory.OTHER, OpenFoodFactsCategoryMapper.map("en:some-unmapped-tag"));
    }

    @Test
    void tagBoundary_doesNotFireOnSubstringNeighbour() {
        // "en:tea" must not match inside "en:steaks"; that row is a meat.
        assertEquals(ProductCategory.MEAT_DAIRY,
                OpenFoodFactsCategoryMapper.map("en:meats,en:steaks"));
    }
}
