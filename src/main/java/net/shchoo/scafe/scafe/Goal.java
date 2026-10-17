package net.shchoo.scafe.scafe;

public record Goal(Skill skill, int level, Product product) {

    public static Goal fromDef(WorldContent content, Event.GoalDef def) {
        return new Goal(content.skill(def.skillId()), def.level(), content.product(def.productId()));
    }

    public Event.GoalDef toDef() {
        return new Event.GoalDef(skill.id, level, product.id);
    }

    public boolean isMet(Player player) {
        return player.getLevel(skill) >= level;
    }

    public String describe() {
        return "reach " + skill.name + " Lv. " + level + " to make " + product.name;
    }
}
