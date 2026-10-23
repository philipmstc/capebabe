package net.shchoo.scafe.scafe.model;

import java.util.HashMap;
import java.util.Map;
import java.util.function.ToIntFunction;

/** How many of an item fit in one inventory slot (and get equipped at once): your level in the skill making it. */
public final class Stacks {
    /** How many stacks your inventory holds. */
    public static final int INVENTORY_SLOTS = 100;

    private Stacks() {}

    /** Slots {@code items} take up, each item in stacks of {@link #size}. */
    public static int slots(WorldContent content, Map<Product, Integer> items, ToIntFunction<Skill> levelOf) {
        int slots = 0;
        for (Map.Entry<Product, Integer> e : items.entrySet()) {
            if (e.getValue() > 0) {
                int stack = size(content, e.getKey(), levelOf);
                slots += (e.getValue() + stack - 1) / stack;
            }
        }
        return slots;
    }

    /** Whether {@code inventory} with {@code changes} added fits (or at least doesn't take more slots than before). */
    public static boolean fits(WorldContent content, Map<Product, Integer> inventory, Map<Product, Integer> changes,
                               ToIntFunction<Skill> levelOf) {
        Map<Product, Integer> after = new HashMap<>(inventory);
        changes.forEach((product, delta) -> after.merge(product, delta, Integer::sum));
        int slots = slots(content, after, levelOf);
        return slots <= INVENTORY_SLOTS || slots <= slots(content, inventory, levelOf);
    }

    /** How many more of {@code product} fit: the free slots' worth, plus the room left in its last stack. */
    public static int room(WorldContent content, Map<Product, Integer> inventory, Product product, ToIntFunction<Skill> levelOf) {
        int stack = size(content, product, levelOf);
        int have = inventory.getOrDefault(product, 0);
        int free = Math.max(0, INVENTORY_SLOTS - slots(content, inventory, levelOf));
        return free * stack + (have % stack == 0 ? 0 : stack - have % stack);
    }

    /** Made by a recipe: that skill's level; anything else (monster drops): your combat level. At least 1. */
    public static int size(WorldContent content, Product product, ToIntFunction<Skill> levelOf) {
        Recipe recipe = content.madeBy(product);
        Skill skill = recipe != null ? recipe.skill : content.combatSkill();
        return skill == null ? 1 : Math.max(1, levelOf.applyAsInt(skill));
    }
}
