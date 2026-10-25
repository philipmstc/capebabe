package net.shchoo.scafe.scafe.model;

public final class Levels {
    public static final int MAX_LEVEL = Skill.XP_PER_LEVEL.length;

    private Levels() {}

    public static int levelFor(long xp) {
        int level = 1;
        while (level < MAX_LEVEL && xp >= Skill.XP_PER_LEVEL[level]) {
            level++;
        }
        return level;
    }

    /**
     * The level {@code xp} would be with no cap: the table, then on past it with each level costing what the table's
     * do, 1047 * 2^(level/10) (the last step, 99 to 100, is 1,000,330 in the table and 1,000,334 by the formula).
     */
    public static int virtualLevelFor(long xp) {
        int level = levelFor(xp);
        if (level < MAX_LEVEL) {
            return level;
        }
        long reached = Skill.XP_PER_LEVEL[MAX_LEVEL - 1];
        while (true) {
            long cost = (long) (1047 * Math.pow(2, level / 10.0));
            if (xp < reached + cost) {
                return level;
            }
            reached += cost;
            level++;
        }
    }

    /** Total xp at which {@code level} ends (capped at the last level). */
    public static long xpToFinish(int level) {
        return Skill.XP_PER_LEVEL[Math.min(level, MAX_LEVEL - 1)];
    }
}
