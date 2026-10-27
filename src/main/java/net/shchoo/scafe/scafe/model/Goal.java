package net.shchoo.scafe.scafe.model;

import java.util.Arrays;

/**
 * A skill's goal (one a skill at most): a level to reach, one of its milestones ({@link #levels}). {@code product}:
 * the tier that milestone is about -- the one it unlocks, or makes x2 / x3 from.
 */
public record Goal(Skill skill, int level, Product product) {
    /** The levels this goal can be moved between: where its skill's tiers unlock or make more ({@link Tiers}), and the max. */
    public int[] levels() {
        return Tiers.milestones(skill.type);
    }

    public boolean allows(int level) {
        return Arrays.stream(levels()).anyMatch(l -> l == level);
    }

    public boolean isMet(int currentLevel) {
        return currentLevel >= level;
    }

    /** Only skills with recipes have milestones to aim for. */
    public static boolean possible(Skill skill) {
        return skill.type != SkillType.COMBAT && !skill.productLines.isEmpty();
    }

    /** {@code skill}'s goal at {@code level}, about the tier that unlocks there, else the one making more there. */
    public static Goal toward(Skill skill, int level) {
        var recipes = skill.productLines.get(0).recipes;
        Product yields = null, highest = recipes.get(0).product;
        for (int t = 0; t < recipes.size(); t++) {
            Recipe recipe = recipes.get(t);
            if (recipe.levelNeeded == level) {
                return new Goal(skill, level, recipe.product);
            }
            if (Tiers.yieldLevel(skill.type, t, 2) == level || Tiers.yieldLevel(skill.type, t, 3) == level) {
                yields = recipe.product;
            }
            if (recipe.levelNeeded <= level) {
                highest = recipe.product;
            }
        }
        return new Goal(skill, level, yields != null ? yields : highest);
    }

    /** The first milestone past {@code currentLevel}, or null when there's none left. */
    public static Goal next(Skill skill, int currentLevel) {
        if (!possible(skill)) {
            return null;
        }
        return Arrays.stream(Tiers.milestones(skill.type)).filter(l -> l > currentLevel).boxed().findFirst()
                .map(l -> toward(skill, l)).orElse(null);
    }

    /** The next allowed level up (or down, {@code step} -1), or this level when there's none. */
    public int stepped(int step) {
        int next = level;
        for (int l : levels()) {
            if (step > 0 && l > level) {
                return l;
            }
            if (step < 0 && l < level) {
                next = l;
            }
        }
        return next;
    }

    public Goal withLevel(int newLevel) {
        return skill.productLines.isEmpty() ? new Goal(skill, newLevel, product) : toward(skill, newLevel);
    }

    /** What reaching it gets you: "skill3-1 unlocks", "skill3-0 x2", or just the item. */
    public String what() {
        var recipes = skill.productLines.isEmpty() ? java.util.List.<Recipe>of() : skill.productLines.get(0).recipes;
        for (int t = 0; t < recipes.size(); t++) {
            if (!recipes.get(t).product.equals(product)) {
                continue;
            }
            if (recipes.get(t).levelNeeded == level) {
                return product.name + " unlocks";
            }
            for (int times = 2; times <= 3; times++) {
                if (Tiers.yieldLevel(skill.type, t, times) == level) {
                    return product.name + " x" + times;
                }
            }
        }
        return product.name;
    }

    public String describe() {
        return "reach " + skill.name + " Lv. " + level + " (" + what() + ")";
    }
}
