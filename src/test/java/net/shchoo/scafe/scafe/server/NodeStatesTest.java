package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.client.GameLog;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.Recipe;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.model.WorldContent;
import net.shchoo.scafe.scafe.protocol.Intent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NodeStatesTest {
    private GameServer server;
    private final List<String> log = new ArrayList<>();
    private Player alice;
    private Player bob;

    @BeforeEach
    void setUp() {
        server = new GameServer(new World(1, "test", 99));
        server.setDiscovery(Discovery.off());
        GameLog.subscribe(log::add);
        alice = connect("alice");
        bob = connect("bob");
        alice.send(new Intent.Admin("1", List.of("1")));
        tick();
    }

    private Player connect(String name) {
        Player player = new Player(new LocalConnection(server.connect()));
        player.send(new Intent.Join(name));
        return player;
    }

    private void tick() {
        server.tick();
        alice.receive();
        bob.receive();
    }

    private Node placeNode(int capacity, int charges, int respawnTicks) {
        Recipe tier0 = server.world.content.skills.get(0).productLines.get(0).recipes.get(0);
        WorldContent scratch = server.world.content.copy();
        Node node = new Node(scratch.allocateId(), new Position(0, 1), scratch.recipe(tier0.id), capacity, charges, respawnTicks);
        scratch.addNode(node);
        server.applyContent(scratch.diff(server.world.content));
        tick();
        return alice.content.node(node.id);
    }

    @Test
    void capacityLimitsConcurrentUsers() {
        placeNode(1, Node.UNLIMITED, 0);
        alice.send(new Intent.Perform());
        tick();
        bob.send(new Intent.Perform());
        tick();
        assertNotNull(alice.currentAction);
        assertNull(bob.currentAction);
        assertTrue(log.contains("Someone else is using that (1/1)."));

        alice.send(new Intent.Perform());
        tick();
        bob.send(new Intent.Perform());
        tick();
        assertNull(alice.currentAction);
        assertNotNull(bob.currentAction);
    }

    @Test
    void nodesRunOutAndRespawn() {
        Node node = placeNode(Node.UNLIMITED, 2, 3);
        alice.send(new Intent.Perform());
        for (int i = 0; i < 5; i++) {
            tick();
        }
        assertNull(alice.currentAction);
        assertEquals(2, alice.inventory.get(node.recipe.product));
        assertTrue(alice.status(node).depleted());
        assertTrue(bob.status(node).depleted());
        assertTrue(log.stream().anyMatch(m -> m.endsWith("ran out.")));

        alice.send(new Intent.Perform());
        tick();
        assertNull(alice.currentAction);
        assertTrue(log.stream().anyMatch(m -> m.startsWith("It's empty")));

        tick();
        tick();
        assertFalse(alice.status(node).depleted());
        assertEquals(2, alice.status(node).charges());
        alice.send(new Intent.Perform());
        tick();
        assertNotNull(alice.currentAction);
    }

    @Test
    void runningOutStopsEveryoneUsingIt() {
        Node node = placeNode(Node.UNLIMITED, 1, 10);
        alice.send(new Intent.Perform());
        bob.send(new Intent.Perform());
        tick();
        assertEquals(2, alice.status(node).users());
        tick();
        tick();
        assertNull(alice.currentAction);
        assertNull(bob.currentAction);
        assertEquals(0, alice.status(node).users());
        int made = alice.inventory.getOrDefault(node.recipe.product, 0) + bob.inventory.getOrDefault(node.recipe.product, 0);
        assertEquals(1, made);
    }

    @Test
    void lateJoinersSeeNodeState() {
        Node node = placeNode(Node.UNLIMITED, 1, 10);
        alice.send(new Intent.Perform());
        tick();
        tick();
        tick();
        Player carol = connect("carol");
        server.tick();
        carol.receive();
        assertTrue(carol.status(carol.content.node(node.id)).depleted());
    }
}
