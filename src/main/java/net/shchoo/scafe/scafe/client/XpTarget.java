package net.shchoo.scafe.scafe.client;

import net.shchoo.scafe.scafe.model.Goal;
import net.shchoo.scafe.scafe.model.Levels;
import net.shchoo.scafe.scafe.model.Skill;

/**
 * What the xp tracker's bar is heading for in a skill: your nearest unmet goal there (the lowest goal level above
 * your level), else just the next level. {@code fromXp}..{@code toXp} is the bar: for a goal it's all your xp out
 * of the xp that level needs, so it only ever fills up; for the next level it's this level's xp, as before.
 */
public record XpTarget(int level, int targetLevel, boolean goal, long fromXp, long xp, long toXp) {

    public static XpTarget of(Player player, Skill skill) {
        int level = player.getLevel(skill);
        long xp = player.getExperience(skill);
        if (level >= Levels.MAX_LEVEL) {
            return new XpTarget(level, level, false, 0, 1, 1);
        }
        int goalLevel = player.goals.stream()
                .filter(goal -> goal.skill().equals(skill) && !goal.isMet(level))
                .mapToInt(Goal::level)
                .min().orElse(0);
        if (goalLevel > 0) {
            return new XpTarget(level, goalLevel, true, 0, xp, Skill.XP_PER_LEVEL[goalLevel - 1]);
        }
        return new XpTarget(level, level + 1, false, Skill.XP_PER_LEVEL[level - 1], xp, Skill.XP_PER_LEVEL[level]);
    }

    public boolean maxed() {
        return targetLevel == level;
    }

    public long remaining() {
        return Math.max(0, toXp - xp);
    }

    /** How full the bar is, 0..1. */
    public double fraction() {
        long span = toXp - fromXp;
        return span <= 0 ? 1 : Math.max(0, Math.min(1, (double) (xp - fromXp) / span));
    }
}
