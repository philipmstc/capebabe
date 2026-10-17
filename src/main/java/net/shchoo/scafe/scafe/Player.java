package net.shchoo.scafe.scafe;

import com.googlecode.lanterna.TextColor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Player implements Walker.Mobile {
    public static final int LEVEL_UP_POPUP_TICKS = 45;

    public int x;
    public int y;
    private Map<Skill, Integer> levels;
    private Map<Skill, Long> currentExperience;
    public LinkedHashMap<Product, Integer> inventory;
    public final List<Goal> goals = new ArrayList<>();
    public final Walker walker = new Walker(this);
    public Board.Position lastActionAt;
    public ActionProgress currentAction;
    public final List<Popup> popups = new ArrayList<>();
    public final XpTracker xpTracker = new XpTracker();

    public Player() {
        this.levels = new HashMap<>();
        this.currentExperience = new HashMap<>();
        this.inventory = new LinkedHashMap<>();
    }

    @Override
    public Board.Position position() {
        return new Board.Position(x, y);
    }

    @Override
    public void moveTo(Board.Position position) {
        x = position.x();
        y = position.y();
    }

    public void startAction(Board.Action action) {
        if (currentAction != null) {
            currentAction = null;
            GameLog.say("Stopped.");
        } else if (!action.canPerform(this)) {
            action.perform(this);
        } else {
            currentAction = new ActionProgress(action, this);
        }
    }

    public void tick() {
        walker.tick();
        if (currentAction != null && !currentAction.tick(this)) {
            currentAction = null;
        }
        popups.removeIf(Popup::tick);
    }

    /** Manual movement; cancels any walk in progress. */
    public void moveBy(int dx, int dy) {
        walker.stop();
        x += dx;
        y += dy;
    }

    public void gainExperience(Skill target, long gained) {
        currentExperience.put(target, currentExperience.computeIfAbsent(target, s-> 0L) + gained);
        xpTracker.record(target, gained);
        popups.add(new Popup(position(), "+" + gained + "xp", SkillStyle.getColorForSkill(target)));
        int before = getLevel(target);
        int level = before;
        while (level < Skill.XP_PER_LEVEL.length && currentExperience.get(target) >= Skill.XP_PER_LEVEL[level]) {
            level++;
        }
        if (level > before) {
            levels.put(target, level);
            onLevelUp(target, before, level);
        }
    }

    private void onLevelUp(Skill skill, int from, int to) {
        GameLog.say(skill.name + " is now Lv. " + to + "!");
        popups.add(Popup.following(this, " " + skill.name + " Lv. " + to + "! ",
                TextColor.ANSI.BLACK, SkillStyle.getColorForSkill(skill), LEVEL_UP_POPUP_TICKS));
        List<String> unlocked = skill.productLines.stream()
                .flatMap(pl -> pl.recipes.stream())
                .filter(r -> r.levelNeeded > from && r.levelNeeded <= to)
                .map(r -> r.product.name)
                .toList();
        if (!unlocked.isEmpty()) {
            GameLog.say("Unlocked: " + String.join(", ", unlocked));
        }
        for (Goal goal : goals) {
            if (goal.skill() == skill && goal.level() > from && goal.level() <= to) {
                GameLog.say("Goal complete: " + goal.describe());
            }
        }
    }

    public boolean addGoal(Goal goal) {
        if (goals.contains(goal)) {
            return false;
        }
        goals.add(goal);
        return true;
    }

    public int getLevel(Skill target) {
        return levels.computeIfAbsent(target, s-> 1);
    }

    public Long getExperience(Skill target) {
        currentExperience.putIfAbsent(target, 0L);
        return currentExperience.get(target);
    }

    public Long getExperienceToNextLevel(Skill target) {
        levels.putIfAbsent(target, 1);
        return Skill.XP_PER_LEVEL[Math.min(levels.get(target), Skill.XP_PER_LEVEL.length - 1)];
    }

    public boolean has(Map<Product, Integer> components) {
        return components.entrySet().stream().allMatch(c -> inventory.getOrDefault(c.getKey(), 0) >= c.getValue());
    }

    public boolean attemptToUseAll(Map<Product, Integer> components) {
        for (Product part : components.keySet()) {
            if (inventory.getOrDefault(part, 0) < components.get(part)) {
                GameLog.say(part.name + " x " + components.get(part) + " are not available");
                return false;
            }
        }
        for (Product part : components.keySet()) {
            inventory.put(part, inventory.get(part) - components.get(part));
        }
        return true;
    }
}
