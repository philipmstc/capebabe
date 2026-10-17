package net.shchoo.scafe.scafe;

import java.util.*;
import java.util.stream.Stream;

public class ProductLineFactory {
    private static final int[] DEFAULT_LEVEL_NEEDED_BY_TIER = {0, 20, 35, 50, 70};
    private static final int[] DEFAULT_XP_GAINED_BY_TIER = {200,300,400,500,750};

    private static Recipe createRecipe(Skill skill, String productName, int tier, List<Product> components) {
        return new Recipe(
                skill,
                new Product(productName + "-" + tier),
                DEFAULT_LEVEL_NEEDED_BY_TIER[tier],
                DEFAULT_XP_GAINED_BY_TIER[tier],
                components);
    }

    public static ProductLine createPrimaryProductLine(Skill skill, String productName) {
        List<Recipe> recipes = new ArrayList<>();
        for (int i = 0; i < DEFAULT_LEVEL_NEEDED_BY_TIER.length; i++) {
            recipes.add(createRecipe(skill, productName, i, new ArrayList<>()));
        }
        return new ProductLine(skill, recipes);
    }

    public static ProductLine createSecondaryProductLine(Skill skill, String productName, Collection<Skill> primarySkills) {
        List<Recipe> recipes = new ArrayList<>();
        Skill targetSkill = chooseSkill(primarySkills);
        ProductLine targetProductLine = chooseProductLine(targetSkill);
        for (int i = 0; i < DEFAULT_LEVEL_NEEDED_BY_TIER.length; i++) {
            Product p = new Product(productName + "-" + i);
            List<Product> comps = new ArrayList<>();
            // todo only single component recipes considered for now.
            Product targetProduct = targetProductLine.recipes.get(i).product;
            comps.add(targetProduct);
            recipes.add(createRecipe(skill, productName, i, comps));
        }
        return new ProductLine(skill, recipes);
    }

    public static ProductLine createTertiaryProductLine(Skill skill, String productName, Collection<Skill> secondarySkills) {
        // todo does this need special treatment?
        return createSecondaryProductLine(skill, productName, secondarySkills);
    }

    private static Skill chooseSkill(Collection<Skill> skills) {
        // todo add some variance here eventually
        return skills.stream()
                .min(Comparator.comparingInt(ProductLineFactory::leastUsage))
                .orElseThrow(() -> new IllegalStateException("No skills to build a product line from"));
    }

    private static ProductLine chooseProductLine(Skill skill) {
        return skill.productLines.stream()
                .min(Comparator.comparingInt(ProductLineFactory::usage))
                .orElseThrow(() -> new IllegalStateException("Skill " + skill.name + " has no product lines"));
    }

    private static int usage(ProductLine productLine) {
        return productLine.recipes.get(0).product.recipesUsedIn;
    }

    private static int leastUsage(Skill skill) {
        return skill.productLines.stream()
                .mapToInt(ProductLineFactory::usage)
                .min()
                .orElse(Integer.MAX_VALUE); // a skill with no lines has nothing to offer
    }
}
