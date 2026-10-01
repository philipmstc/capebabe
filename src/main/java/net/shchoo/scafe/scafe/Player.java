package net.shchoo.scafe.scafe;

import java.util.HashMap;
import java.util.Map;

public class Player {
    public int x;
    public int y;
    public Map<Skill, Integer> levels;
    public Map<Skill, Long> currentExperience;
    public Map<Product, Integer> inventory;

    public Player() {
        this.levels = new HashMap<>();
        this.currentExperience = new HashMap<>();
        this.inventory = new HashMap<>();
    }

    public void gainExperience(Skill target, long gained) {
        currentExperience.put(target, currentExperience.computeIfAbsent(target, s-> 0L) + gained);
        if (currentExperience.get(target) > Skill.XP_PER_LEVEL[levels.computeIfAbsent(target, s-> 1)]-1) {
            levels.put(target, levels.get(target) + 1);
        }
    }
}
