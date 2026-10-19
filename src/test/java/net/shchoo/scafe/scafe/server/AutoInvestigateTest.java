package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.generation.WorldGenerator;
import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.protocol.Intent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AutoInvestigateTest {
    private GameServer server;
    private Player player;

    @BeforeEach
    void setUp() {
        World world = new World(1, "w", 1);
        world.content.apply(WorldGenerator.newSkills(world, 1));
        server = new GameServer(world);
        server.setDiscovery(Discovery.off());
        player = new Player(new LocalConnection(server.connect()));
        player.send(new Intent.Join("alice"));
        tick();
    }

    private void tick() {
        server.tick();
        player.receive();
    }

    private void nodeAt(Position at) {
        server.applyContent(WorldGenerator.newNode(server.world, at, Node.UNLIMITED, Node.UNLIMITED, 0));
    }

    private void walkFar() {
        player.send(new Intent.WalkTo(new Position(30, 0)));
        tick();
        tick();
    }

    @Test
    void onTheMoveYouHeadForANewNodeNearby() {
        player.toggleAutoInvestigate();
        tick();
        assertTrue(player.autoInvestigate, "confirmed by the server");
        walkFar();
        assertEquals(new Position(2, 0), new Position(player.x, player.y));

        nodeAt(new Position(2, 5));
        for (int i = 0; i < 10; i++) {
            tick();
        }
        assertEquals(new Position(2, 4), new Position(player.x, player.y), "went over and stopped next to it");
        assertEquals(new Position(2, 5), player.ahead(), "facing it");
    }

    @Test
    void standingStillOrSwitchedOffYouStayPut() {
        player.toggleAutoInvestigate();
        tick();
        nodeAt(new Position(0, 4));
        for (int i = 0; i < 6; i++) {
            tick();
        }
        assertEquals(new Position(0, 0), new Position(player.x, player.y), "not moving = not interrupted");

        player.toggleAutoInvestigate();
        tick();
        walkFar();
        nodeAt(new Position(3, 4));
        for (int i = 0; i < 6; i++) {
            tick();
        }
        assertEquals(0, player.y, "setting off: kept walking");
        assertTrue(player.x > 3);
    }

    @Test
    void farAwayNodesDontCount() {
        player.toggleAutoInvestigate();
        tick();
        walkFar();
        nodeAt(new Position(2, GameServer.AUTO_INVESTIGATE_RANGE + 1));
        tick();
        tick();
        assertEquals(0, player.y);
    }
}
