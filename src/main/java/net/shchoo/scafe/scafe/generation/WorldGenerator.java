package net.shchoo.scafe.scafe.generation;

import net.shchoo.scafe.scafe.model.Building;
import net.shchoo.scafe.scafe.model.DamageColor;
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

import java.util.ArrayList;
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
                SkillFactory.addNewProductLineToSkill(target, content, rng);
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
            // any hue, now and then colourless (DamageColor)
            int roll = rng.nextInt(10);
            int color = roll == 0 ? DamageColor.WHITE : roll == 1 ? DamageColor.BLACK : rng.nextInt(DamageColor.HUES);
            content.addMonster(new MonsterType(content.allocateId(), "monster" + n, level, maxHp, maxHit, color,
                    MONSTER_SYMBOLS.charAt(n % MONSTER_SYMBOLS.length()), roamRadius, moveTicks));
        });
    }

    /** Nothing of a building (bank, church) may be in here, so you have to walk a little to find it. */
    public static final int BUILDING_CLEAR_RADIUS = 10;
    /** ...and all of it within this far. */
    public static final int BUILDING_MAX_RADIUS = 20;

    /**
     * A building of {@code kind}, at a random spot (from the world's seed) whose every tile is outside the
     * {@link #BUILDING_CLEAR_RADIUS} square round the origin and inside {@link #BUILDING_MAX_RADIUS}, with nothing
     * on or right around it. Empty if there's no such spot.
     */
    public static ContentBatch newBuilding(World world, Building.Kind kind) {
        return newBuilding(world, kind, BUILDING_CLEAR_RADIUS, BUILDING_MAX_RADIUS);
    }

    /** Like {@link #newBuilding(World, Building.Kind)}, between {@code clear} (exclusive) and {@code max} tiles out. */
    public static ContentBatch newBuilding(World world, Building.Kind kind, int clear, int max) {
        return generate(world, (content, rng) -> {
            List<Position> spots = new ArrayList<>();
            // no room within the radius (a long one, with others in the way): look further out
            for (int radius = max; spots.isEmpty() && radius <= 4 * max; radius += 10) {
                for (int y = -radius; y <= radius - kind.height() + 1; y++) {
                    for (int x = -radius; x <= radius - kind.width() + 1; x++) {
                        Building candidate = new Building(0, kind, new Position(x, y));
                        if (candidate.tiles().stream().allMatch(t -> Math.max(Math.abs(t.x()), Math.abs(t.y())) > clear)
                                && clearAround(content, candidate)) {
                            spots.add(candidate.corner());
                        }
                    }
                }
            }
            if (!spots.isEmpty()) {
                content.addBuilding(new Building(content.allocateId(), kind, spots.get(rng.nextInt(spots.size()))));
            }
        });
    }

    /**
     * A building of {@code kind} with its corner as close as it can get to {@code wanted} (within {@code search}
     * tiles), clear all round like any building and on no {@code occupied} tile (someone standing there). Empty
     * if there's nowhere.
     */
    public static ContentBatch newBuildingAt(World world, Building.Kind kind, Position wanted, int search,
                                             java.util.function.Predicate<Position> occupied) {
        return generate(world, (content, rng) -> {
            for (int distance = 0; distance <= search; distance++) {
                for (int dy = -distance; dy <= distance; dy++) {
                    for (int dx = -distance; dx <= distance; dx++) {
                        if (Math.max(Math.abs(dx), Math.abs(dy)) != distance) {
                            continue;
                        }
                        Building candidate = new Building(0, kind, new Position(wanted.x() + dx, wanted.y() + dy));
                        if (clearAround(content, candidate) && candidate.tiles().stream().noneMatch(occupied)
                                && candidate.tiles().stream().allMatch(t -> Math.max(Math.abs(t.x()), Math.abs(t.y())) > BUILDING_CLEAR_RADIUS)) {
                            content.addBuilding(new Building(content.allocateId(), kind, candidate.corner()));
                            return;
                        }
                    }
                }
            }
        });
    }

    /** The prestige {@code prestige} version of {@code base} (an item made by someone that many times prestiged). */
    public static ContentBatch newPrestigeProduct(World world, Product base, int prestige) {
        return generate(world, (content, rng) -> content.addProduct(new Product(content.allocateId(),
                Product.prestigeName(base, prestige), base.tier, content.product(base.id), prestige)));
    }

    /** Nothing on it or one tile round it, so it walls nothing in and can always be walked up to. */
    private static boolean clearAround(WorldContent content, Building building) {
        for (Position tile : building.tiles()) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (content.builtOn(new Position(tile.x() + dx, tile.y() + dy))) {
                        return false;
                    }
                }
            }
        }
        return true;
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
