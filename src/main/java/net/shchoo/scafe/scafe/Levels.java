package net.shchoo.scafe.scafe;

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

    /** Total xp at which {@code level} ends (capped at the last level). */
    public static long xpToFinish(int level) {
        return Skill.XP_PER_LEVEL[Math.min(level, MAX_LEVEL - 1)];
    }
}
