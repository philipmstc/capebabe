package net.shchoo.scafe.scafe.model;

/**
 * Praying at a church for a recipe's node: for the next {@link #STEPS} steps / items made / kills, a new node is
 * twice as likely to turn up near you, and the next one that does is that recipe's (then the blessing's spent).
 * It costs {@link #PER_TIER} x its tier of the tier below's item in its product line (tier 3: 300 of tier 2), or
 * {@link #ANY_ITEM} of any one item (the only way for tier 0).
 */
public final class Prayer {
    public static final int STEPS = 100;
    public static final int PER_TIER = 100;
    public static final int ANY_ITEM = 1000;

    private Prayer() {}

    /** Where {@code recipe} is in its product line (0 = the first), or -1 if it's not in one. */
    public static int tier(Recipe recipe) {
        for (ProductLine line : recipe.skill.productLines) {
            int index = line.recipes.indexOf(recipe);
            if (index >= 0) {
                return index;
            }
        }
        return -1;
    }

    /** The item a tier below {@code recipe} in its product line, or null at tier 0. */
    public static Product tierBelow(Recipe recipe) {
        for (ProductLine line : recipe.skill.productLines) {
            int index = line.recipes.indexOf(recipe);
            if (index > 0) {
                return line.recipes.get(index - 1).product;
            }
        }
        return null;
    }

    /** How many of {@code payment} praying for {@code recipe} takes. */
    public static int cost(Recipe recipe, Product payment) {
        Product below = tierBelow(recipe);
        return below != null && below.equals(payment) ? PER_TIER * tier(recipe) : ANY_ITEM;
    }
}
