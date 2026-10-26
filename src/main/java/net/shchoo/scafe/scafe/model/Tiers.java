package net.shchoo.scafe.scafe.model;

import java.util.Arrays;
import java.util.TreeSet;

/**
 * A product line's five tiers: the level each one unlocks at, and the levels past that at which crafting it makes
 * two, then three, at a time. One table for every skill for now (it may differ by skill type later).
 * <pre>
 *       x1   x2   x3
 *  T0    0   30   65
 *  T1   20   50   75
 *  T2   40   70   85
 *  T3   60   90   95
 *  T4   80  100    -
 * </pre>
 */
public final class Tiers {
    public static final int[] LEVEL_NEEDED = {0, 20, 40, 60, 80};
    /** Per tier: the level it makes x2 at, then x3. */
    private static final int[][] YIELD_LEVELS = {{30, 65}, {50, 75}, {70, 85}, {90, 95}, {100}};
    /**
     * Defaults worlds were made with before, per tier: a recipe still at one of them gets the new level. 0 / 20 / 35
     * / 50 / 70 to start with; then for a while 40 / 60 / 85.
     */
    private static final int[][] OLD_LEVEL_NEEDED = {{0}, {20}, {35, 40}, {50, 60}, {70, 85}};

    private Tiers() {}

    /** Where {@code recipe} is in its product line (0 = the first), or -1 if it's not in one. */
    public static int of(Recipe recipe) {
        for (ProductLine line : recipe.skill.productLines) {
            int index = line.recipes.indexOf(recipe);
            if (index >= 0) {
                return index;
            }
        }
        return -1;
    }

    /** The level a recipe saved as needing {@code saved} at {@code tier} needs now (old defaults move to the new). */
    public static int levelNeeded(int tier, int saved) {
        return tier >= 0 && tier < OLD_LEVEL_NEEDED.length && Arrays.stream(OLD_LEVEL_NEEDED[tier]).anyMatch(l -> l == saved)
                ? LEVEL_NEEDED[tier] : saved;
    }

    /** How many one craft of {@code tier} makes at {@code level}: 1, 2 or 3. */
    public static int yield(int tier, int level) {
        if (tier < 0 || tier >= YIELD_LEVELS.length) {
            return 1;
        }
        int made = 1;
        for (int at : YIELD_LEVELS[tier]) {
            if (level >= at) {
                made++;
            }
        }
        return made;
    }

    /** The level {@code tier} next makes more at, past {@code level}, or -1 when it's as good as it gets. */
    public static int nextYieldLevel(int tier, int level) {
        if (tier < 0 || tier >= YIELD_LEVELS.length) {
            return -1;
        }
        return Arrays.stream(YIELD_LEVELS[tier]).filter(at -> at > level).findFirst().orElse(-1);
    }

    /** The level {@code tier} makes {@code times} (2 or 3) a craft from, or -1 if it never does. */
    public static int yieldLevel(int tier, int times) {
        if (tier < 0 || tier >= YIELD_LEVELS.length || times < 2 || times - 2 >= YIELD_LEVELS[tier].length) {
            return -1;
        }
        return YIELD_LEVELS[tier][times - 2];
    }

    /** Every level something happens at (a tier unlocks or makes more), and the max: what goals step between. */
    public static int[] milestones() {
        TreeSet<Integer> levels = new TreeSet<>();
        Arrays.stream(LEVEL_NEEDED).filter(l -> l > 0).forEach(levels::add);
        Arrays.stream(YIELD_LEVELS).flatMapToInt(Arrays::stream).forEach(levels::add);
        levels.add(Levels.MAX_LEVEL);
        return levels.stream().mapToInt(Integer::intValue).toArray();
    }
}
