package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.generation.WorldGenerator;
import net.shchoo.scafe.scafe.model.ContentBatch;
import net.shchoo.scafe.scafe.model.MonsterType;
import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Grows the world from what players do. Every step, every item made, every level up and every kill rolls a
 * chance to discover a new skill, a new node nearby, a new kind of monster, or to bring a monster out. The
 * chances back off exponentially: skills and monster kinds by how many the world already has, nodes and
 * monsters by how many are already near you (so exploring keeps finding things).
 */
public class Discovery {

    public enum Trigger {
        //    chances before backoff: new skill, new node, new monster kind, a monster shows up
        MOVED(0.03, 0.10, 0.01, 0.06),
        MADE(0.08, 0.05, 0.01, 0.00),
        LEVELLED(0.50, 0.30, 0.15, 0.00),
        KILLED(0.05, 0.05, 0.15, 0.00);

        final double skillChance;
        final double nodeChance;
        final double monsterKindChance;
        final double monsterSpawnChance;

        Trigger(double skillChance, double nodeChance, double monsterKindChance, double monsterSpawnChance) {
            this.skillChance = skillChance;
            this.nodeChance = nodeChance;
            this.monsterKindChance = monsterKindChance;
            this.monsterSpawnChance = monsterSpawnChance;
        }
    }

    public static final double SKILL_BACKOFF = 0.6;
    public static final double NODE_BACKOFF = 0.5;
    /** Nodes within this many tiles count towards the node backoff. */
    public static final int NODE_CROWD_RADIUS = 8;
    public static final int SPAWN_MIN_DISTANCE = 2;
    public static final int SPAWN_MAX_DISTANCE = 5;
    /** Unlocked recipes are this many times likelier than locked ones to show up as a new node. */
    public static final int UNLOCKED_WEIGHT = 4;
    public static final double MONSTER_KIND_BACKOFF = 0.5;
    public static final double MONSTER_CROWD_BACKOFF = 0.5;
    /** Monsters within this many tiles count towards the spawn backoff. */
    public static final int MONSTER_CROWD_RADIUS = 10;
    public static final int MONSTER_SPAWN_MIN_DISTANCE = 3;
    public static final int MONSTER_SPAWN_MAX_DISTANCE = 6;
    /** Monster kinds at most this many levels above your combat level are this much likelier to show up. */
    public static final int FAIR_FIGHT_LEVELS = 3;
    public static final int FAIR_FIGHT_WEIGHT = 4;

    private final Random random;
    private final boolean enabled;

    public Discovery(Random random) {
        this(random, true);
    }

    private Discovery(Random random, boolean enabled) {
        this.random = random;
        this.enabled = enabled;
    }

    public static Discovery off() {
        return new Discovery(new Random(0), false);
    }

    /** base * backoff^count */
    static double chance(double base, double backoff, int count) {
        return base * Math.pow(backoff, count);
    }

    void roll(GameServer server, GameCharacter character, Trigger trigger) {
        if (!enabled) {
            return;
        }
        World world = server.world;
        if (random.nextDouble() < chance(trigger.skillChance, SKILL_BACKOFF, world.content.craftingSkills().size())) {
            discoverSkill(server, character);
        }
        boolean anySkills = !world.content.craftingSkills().isEmpty();
        if (anySkills
                && random.nextDouble() < chance(trigger.nodeChance, NODE_BACKOFF, nodesNear(world, character.position()))) {
            discoverNode(server, character);
        }
        if (anySkills
                && random.nextDouble() < chance(trigger.monsterKindChance, MONSTER_KIND_BACKOFF, world.content.monsters.size())) {
            discoverMonsterKind(server, character);
        }
        if (!world.content.monsters.isEmpty() && random.nextDouble() < chance(trigger.monsterSpawnChance,
                MONSTER_CROWD_BACKOFF, server.monsters().near(character.position(), MONSTER_CROWD_RADIUS))) {
            spawnMonsterNear(server, character, pickKind(world.content.monsters, character.combatLevel()));
        }
    }

