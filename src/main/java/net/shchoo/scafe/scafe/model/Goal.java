package net.shchoo.scafe.scafe.model;

public record Goal(Skill skill, int level, Product product) {

    public boolean isMet(int currentLevel) {
        return currentLevel >= level;
    }

    public String describe() {
        return "reach " + skill.name + " Lv. " + level + " to make " + product.name;
    }
}
