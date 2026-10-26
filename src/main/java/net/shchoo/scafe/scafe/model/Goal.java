package net.shchoo.scafe.scafe.model;

import java.util.Arrays;

/** {@code product}: the locked node's product the goal was set from (g); the level can be moved off it since. */
public record Goal(Skill skill, int level, Product product) {
    /** The levels a goal can be moved between: where a tier unlocks or makes more ({@link Tiers}), and the max. */
    public static final int[] LEVELS = Tiers.milestones();

    public static boolean allowed(int level) {
        return Arrays.stream(LEVELS).anyMatch(l -> l == level);
    }

    public boolean isMet(int currentLevel) {
        return currentLevel >= level;
    }

    /** The next allowed level up (or down, {@code step} -1), or this level when there's none. */
    public int stepped(int step) {
        int next = level;
        for (int l : LEVELS) {
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
        return new Goal(skill, newLevel, product);
    }

    public String describe() {
        return "reach " + skill.name + " Lv. " + level + " (" + product.name + ")";
    }
}