    private void discoverMonsterKind(GameServer server, GameCharacter character) {
        server.applyContent(WorldGenerator.newMonsterType(server.world));
        List<MonsterType> kinds = server.world.content.monsters;
        MonsterType kind = kinds.get(kinds.size() - 1);
        character.say("Something new is out there: " + kind.name + " (Lv. " + kind.level + ").");
        server.tellOthers(character, character.name + " ran into a new kind of monster: " + kind.name + ".");
        spawnMonsterNear(server, character, kind);
    }

    private void spawnMonsterNear(GameServer server, GameCharacter character, MonsterType kind) {
        Position at = freeSpot(server.world, server.monsters(), character.position(),
                MONSTER_SPAWN_MIN_DISTANCE, MONSTER_SPAWN_MAX_DISTANCE, random);
        if (at != null) {
            server.spawnMonster(kind, at);
            character.say("A " + kind.name + " (Lv. " + kind.level + ") shows up nearby.");
        }
    }

    /** Kinds near your level are likelier, but anything can turn up. */
    private MonsterType pickKind(List<MonsterType> kinds, int combatLevel) {
        int total = kinds.stream().mapToInt(kind -> weight(kind, combatLevel)).sum();
        int pick = random.nextInt(total);
        for (MonsterType kind : kinds) {
            pick -= weight(kind, combatLevel);
            if (pick < 0) {
                return kind;
            }
        }
        return kinds.get(kinds.size() - 1);
    }

    private static int weight(MonsterType kind, int combatLevel) {
        return kind.level <= combatLevel + FAIR_FIGHT_LEVELS ? FAIR_FIGHT_WEIGHT : 1;
    }

    private void discoverSkill(GameServer server, GameCharacter character) {
        ContentBatch batch = WorldGenerator.newSkills(server.world, 1);
        server.applyContent(batch);
        ContentBatch.SkillDef skill = batch.skills().get(0);
        character.say("You discovered a new skill: " + skill.name() + " (" + skill.type().name().toLowerCase() + ")!");
        server.tellOthers(character, character.name + " discovered a new skill: " + skill.name() + ".");
    }

    private void discoverNode(GameServer server, GameCharacter character) {
        Position at = freeSpot(server.world, server.monsters(), character.position(),
                SPAWN_MIN_DISTANCE, SPAWN_MAX_DISTANCE, random);
        if (at == null) {
            return;
        }
        int capacity = 1 + random.nextInt(3);
        int charges = 5 + random.nextInt(16);
        int respawnTicks = 20 + random.nextInt(41);
        ContentBatch batch = WorldGenerator.newNode(server.world, at, capacity, charges, respawnTicks,
                recipe -> recipe.levelMet(character.getLevel(recipe.skill)) ? UNLOCKED_WEIGHT : 1);
        server.applyContent(batch);
        Node node = server.world.content.node(batch.nodes().get(0).id());
        character.say("A " + node.skill().name + " node (" + node.label() + ") appeared nearby.");
    }

    static int nodesNear(World world, Position around) {
        int count = 0;
        for (Node node : world.content.nodes()) {
            if (Math.abs(node.position.x() - around.x()) <= NODE_CROWD_RADIUS
                    && Math.abs(node.position.y() - around.y()) <= NODE_CROWD_RADIUS) {
                count++;
            }
        }
        return count;
    }

    /** A random tile between min and max tiles away (either axis) with no node or monster on it, or null. */
    static Position freeSpot(World world, Monsters monsters, Position around, int min, int max, Random random) {
        List<Position> spots = new ArrayList<>();
        for (int dx = -max; dx <= max; dx++) {
            for (int dy = -max; dy <= max; dy++) {
                if (Math.max(Math.abs(dx), Math.abs(dy)) < min) {
                    continue;
                }
                Position spot = new Position(around.x() + dx, around.y() + dy);
                if (world.content.nodeAt(spot) == null && !monsters.occupied(spot)) {
                    spots.add(spot);
                }
            }
        }
        return spots.isEmpty() ? null : spots.get(random.nextInt(spots.size()));
    }
}
