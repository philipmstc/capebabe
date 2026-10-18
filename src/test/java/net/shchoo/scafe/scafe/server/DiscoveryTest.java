package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.client.GameLog;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.generation.WorldGenerator;
import net.shchoo.scafe.scafe.model.ContentBatch;
import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.protocol.Event;
import net.shchoo.scafe.scafe.protocol.Intent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class DiscoveryTest {
    private final List<String> log = new ArrayList<>();

    /** Every roll succeeds. */
    private static class Lucky extends Random {
        @Override
        public double nextDouble() {
            return 0.0;
        }
    }

    /** No roll ever succeeds. */
    private static class Unlucky extends Random {
        @Override
        public double nextDouble() {
            return 0.999999;
        }
    }

    @BeforeEach
    void setUp() {
        GameLog.subscribe(log::add);
    }

    private static Player join(GameServer server, String name) {
        Player player = new Player(new LocalConnection(server.connect()));
        player.send(new Intent.Join(name));
        server.tick();
        player.receive();
        return player;
    }

    private static void walk(GameServer server, Player player, int steps, Player... others) {
        for (int i = 0; i < steps; i++) {
            player.moveBy(1, 0);
            server.tick();
            player.receive();
            for (Player other : others) {
                other.receive();
            }
        }
    }

    @Test
    void worldsStartEmpty() {
        World world = new World(1, "w", 1);
        assertTrue(world.content.skills.isEmpty());
        assertTrue(world.content.nodes().isEmpty());
    }

    @Test
    void nothingHappensWithoutLuck() {
        GameServer server = new GameServer(new World(1, "w", 1));
        server.setDiscovery(new Discovery(new Unlucky()));
        Player alice = join(server, "alice");
        walk(server, alice, 30);
        assertTrue(alice.content.skills.isEmpty());
    }

    @Test
    void walkingCanDiscoverTheFirstSkillAndANodeNearby() {
        GameServer server = new GameServer(new World(1, "w", 1));
        server.setDiscovery(new Discovery(new Lucky()));
        Player alice = join(server, "alice");
        walk(server, alice, 1);
        assertEquals(1, alice.content.craftingSkills().size());
        assertTrue(log.stream().anyMatch(m -> m.startsWith("You discovered a new skill: skill0")));
        assertNotNull(alice.content.combatSkill(), "with every roll lucky, a monster kind (and combat) shows up too");
        assertTrue(alice.others.values().stream().anyMatch(Event.EntityInfo::isMonster));
        assertEquals(1, alice.content.nodes().size());
        Node node = alice.content.nodes().iterator().next();
        int distance = Math.max(Math.abs(node.position.x() - alice.x), Math.abs(node.position.y() - alice.y));
        assertTrue(distance >= Discovery.SPAWN_MIN_DISTANCE && distance <= Discovery.SPAWN_MAX_DISTANCE, "distance " + distance);
        assertTrue(log.stream().anyMatch(m -> m.contains("node") && m.endsWith("appeared nearby.")));
    }

    @Test
    void othersHearAboutNewSkills() {
        GameServer server = new GameServer(new World(1, "w", 1));
        server.setDiscovery(new Discovery(new Unlucky()));
        Player alice = join(server, "alice");
        Player bob = join(server, "bob");
        server.setDiscovery(new Discovery(new Lucky()));
        walk(server, alice, 1, bob);
        assertTrue(log.contains("alice discovered a new skill: skill0."));
        assertEquals(1, bob.content.craftingSkills().size());
    }

    @Test
    void chancesBackOffExponentially() {
        assertEquals(0.5, Discovery.chance(0.5, 0.5, 0), 1e-9);
        assertEquals(0.0625, Discovery.chance(0.5, 0.5, 3), 1e-9);
        assertTrue(Discovery.chance(Discovery.Trigger.MOVED.skillChance, Discovery.SKILL_BACKOFF, 10)
                < Discovery.Trigger.MOVED.skillChance / 100);
    }

    @Test
    void aLongWalkGrowsTheWorldWithoutExploding() {
        GameServer server = new GameServer(new World(1, "w", 7));
        server.setDiscovery(new Discovery(new Random(42)));
        Player alice = join(server, "alice");
        walk(server, alice, 400);
        int skills = alice.content.skills.size();
        int nodes = alice.content.nodes().size();
        assertTrue(skills >= 1 && skills <= 12, "skills " + skills);
        assertTrue(nodes >= 10, "nodes " + nodes);
        int crowded = Discovery.nodesNear(server.world, alice.position());
        assertTrue(crowded <= 8, "nodes near the end of the walk " + crowded);
    }

    @Test
    void discoveredNodesLeanTowardsRecipesYouCanDo() {
        World world = new World(1, "w", 3);
        world.content.apply(WorldGenerator.newSkills(world, 4));
        GameCharacter fresh = new GameCharacter(1, "fresh");
        int unlocked = 0;
        int total = 200;
        for (int i = 0; i < total; i++) {
            ContentBatch batch = WorldGenerator.newNode(world, new Position(i, 0), 1, 0, 0,
                    recipe -> recipe.levelMet(fresh.getLevel(recipe.skill)) ? Discovery.UNLOCKED_WEIGHT : 1);
            world.content.apply(batch);
            Node node = world.content.node(batch.nodes().get(0).id());
            if (!fresh.isLockedFor(node)) {
                unlocked++;
            }
        }
        assertTrue(unlocked > total / 3, "unlocked " + unlocked + "/" + total);
    }
}
