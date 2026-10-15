package net.shchoo.scafe.scafe;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class Player {
    public int x;
    public int y;
    private Map<Skill, Integer> levels;
    private Map<Skill, Long> currentExperience;
    public LinkedHashMap<Product, Integer> inventory;

    public Player() {
        this.levels = new HashMap<>();
        this.currentExperience = new HashMap<>();
        this.inventory = new LinkedHashMap<>();
    }

    public void gainExperience(Skill target, long gained) {
        currentExperience.put(target, currentExperience.computeIfAbsent(target, s-> 0L) + gained);
        if (currentExperience.get(target) > Skill.XP_PER_LEVEL[levels.computeIfAbsent(target, s-> 1)]-1) {
            levels.put(target, levels.get(target) + 1);
        }
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
        return Skill.XP_PER_LEVEL[levels.get(target)];
    }
}
