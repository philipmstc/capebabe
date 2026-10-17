package net.shchoo.scafe.scafe;

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
                Skill target = content.skills.get(rng.nextInt(content.skills.size()));
                SkillFactory.addNewProductLineToSkill(target, content);
            }
        });
    }

    /** A node for a random recipe. Capacity / charges use {@link Node#UNLIMITED} for "no limit". */
    public static ContentBatch newNode(World world, Board.Position at, int capacity, int maxCharges, int respawnTicks) {
        return newNode(world, at, capacity, maxCharges, respawnTicks, recipe -> 1);
    }

    /** Like {@link #newNode(World, Board.Position, int, int, int)}, picking recipes in proportion to {@code weight}. */
    public static ContentBatch newNode(World world, Board.Position at, int capacity, int maxCharges, int respawnTicks,
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

    private static void requireSkills(World world) {
        if (world.content.skills.isEmpty()) {
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
