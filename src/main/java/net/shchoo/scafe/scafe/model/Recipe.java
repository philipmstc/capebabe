package net.shchoo.scafe.scafe.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Recipe {
    public final int id;
    public Skill skill;
    public Product product;
    public int levelNeeded;
    public int expGained;
    public Map<Product, Integer> components;

    public Recipe(int id, Skill skill, Product product, int levelNeeded, int expGained, List<Product> components) {
        this(id, skill, product, levelNeeded, expGained, oneEach(components));
    }

    public Recipe(int id, Skill skill, Product product, int levelNeeded, int expGained, Map<Product, Integer> components) {
        this.id = id;
        this.skill = skill;
        this.product = product;
        this.levelNeeded = levelNeeded;
        this.expGained = expGained;
        this.components = new LinkedHashMap<>(components);
        this.components.keySet().forEach(co -> co.recipesUsedIn++);
    }

    private static Map<Product, Integer> oneEach(List<Product> components) {
        Map<Product, Integer> comps = new LinkedHashMap<>();
        components.forEach(co -> comps.put(co, 1));
        return comps;
    }

    public boolean levelMet(int level) {
        return level >= levelNeeded;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Recipe other && other.id == id;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(id);
    }
}
