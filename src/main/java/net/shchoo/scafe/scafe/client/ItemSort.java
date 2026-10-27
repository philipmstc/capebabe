package net.shchoo.scafe.scafe.client;

import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.Recipe;
import net.shchoo.scafe.scafe.model.WorldContent;

import java.util.Comparator;

/**
 * How the inventory and bank are ordered (Settings > Sort items; a display setting, kept on this computer): as you
 * got them, or by skill number, skill type (primary, secondary, tertiary) or tier, each then by the others. Items
 * no skill makes (monster drops) go last.
 */
public enum ItemSort {
    AS_GOT("as got"), SKILL("skill"), TYPE("type"), TIER("tier");

    public final String label;

    ItemSort(String label) {
        this.label = label;
    }

    /** The next one along ({@code step} 1) or back (-1), round and round. */
    public ItemSort step(int step) {
        ItemSort[] all = values();
        return all[Math.floorMod(ordinal() + step, all.length)];
    }

    /** The order; null for {@link #AS_GOT} (leave it be). */
    public Comparator<Product> comparator(WorldContent content) {
        if (this == AS_GOT) {
            return null;
        }
        Comparator<Product> made = Comparator.comparing(p -> content.madeBy(p) == null);
        Comparator<Product> skill = Comparator.comparingInt(p -> skillIndex(content, p));
        Comparator<Product> type = Comparator.comparingInt(p -> {
            Recipe recipe = content.madeBy(p);
            return recipe == null ? Integer.MAX_VALUE : recipe.skill.type.ordinal();
        });
        Comparator<Product> tier = Comparator.comparingInt(p -> p.root().tier);
        Comparator<Product> rest = Comparator.<Product>comparingInt(p -> p.prestige).thenComparing(p -> p.name);
        return switch (this) {
            case SKILL -> made.thenComparing(skill).thenComparing(tier).thenComparing(rest);
            case TYPE -> made.thenComparing(type).thenComparing(skill).thenComparing(tier).thenComparing(rest);
            default -> made.thenComparing(tier).thenComparing(skill).thenComparing(rest);
        };
    }

    private static int skillIndex(WorldContent content, Product product) {
        Recipe recipe = content.madeBy(product);
        return recipe == null ? Integer.MAX_VALUE : content.skills.indexOf(recipe.skill);
    }
}
