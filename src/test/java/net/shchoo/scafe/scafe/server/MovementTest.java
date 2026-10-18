package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.client.GameLog;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.model.*;
import net.shchoo.scafe.scafe.protocol.Intent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MovementTest {
    private GameServer server;
    private Player alice;
    private final List<String> log = new ArrayList<>();

    @BeforeEach
    void setUp() {
        server = new GameServer(new World(1, "w", 5));
        server.setDiscovery(Discovery.off());
        GameLog.subscribe(log::add);
        alice = join("alice");
        alice.send(new Intent.Admin("1", List.of("1")));
        tick();
    }

    private Player join(String name) {
        Player player = new Player(new LocalConnection(server.connect()));
        player.send(new Intent.Join(name));
        server.tick();
        player.receive();
        return player;
    }

    private void tick(Player... players) {
        server.tick();
        for (Player player : players.length == 0 ? new Player[]{alice} : players) {
            player.receive();
        }
    }

    /** An unlocked node at each position, all in one batch. */
    private void nodesAt(Position... positions) {
        Recipe tier0 = server.world.content.skills.get(0).productLines.get(0).recipes.get(0);
        WorldContent scratch = server.world.content.copy();
        for (Position at : positions) {
            scratch.addNode(new Node(scratch.allocateId(), at, scratch.recipe(tier0.id), Node.UNLIMITED, Node.UNLIMITED, 0));
        }
        server.applyContent(scratch.diff(server.world.content));
        tick();
    }

    @Test
    void youStartFacingDown() {
        assertEquals(Direction.DOWN, alice.facing);
        assertEquals(new Position(0, 1), alice.ahead());
    }

    @Test
    void stepsTurnYouAndNodesBlockYou() {
        nodesAt(new Position(2, 0));
        alice.moveBy(1, 0);
        tick();
        assertEquals(new Position(1, 0), alice.position());
        assertEquals(Direction.RIGHT, alice.facing);

        alice.moveBy(1, 0);
        assertEquals(new Position(1, 0), alice.position(), "prediction knows the node blocks you");
        tick();
        assertEquals(new Position(1, 0), alice.serverPosition(), "the server agrees");
        assertEquals(new Position(1, 0), alice.position(), "no snap back");

        alice.moveBy(0, -1);
        tick();
        assertEquals(new Position(1, -1), alice.position());
        assertEquals(Direction.UP, alice.facing);
    }

    @Test
    void otherPlayersDontBlock() {
        Player bob = join("bob");
        bob.moveBy(1, 0);
        tick(alice, bob);
        alice.moveBy(1, 0);
        tick(alice, bob);
        assertEquals(bob.position(), alice.position());
    }

    @Test
    void youOnlyUseTheNodeYoureFacing() {
        // a node to the right (the one we want) and one above (just a wall); below is free and you face it
        nodesAt(new Position(1, 0), new Position(0, -1));
        alice.send(new Intent.Perform());
        tick();
        assertNull(alice.currentAction);
        assertTrue(log.contains("Nothing to do here..."), "facing down at nothing");

        alice.moveBy(1, 0);
        alice.send(new Intent.Perform());
        tick();
        assertEquals(Direction.RIGHT, alice.facing);
        assertNotNull(alice.currentAction, "turned to face the node, so q uses it");

        alice.moveBy(0, -1);
        tick();
        assertEquals(new Position(0, 0), alice.position(), "the node above blocks the step...");
        assertEquals(Direction.UP, alice.facing);
        assertNull(alice.currentAction, "...but turning away still interrupts");
        assertTrue(log.contains("Interrupted."));
    }

    @Test
    void buildingPutsTheNodeInFrontOfYou() {
        alice.moveBy(-1, 0);
        tick();
        alice.send(new Intent.Admin("3", List.of()));
        tick();
        assertEquals(new Position(-1, 0), alice.position());
        assertNotNull(alice.content.nodeAt(new Position(-2, 0)), "in front of you, facing left");
        assertNull(alice.content.nodeAt(alice.position()));
    }

    @Test
    void walkingToANodeEndsNextToItFacingIt() {
        nodesAt(new Position(3, 0));
        alice.send(new Intent.WalkTo(new Position(3, 0)));
        for (int i = 0; i < 4; i++) {
            tick();
        }
        assertEquals(new Position(2, 0), alice.position());
        assertEquals(Direction.RIGHT, alice.facing);
        alice.send(new Intent.Perform());
        tick();
        assertNotNull(alice.currentAction);
    }

    @Test
    void walkingGoesAroundNodesAndDiagonalsCountAsOneStep() {
        alice.send(new Intent.WalkTo(new Position(3, 3)));
        for (int i = 0; i < 3; i++) {
            tick();
        }
        assertEquals(new Position(3, 3), alice.position(), "3 diagonal steps, 3 ticks");

        // a wall from (5,1) to (5,5); walk from (3,3) to (7,3) has to go around it
        nodesAt(new Position(5, 1), new Position(5, 2), new Position(5, 3), new Position(5, 4), new Position(5, 5));
        alice.send(new Intent.WalkTo(new Position(7, 3)));
        for (int i = 0; i < 20 && !alice.position().equals(new Position(7, 3)); i++) {
            tick();
            assertNull(alice.content.nodeAt(alice.position()), "walked through a node at " + alice.position());
        }
        assertEquals(new Position(7, 3), alice.position());
    }

    @Test
    void pathsNeverSqueezeBetweenTwoNodes() {
        Set<Position> blocked = Set.of(new Position(1, 0), new Position(0, 1));
        Deque<Position> path = Pathfinding.path(new Position(0, 0), new Position(1, 1), p -> !blocked.contains(p));
        assertFalse(path.isEmpty(), "there is a way round");
        assertNotEquals(new Position(1, 1), path.peekFirst(), "but not through the corner between the two nodes");
        Position at = new Position(0, 0);
        for (Position next : path) {
            assertTrue(Pathfinding.canStep(at, next, p -> !blocked.contains(p)), at + " -> " + next);
            at = next;
        }
    }

    @Test
    void pathsComeOutStraight() {
        Deque<Position> path = Pathfinding.path(new Position(0, 0), new Position(0, 4), p -> true);
        assertEquals(List.of(new Position(0, 1), new Position(0, 2), new Position(0, 3), new Position(0, 4)), List.copyOf(path));
    }

    @Test
    void unreachablePlacesSaySo() {
        nodesAt(new Position(9, 9), new Position(10, 9), new Position(11, 9), new Position(9, 10), new Position(11, 10),
                new Position(9, 11), new Position(10, 11), new Position(11, 11));
        alice.send(new Intent.WalkTo(new Position(10, 10)));
        tick();
        assertTrue(log.contains("Can't find a way there."));
        assertEquals(new Position(0, 0), alice.position());
    }

    @Test
    void runningCoversTwoTilesPerTick() {
        alice.toggleRunning();
        tick();
        assertTrue(alice.running);
        alice.moveBy(1, 0);
        alice.moveBy(1, 0);
        tick();
        assertEquals(new Position(2, 0), alice.serverPosition(), "both queued steps in one tick");
        assertEquals(new Position(2, 0), alice.position());

        alice.send(new Intent.WalkTo(new Position(2, 6)));
        for (int i = 0; i < 3; i++) {
            tick();
        }
        assertEquals(new Position(2, 6), alice.position(), "6 tiles in 3 ticks");
    }

    @Test
    void colorsAreSeenByOthersAndValidated() {
        Player bob = join("bob");
        tick(alice, bob);
        alice.setColor(3);
        tick(alice, bob);
        assertEquals(3, alice.colorIndex);
        assertEquals(3, bob.others.get(alice.id).colorIndex());

        alice.send(new Intent.SetColor(99));
        tick(alice, bob);
        assertEquals(3, alice.colorIndex, "out of range is ignored");
        alice.setColor(-1);
        tick(alice, bob);
        assertEquals(Intent.PLAYER_COLORS - 1, alice.colorIndex, "the client wraps around");
    }
}
