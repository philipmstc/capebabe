package net.shchoo.scafe.scafe.model;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeSet;

/**
 * A product line's five tiers: the level each one unlocks at (the same for every skill), and -- by the skill's type --
 * the levels past that at which crafting it makes two, then three, at a time.
 * <pre>
 *  PRIMARY             SECONDARY           TERTIARY
 *      x1  x2  x3          x1  x2  x3          x1  x2  x3
 *  T0   0  10  45      T0   0  20  55      T0   0  30  65
 *  T1  20  30  65      T1  20  40  75      T1  20  50  95
 *  T2  40  50  75      T2  40  60  95      T2  40  70   -
 *  T3  60  70  95      T3  60  80   -      T3  60  90   -
 *  T4  80  90   -      T4  80 100   -      T4  80   -   -
 * </pre>
 */
public final class Tiers {
    public static final int[] LEVEL_NEEDED = {0, 20, 40, 60, 80};
    /** Per skill type, per tier: the level it makes x2 at, then x3 (fewer: never). */
    private static final Map<SkillType, int[][]> YIELD_LEVELS = Map.of(
            SkillType.PRIMARY, new int[][]{{10, 45}, {30, 65}, {50, 75}, {70, 95}, {90}},
            SkillType.SECONDARY, new int[][]{{20, 55}, {40, 75}, {60, 95}, {80}, {100}},
            SkillType.TERTIARY, new int[][]{{30, 65}, {50, 95}, {70}, {90}, {}});

    private static int[][] yieldLevels(SkillType type) {
        return YIELD_LEVELS.getOrDefault(type, YIELD_LEVELS.get(SkillType.PRIMARY));
    }

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

    /** How many one craft of {@code tier} makes at {@code level} in a skill of {@code type}: 1, 2 or 3. */
    public static int yield(SkillType type, int tier, int level) {
        int[][] table = yieldLevels(type);
        if (tier < 0 || tier >= table.length) {
            return 1;
        }
        int made = 1;
        for (int at : table[tier]) {
            if (level >= at) {
                made++;
            }
        }
        return made;
    }

    /** The level {@code tier} next makes more at, past {@code level}, or -1 when it's as good as it gets. */
    public static int nextYieldLevel(SkillType type, int tier, int level) {
        int[][] table = yieldLevels(type);
        if (tier < 0 || tier >= table.length) {
            return -1;
        }
        return Arrays.stream(table[tier]).filter(at -> at > level).findFirst().orElse(-1);
    }

    /** The level {@code tier} makes {@code times} (2 or 3) a craft from, or -1 if it never does. */
    public static int yieldLevel(SkillType type, int tier, int times) {
        int[][] table = yieldLevels(type);
        if (tier < 0 || tier >= table.length || times < 2 || times - 2 >= table[tier].length) {
            return -1;
        }
        return table[tier][times - 2];
    }

    /** Every level something happens at in a skill of {@code type} (a tier unlocks or makes more), and the max. */
    public static int[] milestones(SkillType type) {
        TreeSet<Integer> levels = new TreeSet<>();
        Arrays.stream(LEVEL_NEEDED).filter(l -> l > 0).forEach(levels::add);
        Arrays.stream(yieldLevels(type)).flatMapToInt(Arrays::stream).forEach(levels::add);
        levels.add(Levels.MAX_LEVEL);
        return levels.stream().mapToInt(Integer::intValue).toArray();
    }
}
