package net.shchoo.scafe.scafe;

public record Goal(Skill skill, int level, Product product) {

    public boolean isMet(Player player) {
        return player.getLevel(skill) >= level;
    }

    public String describe() {
        return "reach " + skill.name + " Lv. " + level + " to make " + product.name;
    }
}
