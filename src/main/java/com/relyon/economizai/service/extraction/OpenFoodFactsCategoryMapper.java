package com.relyon.economizai.service.extraction;

import com.relyon.economizai.model.enums.ProductCategory;

import java.util.List;

/**
 * Maps Open Food Facts category tags (e.g. {@code en:beverages}) to our
 * {@link ProductCategory} enum. An OFF product carries MANY tags at once
 * (coffee has {@code en:coffees} AND {@code en:beverages} AND the very broad
 * {@code en:plant-based-foods}), so we scan our categories in a fixed PRIORITY
 * order and return the first whose signature tags appear — the more distinctive
 * domains (cleaning, beverages, dairy) win over the broad pantry catch-all.
 *
 * <p>Broad parent tags like {@code en:plant-based-foods} are deliberately NOT
 * used — they cover almost every non-animal food and would mis-bucket coffee,
 * pasta and chocolate as PRODUCE. Only specific fruit/vegetable tags map to
 * PRODUCE.
 */
public final class OpenFoodFactsCategoryMapper {

    private OpenFoodFactsCategoryMapper() {}

    private record Rule(ProductCategory category, List<String> tags) {}

    // Priority order: first rule whose tag matches wins. Distinctive domains
    // before the broad GROCERIES catch-all; BEVERAGES before PRODUCE so coffee/
    // tea/juices don't fall through to a fruit tag.
    private static final List<Rule> RULES = List.of(
        new Rule(ProductCategory.CLEANING, List.of(
            "en:cleaning-products", "en:household-cleaning", "en:household-supplies",
            "en:laundry", "en:dishwashing", "en:surface-cleaners", "en:disinfectants")),
        new Rule(ProductCategory.PERSONAL_CARE, List.of(
            "en:personal-care", "en:beauty", "en:cosmetics", "en:hair-care",
            "en:skin-care", "en:oral-hygiene", "en:toothpastes", "en:deodorants",
            "en:soaps", "en:shampoos", "en:hygiene")),
        new Rule(ProductCategory.HEALTH, List.of(
            "en:dietary-supplements", "en:supplements", "en:pharmaceuticals",
            "en:vitamins", "en:sport-nutrition", "en:protein-supplements",
            "en:medicines")),
        new Rule(ProductCategory.BEVERAGES, List.of(
            "en:beverages", "en:drinks", "en:waters", "en:juices", "en:sodas",
            "en:soft-drinks", "en:alcoholic-beverages", "en:beers", "en:wines",
            "en:coffees", "en:coffee", "en:teas", "en:tea", "en:energy-drinks",
            "en:plant-based-beverages", "en:mate")),
        new Rule(ProductCategory.MEAT_DAIRY, List.of(
            "en:dairies", "en:dairy", "en:milks", "en:cheeses", "en:yogurts",
            "en:butters", "en:creams", "en:meats", "en:beef", "en:pork",
            "en:poultry", "en:chicken", "en:fishes", "en:seafood", "en:eggs",
            "en:sausages", "en:hams", "en:prepared-meats")),
        new Rule(ProductCategory.BAKERY, List.of(
            "en:breads", "en:bread", "en:bakery", "en:bakery-products",
            "en:pastries", "en:cakes", "en:viennoiseries")),
        new Rule(ProductCategory.PRODUCE, List.of(
            "en:fruits", "en:vegetables", "en:fresh-vegetables", "en:fresh-fruits",
            "en:fresh-foods", "en:herbs", "en:legumes-and-their-products")),
        new Rule(ProductCategory.GROCERIES, List.of(
            "en:cereals-and-potatoes", "en:cereals", "en:pastas", "en:pasta",
            "en:rice", "en:flours", "en:legumes", "en:beans", "en:snacks",
            "en:chips-and-crackers", "en:chocolates", "en:confectioneries",
            "en:candies", "en:sugars", "en:oils-and-fats", "en:sauces",
            "en:condiments", "en:spices", "en:seasonings", "en:canned-foods",
            "en:frozen-foods", "en:soups", "en:grocery", "en:groceries",
            "en:sweeteners", "en:breakfasts", "en:cocoa-and-its-products",
            "en:biscuits", "en:cookies", "en:cereal-bars"))
    );

    /**
     * @param categoryTags comma- or space-separated OPF category tags,
     *                     e.g. {@code "en:beverages, en:sodas, pt:refrigerantes"}
     * @return best matching {@link ProductCategory}, or {@link ProductCategory#OTHER}
     */
    public static ProductCategory map(String categoryTags) {
        if (categoryTags == null || categoryTags.isBlank()) return ProductCategory.OTHER;
        var lower = categoryTags.toLowerCase();
        for (var rule : RULES) {
            for (var tag : rule.tags()) {
                if (containsTag(lower, tag)) return rule.category();
            }
        }
        return ProductCategory.OTHER;
    }

    // Match on a whole tag token, not a substring, so "en:tea" doesn't fire on
    // "en:steak" and "en:coffee" doesn't fire on "en:coffee-substitutes"-style
    // false neighbours. Tags are delimited by commas/spaces in the OFF field.
    private static boolean containsTag(String tagsLower, String tag) {
        var index = tagsLower.indexOf(tag);
        while (index >= 0) {
            var before = index == 0 ? ' ' : tagsLower.charAt(index - 1);
            var afterIndex = index + tag.length();
            var after = afterIndex >= tagsLower.length() ? ' ' : tagsLower.charAt(afterIndex);
            var boundedBefore = before == ' ' || before == ',';
            var boundedAfter = after == ' ' || after == ',';
            if (boundedBefore && boundedAfter) return true;
            index = tagsLower.indexOf(tag, index + 1);
        }
        return false;
    }
}
