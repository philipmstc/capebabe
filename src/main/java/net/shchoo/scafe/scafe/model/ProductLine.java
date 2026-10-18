package net.shchoo.scafe.scafe.model;

import java.util.List;

public class ProductLine {
    public final int id;
    public Skill skill;
    public List<Recipe> recipes;

    public ProductLine(int id, Skill skill, List<Recipe> recipes) {
        this.id = id;
        this.skill = skill;
        this.recipes = recipes;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ProductLine other && other.id == id;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(id);
    }
}
