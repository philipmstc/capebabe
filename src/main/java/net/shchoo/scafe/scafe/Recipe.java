package net.shchoo.scafe.scafe;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Recipe {
    Skill skill;
    Product product;
    int levelNeeded;
    int expGained;
    Map<Product, Integer> components;

    public Recipe(Skill skill, Product product, int levelNeeded, int expGained, List<Product> components)
    {
        this.skill = skill;
        this.product = product;
        this.levelNeeded = levelNeeded;
        this.expGained = expGained;
        HashMap<Product, Integer> comps = new HashMap<>();
        components.forEach(co -> {
            comps.put(co, 1);
            co.recipesUsedIn++;
        });
        this.components = comps;
    }

    public boolean levelMet(Player player) {
        return player.getLevel(skill) >= levelNeeded;
    }

    public boolean create(Player player) {
        if (!levelMet(player)) {
            GameLog.say("Need " + skill.name + " Lv. " + levelNeeded + " (you're Lv. " + player.getLevel(skill) + ")");
            return false;
        }
        if (player.attemptToUseAll(components)) {
            player.inventory.putIfAbsent(product, 0);
            player.inventory.put(product, player.inventory.get(product)+1);
            player.gainExperience(skill, expGained);
            return true;
        }
        else {
            return false;
        }

    }
}
