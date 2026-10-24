package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.generation.WorldGenerator;
import net.shchoo.scafe.scafe.model.Building;
import net.shchoo.scafe.scafe.model.Stations;
import net.shchoo.scafe.scafe.model.ContentBatch;
import net.shchoo.scafe.scafe.model.MonsterType;
import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.Recipe;
import net.shchoo.scafe.scafe.model.Skill;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.model.Stat;
import net.shchoo.scafe.scafe.protocol.Event;

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

    /** More than this many tiles from every station, on the move, you might come across a new one. */
    public static final int STATION_LONELY_DISTANCE = 60;
    /** ...with this chance a tick. */
    public static final double STATION_CHANCE = 0.02;

    private final Random random;
    private final boolean enabled;

    public Discovery(Random random) {
        this(random, true);
    }

    private Discovery(Random random, boolean enabled) {
        this.random = random;
        this.enabled = enabled;
    }

    /** Off = the world only grows when asked to (debug commands), and new skills don't bring a starter node. */
    public boolean enabled() {
        return enabled;
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
        if (trigger == Trigger.MOVED && farFromStations(world, character.position()) && random.nextDouble() < STATION_CHANCE) {
            server.discoverStation(character);
        }
        if (!world.content.monsters.isEmpty() && random.nextDouble() < chance(trigger.monsterSpawnChance,
                MONSTER_CROWD_BACKOFF, server.monsters().near(character.position(), MONSTER_CROWD_RADIUS))) {
            spawnMonsterNear(server, character, pickKind(world.content.monsters, character.combatLevel()));
        }
    }

    /**
     * There's a line (the station by the origin) and you're further than {@link #STATION_LONELY_DISTANCE} (either
     * axis) from every station on it.
     */
    static boolean farFromStations(World world, Position at) {
        List<Building> line = Stations.all(world.content);
        return !line.isEmpty() && line.stream().noneMatch(b -> b.within(at, STATION_LONELY_DISTANCE));
    }

    private void discoverMonsterKind(GameServer server, GameCharacter character) {
        server.applyContent(WorldGenerator.newMonsterType(server.world), character);
        List<MonsterType> kinds = server.world.content.monsters;
        MonsterType kind = kinds.get(kinds.size() - 1);
        character.say(Event.MessageKind.NOTICE, "Something new is out there: " + kind.name + " (Lv. " + kind.level + ").");
        character.count(Stat.MONSTERS_DISCOVERED);
        server.tellOthers(character, Event.MessageKind.NOTICE, character.name + " ran into a new kind of monster: " + kind.name + ".");
        spawnMonsterNear(server, character, kind);
    }

    private void spawnMonsterNear(GameServer server, GameCharacter character, MonsterType kind) {
        Position at = freeSpot(server, character.position(), MONSTER_SPAWN_MIN_DISTANCE, MONSTER_SPAWN_MAX_DISTANCE, random);
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
        server.applyContent(batch, character);
        ContentBatch.SkillDef skill = batch.skills().get(0);
        character.say(Event.MessageKind.NOTICE, "You discovered a new skill: " + skill.name() + " (" + skill.type().name().toLowerCase() + ")!");
        character.count(Stat.SKILLS_DISCOVERED);
        server.tellOthers(character, Event.MessageKind.NOTICE, character.name + " discovered a new skill: " + skill.name() + ".");
        spawnStarterNode(server, character, server.world.content.skill(skill.id()), random);
    }

    /**
     * A tier-0 node of a brand new skill's first product line, near whoever found it, so every skill has at
     * least one node somewhere. Returns null if there was no safe spot nearby.
     */
    static Node spawnStarterNode(GameServer server, GameCharacter character, Skill skill, Random random) {
        if (skill.productLines.isEmpty()) {
            return null;
        }
        Recipe tier0 = skill.productLines.get(0).recipes.get(0);
        Position at = nodeSpot(server, character.position(), SPAWN_MIN_DISTANCE, SPAWN_MAX_DISTANCE, random);
        if (at == null) {
            return null;
        }
        ContentBatch batch = WorldGenerator.newNode(server.world, at, 1 + random.nextInt(3), 5 + random.nextInt(16),
                20 + random.nextInt(41), recipe -> recipe.id == tier0.id ? 1 : 0);
        server.applyContent(batch, character);
        Node node = server.world.content.node(batch.nodes().get(0).id());
        character.say(Event.MessageKind.NOTICE, "A " + skill.name + " node (" + node.label() + ") appeared nearby.");
        return node;
    }

    private void discoverNode(GameServer server, GameCharacter character) {
        Position at = nodeSpot(server, character.position(), SPAWN_MIN_DISTANCE, SPAWN_MAX_DISTANCE, random);
        if (at == null) {
            return;
        }
        int capacity = 1 + random.nextInt(3);
        int charges = 5 + random.nextInt(16);
        int respawnTicks = 20 + random.nextInt(41);
        ContentBatch batch = WorldGenerator.newNode(server.world, at, capacity, charges, respawnTicks,
                recipe -> recipe.levelMet(character.getLevel(recipe.skill)) ? UNLOCKED_WEIGHT : 1);
        server.applyContent(batch, character);
        Node node = server.world.content.node(batch.nodes().get(0).id());
        character.say(Event.MessageKind.NOTICE, "A " + node.skill().name + " node (" + node.label() + ") appeared nearby.");
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

    /** A random tile between min and max tiles away (either axis) with no node, monster or player on it, or null. */
    /**
     * Like {@link #freeSpot}, with nothing (no node, no building) on any of the 8 tiles round it -- so nodes can't
     * form walls or checkerboards, or crowd the bank -- and, to be sure, not walling anything in ({@link NodePlacement}).
     */
    static Position nodeSpot(GameServer server, Position around, int min, int max, Random random) {
        List<Position> spots = freeSpots(server, around, min, max);
        spots.removeIf(spot -> hasNeighbour(server, spot) || !NodePlacement.safe(p -> !server.passable(p), spot));
        return spots.isEmpty() ? null : spots.get(random.nextInt(spots.size()));
    }

    static boolean hasNeighbour(GameServer server, Position spot) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if ((dx != 0 || dy != 0) && server.world.content.builtOn(new Position(spot.x() + dx, spot.y() + dy))) {
                    return true;
                }
            }
        }
        return false;
    }

    static Position freeSpot(GameServer server, Position around, int min, int max, Random random) {
        List<Position> spots = freeSpots(server, around, min, max);
        return spots.isEmpty() ? null : spots.get(random.nextInt(spots.size()));
    }

    private static List<Position> freeSpots(GameServer server, Position around, int min, int max) {
        List<Position> spots = new ArrayList<>();
        for (int dx = -max; dx <= max; dx++) {
            for (int dy = -max; dy <= max; dy++) {
                if (Math.max(Math.abs(dx), Math.abs(dy)) < min) {
                    continue;
                }
                Position spot = new Position(around.x() + dx, around.y() + dy);
                if (!server.taken(spot)) {
                    spots.add(spot);
                }
            }
        }
        return spots;
    }
}
