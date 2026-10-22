package net.shchoo.scafe.scafe.model;

import java.util.function.ToIntFunction;

/** How many of an item fit in one inventory slot (and get equipped at once): your level in the skill making it. */
public final class Stacks {
    private Stacks() {}

    /** Made by a recipe: that skill's level; anything else (monster drops): your combat level. At least 1. */
    public static int size(WorldContent content, Product product, ToIntFunction<Skill> levelOf) {
        Recipe recipe = content.madeBy(product);
        Skill skill = recipe != null ? recipe.skill : content.combatSkill();
        return skill == null ? 1 : Math.max(1, levelOf.applyAsInt(skill));
    }
}
