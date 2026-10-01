package net.shchoo.scafe.scafe;

import java.util.Collection;
import java.util.List;

public class ProductLine {
    Skill skill;
    public ProductLine(Skill skill, List<Recipe> recipes) {
        this.skill = skill;
        this.recipes = recipes;
    }
    List<Recipe> recipes;
}
