package net.shchoo.scafe.scafe.model;

import java.util.List;
import java.util.Map;

/**
 * Everything one generation step added to a world, as plain data (ids instead of references),
 * so it can be applied atomically, saved, and sent to clients. {@code origin} says whose action made it and
 * when (null if unknown, e.g. batches saved before this was recorded).
 */
public record ContentBatch(int version,
                           List<SkillDef> skills,
                           List<ProductDef> products,
                           List<ProductLineDef> productLines,
                           List<RecipeDef> recipes,
                           List<NodeDef> nodes,
                           List<MonsterDef> monsters,
                           List<DropDef> drops,
                           Origin origin) {

    public ContentBatch(int version, List<SkillDef> skills, List<ProductDef> products, List<ProductLineDef> productLines,
                        List<RecipeDef> recipes, List<NodeDef> nodes, List<MonsterDef> monsters, List<DropDef> drops) {
        this(version, skills, products, productLines, recipes, nodes, monsters, drops, null);
    }

    /** Who caused this content (a character's name) and when, in epoch milliseconds. */
    public record Origin(String by, long at) {}

    public ContentBatch withOrigin(Origin origin) {
        return new ContentBatch(version, skills, products, productLines, recipes, nodes, monsters, drops, origin);
    }

    public record SkillDef(int id, String name, SkillType type, int colorIndex, char symbol) {}

    /** {@code tier}: which tier of its product line, or {@link Product#NO_TIER}. */
    public record ProductDef(int id, String name, int tier) {}

    public record ProductLineDef(int id, int skillId) {}

    /** Recipes of a product line are listed in tier order. */
    public record RecipeDef(int id, int productLineId, int productId, int levelNeeded, int expGained,
                            Map<Integer, Integer> components) {}

    public record NodeDef(int id, int x, int y, int recipeId, int capacity, int maxCharges, int respawnTicks) {}

    public record MonsterDef(int id, String name, int level, int maxHp, int maxHit, int colorIndex, char symbol,
                             int roamRadius, int moveTicks) {}

    /** A one-of-a-kind drop that just dropped; a monster's drops are listed (across batches) in the order found. */
    public record DropDef(int monsterId, int productId, int oneIn, String foundBy) {}

    public boolean isEmpty() {
        return skills.isEmpty() && products.isEmpty() && productLines.isEmpty() && recipes.isEmpty() && nodes.isEmpty()
                && monsters.isEmpty() && drops.isEmpty();
    }
}
