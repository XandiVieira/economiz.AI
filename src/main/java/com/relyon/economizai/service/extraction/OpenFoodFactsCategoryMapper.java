package com.relyon.economizai.service.extraction;

import com.relyon.economizai.model.enums.ProductCategory;

import java.util.List;
import java.util.Map;

/**
 * Maps Open Food Facts category tags (e.g. {@code en:beverages}) to our
 * {@link ProductCategory} enum.  OPF tags are hierarchical and may contain
 * multiple values; we scan them in priority order and return on first match.
 * Tags not covered here fall back to {@link ProductCategory#OTHER}.
 */
public final class OpenFoodFactsCategoryMapper {

    private OpenFoodFactsCategoryMapper() {}

    // Ordered from most-specific to least-specific within each enum value.
    private static final Map<ProductCategory, List<String>> RULES = Map.ofEntries(
        Map.entry(ProductCategory.BAKERY, List.of(
            "en:breads", "en:bread", "en:bakery", "en:bakery-products",
            "en:pastries", "en:cakes", "en:biscuits-and-cakes"
        )),
        Map.entry(ProductCategory.BEVERAGES, List.of(
            "en:beverages", "en:drinks", "en:waters", "en:juices",
            "en:sodas", "en:soft-drinks", "en:alcoholic-beverages",
            "en:beers", "en:wines", "en:coffees", "en:teas",
            "en:energy-drinks", "en:plant-based-beverages"
        )),
        Map.entry(ProductCategory.MEAT_DAIRY, List.of(
            "en:dairies", "en:dairy", "en:milks", "en:cheeses",
            "en:yogurts", "en:butters", "en:creams",
            "en:meats", "en:beef", "en:pork", "en:poultry", "en:chicken",
            "en:fish", "en:seafood", "en:eggs"
        )),
        Map.entry(ProductCategory.PRODUCE, List.of(
            "en:fruits", "en:vegetables", "en:fresh-vegetables",
            "en:fresh-fruits", "en:plant-based-foods", "en:herbs"
        )),
        Map.entry(ProductCategory.GROCERIES, List.of(
            "en:cereals-and-potatoes", "en:cereals", "en:pasta",
            "en:rice", "en:flours", "en:legumes", "en:beans",
            "en:snacks", "en:chips-and-crackers", "en:chocolates",
            "en:candies", "en:sugars", "en:oils-and-fats",
            "en:sauces", "en:condiments", "en:spices", "en:seasonings",
            "en:canned-foods", "en:frozen-foods", "en:soups",
            "en:grocery", "en:groceries", "en:sweeteners"
        )),
        Map.entry(ProductCategory.CLEANING, List.of(
            "en:cleaning-products", "en:household-supplies",
            "en:laundry", "en:dishwashing", "en:surface-cleaners",
            "en:disinfectants"
        )),
        Map.entry(ProductCategory.PERSONAL_CARE, List.of(
            "en:personal-care", "en:beauty", "en:cosmetics",
            "en:hair-care", "en:skin-care", "en:oral-hygiene",
            "en:deodorants", "en:soaps", "en:shampoos"
        )),
        Map.entry(ProductCategory.HEALTH, List.of(
            "en:dietary-supplements", "en:supplements",
            "en:pharmaceuticals", "en:vitamins", "en:health-foods",
            "en:sport-nutrition", "en:protein-supplements"
        ))
    );

    /**
     * @param categoryTags comma- or space-separated OPF category tags,
     *                     e.g. {@code "en:beverages, en:sodas, pt:refrigerantes"}
     * @return best matching {@link ProductCategory}, or {@link ProductCategory#OTHER}
     */
    public static ProductCategory map(String categoryTags) {
        if (categoryTags == null || categoryTags.isBlank()) return ProductCategory.OTHER;
        var lower = categoryTags.toLowerCase();
        for (var entry : RULES.entrySet()) {
            for (var tag : entry.getValue()) {
                if (lower.contains(tag)) return entry.getKey();
            }
        }
        return ProductCategory.OTHER;
    }
}
