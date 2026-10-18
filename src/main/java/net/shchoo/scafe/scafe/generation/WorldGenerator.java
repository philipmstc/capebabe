package net.shchoo.scafe.scafe.generation;

import net.shchoo.scafe.scafe.model.ContentBatch;
import net.shchoo.scafe.scafe.model.MonsterType;
import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.Recipe;
import net.shchoo.scafe.scafe.model.Skill;
import net.shchoo.scafe.scafe.model.SkillType;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.model.WorldContent;

import java.util.List;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.ToIntFunction;

/**
 * Grows a world. Each call is one generation step: it runs against a copy of the world's content with an RNG
 * seeded from (world seed, step number), and returns what it added as the next {@link ContentBatch}.
 * Nothing changes until the caller applies the batch, so a step is all-or-nothing.
 */
public final class WorldGenerator {
    private WorldGenerator() {}

    public static ContentBatch newSkills(World world, int count) {
        return generate(world, (content, rng) -> {
            for (int i = 0; i < count; i++) {
                SkillFactory.createRandomSkill(content, rng);
            }
        });
    }

    public static ContentBatch newProductLines(World world, int count) {
        requireSkills(world);
        return generate(world, (content, rng) -> {
            for (int i = 0; i < count; i++) {
                List<Skill> crafting = content.craftingSkills();
                Skill target = crafting.get(rng.nextInt(crafting.size()));
                SkillFactory.addNewProductLineToSkill(target, content);
            }
        });
    }

    /** A node for a random recipe. Capacity / charges use {@link Node#UNLIMITED} for "no limit". */
    public static ContentBatch newNode(World world, Position at, int capacity, int maxCharges, int respawnTicks) {
        return newNode(world, at, capacity, maxCharges, respawnTicks, recipe -> 1);
    }

    /** Like {@link #newNode(World, Position, int, int, int)}, picking recipes in proportion to {@code weight}. */
    public static ContentBatch newNode(World world, Position at, int capacity, int maxCharges, int respawnTicks,
                                       ToIntFunction<Recipe> weight) {
        requireSkills(world);
        return generate(world, (content, rng) -> {
            List<Recipe> recipes = content.skills.stream()
                    .flatMap(skill -> skill.productLines.stream())
                    .flatMap(productLine -> productLine.recipes.stream())
                    .toList();
            int total = recipes.stream().mapToInt(weight).sum();
            int pick = rng.nextInt(Math.max(1, total));
            Recipe chosen = recipes.get(0);
            for (Recipe recipe : recipes) {
                pick -= weight.applyAsInt(recipe);
                if (pick < 0) {
                    chosen = recipe;
                    break;
                }
            }
            content.addNode(new Node(content.allocateId(), at, chosen, capacity, maxCharges, respawnTicks));
        });
    }

    /** Monster names, in discovery order, and the letters they show up as on the map. */
    private static final String MONSTER_SYMBOLS = "MGBZKTWRSD";

    /** A new kind of monster, a bit tougher than the last. The first one also brings the combat skill. */
    public static ContentBatch newMonsterType(World world) {
        return generate(world, (content, rng) -> {
            if (content.combatSkill() == null) {
                content.addSkill(new Skill(content.allocateId(), "combat", SkillType.COMBAT, content.skills.size(), 'x'));
            }
            int n = content.monsters.size();
            int level = 1 + 4 * n + rng.nextInt(3);
            int maxHp = 4 + 2 * level + rng.nextInt(3);
            int maxHit = 1 + level / 3;
            int roamRadius = 2 + rng.nextInt(4);
            int moveTicks = 2 + rng.nextInt(3);
            content.addMonster(new MonsterType(content.allocateId(), "monster" + n, level, maxHp, maxHit, n,
                    MONSTER_SYMBOLS.charAt(n % MONSTER_SYMBOLS.length()), roamRadius, moveTicks));
        });
    }

    /** The monster's next one-of-a-kind drop: a new neutral product (the k-th one was 1 in 2^(k+1)). */
    public static ContentBatch newDrop(World world, int monsterId, String foundBy) {
        return generate(world, (content, rng) -> {
            MonsterType monster = content.monster(monsterId);
            int k = monster.drops.size();
            Product product = new Product(content.allocateId(), monster.name + "-drop" + k);
            monster.drops.add(new MonsterType.Drop(product, MonsterType.oneInForDrop(k), foundBy));
        });
    }

    public static ContentBatch newDrop(World world, int monsterId) {
        return newDrop(world, monsterId, null);
    }

    private static void requireSkills(World world) {
        if (world.content.craftingSkills().isEmpty()) {
            throw new IllegalStateException("No skills yet -- create one with 1 first.");
        }
    }

    private static ContentBatch generate(World world, BiConsumer<WorldContent, Random> step) {
        WorldContent scratch = world.content.copy();
        step.accept(scratch, rngFor(world.seed, world.content.version() + 1));
        return scratch.diff(world.content);
    }

    static Random rngFor(long seed, int step) {
        long mixed = seed ^ (step * 0x9E3779B97F4A7C15L);
        mixed = (mixed ^ (mixed >>> 33)) * 0xFF51AFD7ED558CCDL;
        return new Random(mixed ^ (mixed >>> 33));
    }
}
