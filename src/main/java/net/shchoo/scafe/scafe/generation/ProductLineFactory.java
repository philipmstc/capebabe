package net.shchoo.scafe.scafe.generation;

import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.ProductLine;
import net.shchoo.scafe.scafe.model.Recipe;
import net.shchoo.scafe.scafe.model.Skill;
import net.shchoo.scafe.scafe.model.WorldContent;

import java.util.*;

/**
 * Builds product lines: a product in five tiers, one recipe each. Primary lines need nothing. Crafted lines
 * (secondary, tertiary) take 1-3 other product lines as inputs, each 1-3 of, and every tier's recipe uses those
 * inputs' products of the same tier (a tier 2 bar is made from tier 2 ore). The input shape is the same for every
 * tier of a line, so a line reads as "2 ore + 1 coal -> bar".
 */
public class ProductLineFactory {
    private static final int[] DEFAULT_LEVEL_NEEDED_BY_TIER = {0, 20, 35, 50, 70};
    private static final int[] DEFAULT_XP_GAINED_BY_TIER = {200, 300, 400, 500, 750};
    /** Chances of a crafted line having 1, 2 or 3 different inputs. */
    private static final double[] INPUT_COUNT_WEIGHTS = {0.45, 0.40, 0.15};
    /** Chances of needing 1, 2 or 3 of an input. */
    private static final double[] AMOUNT_WEIGHTS = {0.6, 0.3, 0.1};
    /** Each component unit past the first adds this much to the recipe's xp. */
    private static final double XP_PER_EXTRA_UNIT = 0.25;

    private static Recipe createRecipe(WorldContent world, Skill skill, String productName, int tier,
                                       Map<Product, Integer> components) {
        int units = components.values().stream().mapToInt(Integer::intValue).sum();
        int xp = DEFAULT_XP_GAINED_BY_TIER[tier];
        if (units > 1) {
            xp = (int) Math.round(xp * (1 + XP_PER_EXTRA_UNIT * (units - 1)) / 10.0) * 10;
        }
        return new Recipe(
                world.allocateId(),
                skill,
                new Product(world.allocateId(), productName + "-" + tier, tier),
                DEFAULT_LEVEL_NEEDED_BY_TIER[tier],
                xp,
                components);
    }

    public static ProductLine createPrimaryProductLine(WorldContent world, Skill skill, String productName) {
        List<Recipe> recipes = new ArrayList<>();
        for (int i = 0; i < DEFAULT_LEVEL_NEEDED_BY_TIER.length; i++) {
            recipes.add(createRecipe(world, skill, productName, i, Map.of()));
        }
        return new ProductLine(world.allocateId(), skill, recipes);
    }

    /** Made from the primary skills' lines. */
    public static ProductLine createSecondaryProductLine(WorldContent world, Skill skill, String productName,
                                                         Collection<Skill> primarySkills, Random rng) {
        return createCraftedProductLine(world, skill, productName, primarySkills, List.of(), rng);
    }

    /** Made from at least one secondary skill's line, maybe with primary lines thrown in (feathers for arrows). */
    public static ProductLine createTertiaryProductLine(WorldContent world, Skill skill, String productName,
                                                        Collection<Skill> secondarySkills, Collection<Skill> primarySkills,
                                                        Random rng) {
        return createCraftedProductLine(world, skill, productName, secondarySkills, primarySkills, rng);
    }

    /**
     * The first input is the least-used line among {@code mainSkills} (so every line gets used by something); any
     * further inputs are picked at random from both groups, less-used lines more likely.
     */
    private static ProductLine createCraftedProductLine(WorldContent world, Skill skill, String productName,
                                                        Collection<Skill> mainSkills, Collection<Skill> extraSkills,
                                                        Random rng) {
        ProductLine first = chooseProductLine(chooseSkill(mainSkills));
        List<ProductLine> others = new ArrayList<>();
        for (Skill source : concat(mainSkills, extraSkills)) {
            if (source != skill) {
                source.productLines.stream().filter(line -> line != first).forEach(others::add);
            }
        }
        List<ProductLine> inputs = new ArrayList<>(List.of(first));
        int wanted = 1 + pick(INPUT_COUNT_WEIGHTS, rng);
        while (inputs.size() < wanted && !others.isEmpty()) {
            inputs.add(others.remove(pickLeastUsedLeaning(others, rng)));
        }
        Map<ProductLine, Integer> amounts = new LinkedHashMap<>();
        inputs.forEach(line -> amounts.put(line, 1 + pick(AMOUNT_WEIGHTS, rng)));

        List<Recipe> recipes = new ArrayList<>();
        for (int tier = 0; tier < DEFAULT_LEVEL_NEEDED_BY_TIER.length; tier++) {
            Map<Product, Integer> components = new LinkedHashMap<>();
            int t = tier;
            amounts.forEach((line, amount) -> components.put(line.recipes.get(t).product, amount));
            recipes.add(createRecipe(world, skill, productName, tier, components));
        }
        return new ProductLine(world.allocateId(), skill, recipes);
    }

    private static List<Skill> concat(Collection<Skill> a, Collection<Skill> b) {
        List<Skill> all = new ArrayList<>(a);
        b.stream().filter(s -> !all.contains(s)).forEach(all::add);
        return all;
    }

    /** Index into {@code weights} (which sum to 1). */
    private static int pick(double[] weights, Random rng) {
        double roll = rng.nextDouble();
        for (int i = 0; i < weights.length; i++) {
            roll -= weights[i];
            if (roll < 0) {
                return i;
            }
        }
        return weights.length - 1;
    }

    /** A random line, weighted 1 / (1 + times it's already used) so popular products don't hog every recipe. */
    private static int pickLeastUsedLeaning(List<ProductLine> lines, Random rng) {
        double total = 0;
        for (ProductLine line : lines) {
            total += 1.0 / (1 + usage(line));
        }
        double roll = rng.nextDouble() * total;
        for (int i = 0; i < lines.size(); i++) {
            roll -= 1.0 / (1 + usage(lines.get(i)));
            if (roll < 0) {
                return i;
            }
        }
        return lines.size() - 1;
    }

    private static Skill chooseSkill(Collection<Skill> skills) {
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
