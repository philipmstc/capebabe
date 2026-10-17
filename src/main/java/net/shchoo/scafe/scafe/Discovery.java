package net.shchoo.scafe.scafe;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Grows the world from what players do. Every step, every item made and every level up rolls a chance to
 * discover a new skill or a new node nearby. The chances back off exponentially: skills by how many the world
 * already has, nodes by how many are already near you (so exploring keeps finding things).
 */
public class Discovery {

    public enum Trigger {
        //    skill chance, node chance (before backoff)
        MOVED(0.03, 0.10),
        MADE(0.08, 0.05),
        LEVELLED(0.50, 0.30);

        final double skillChance;
        final double nodeChance;

        Trigger(double skillChance, double nodeChance) {
            this.skillChance = skillChance;
            this.nodeChance = nodeChance;
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
        if (random.nextDouble() < chance(trigger.skillChance, SKILL_BACKOFF, world.content.skills.size())) {
            discoverSkill(server, character);
        }
        if (!world.content.skills.isEmpty()
                && random.nextDouble() < chance(trigger.nodeChance, NODE_BACKOFF, nodesNear(world, character.position()))) {
            discoverNode(server, character);
        }
    }

    private void discoverSkill(GameServer server, GameCharacter character) {
        ContentBatch batch = WorldGenerator.newSkills(server.world, 1);
        server.applyContent(batch);
        ContentBatch.SkillDef skill = batch.skills().get(0);
        character.say("You discovered a new skill: " + skill.name() + " (" + skill.type().name().toLowerCase() + ")!");
        server.tellOthers(character, character.name + " discovered a new skill: " + skill.name() + ".");
    }

    private void discoverNode(GameServer server, GameCharacter character) {
        Board.Position at = freeSpotNear(server.world, character.position());
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

    static int nodesNear(World world, Board.Position around) {
        int count = 0;
        for (Node node : world.content.nodes()) {
            if (Math.abs(node.position.x() - around.x()) <= NODE_CROWD_RADIUS
                    && Math.abs(node.position.y() - around.y()) <= NODE_CROWD_RADIUS) {
                count++;
            }
        }
        return count;
    }

    private Board.Position freeSpotNear(World world, Board.Position around) {
        List<Board.Position> spots = new ArrayList<>();
        for (int dx = -SPAWN_MAX_DISTANCE; dx <= SPAWN_MAX_DISTANCE; dx++) {
            for (int dy = -SPAWN_MAX_DISTANCE; dy <= SPAWN_MAX_DISTANCE; dy++) {
                if (Math.max(Math.abs(dx), Math.abs(dy)) < SPAWN_MIN_DISTANCE) {
                    continue;
                }
                Board.Position spot = new Board.Position(around.x() + dx, around.y() + dy);
                if (world.content.nodeAt(spot) == null) {
                    spots.add(spot);
                }
            }
        }
        return spots.isEmpty() ? null : spots.get(random.nextInt(spots.size()));
    }
}
