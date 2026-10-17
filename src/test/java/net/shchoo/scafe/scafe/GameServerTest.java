package net.shchoo.scafe.scafe;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GameServerTest {
    private GameServer server;
    private final List<String> log = new ArrayList<>();

    @BeforeEach
    void setUp() {
        server = new GameServer(new World(1, "test", 1234));
        server.setDiscovery(Discovery.off());
        GameLog.subscribe(log::add);
    }

    private Player join(String name) {
        Player player = new Player(new LocalConnection(server.connect()));
        player.send(new Intent.Join(name));
        tick(player);
        return player;
    }

    private void tick(Player... players) {
        server.tick();
        for (Player player : players) {
            player.receive();
        }
    }

    @Test
    void joiningGivesYouTheWorldAndTellsOthers() {
        Player alice = join("alice");
        alice.send(new Intent.Admin("1", List.of("3")));
        tick(alice);
        assertEquals(3, alice.content.skills.size());

        Player bob = join("bob");
        tick(alice, bob);
        assertTrue(bob.joined);
        assertEquals(alice.content.version(), bob.content.version());
        assertEquals(3, bob.content.skills.size());
        assertTrue(alice.others.containsKey(bob.id));
        assertTrue(bob.others.containsKey(alice.id));

        bob.send(new Intent.Leave());
        tick(alice);
        assertFalse(alice.others.containsKey(bob.id));
    }

    @Test
    void youOnlyHearAboutPlayersInRange() {
        Player alice = join("alice");
        Player bob = join("bob");
        tick(alice, bob);
        assertTrue(alice.others.containsKey(bob.id));

        int far = GameServer.VIEW_RANGE + 1;
        bob.send(new Intent.WalkTo(new Board.Position(far, 0)));
        for (int i = 0; i < far; i++) {
            tick(alice, bob);
        }
        assertFalse(alice.others.containsKey(bob.id));
        assertFalse(bob.others.containsKey(alice.id));

        Player carol = join("carol");
        tick(alice, bob, carol);
        assertTrue(carol.others.containsKey(alice.id));
        assertFalse(carol.others.containsKey(bob.id));

        bob.send(new Intent.WalkTo(new Board.Position(far - 1, 0)));
        tick(alice, bob, carol);
        assertEquals(new Board.Position(far - 1, 0), alice.others.get(bob.id).position());
    }

    @Test
    void othersSeeYourTotalLevel() {
        Player alice = join("alice");
        Player bob = join("bob");
        alice.send(new Intent.Admin("1", List.of("2")));
        tick(alice, bob);
        assertEquals(2, alice.others.get(bob.id).totalLevel(alice.content));

        Recipe tier0 = bob.content.skills.get(0).productLines.get(0).recipes.get(0);
        placeNodeFor(tier0);
        bob.send(new Intent.Perform());
        for (int i = 0; i < 20 && bob.getLevel(tier0.skill) < 2; i++) {
            tick(alice, bob);
        }
        assertEquals(2, bob.getLevel(tier0.skill));
        tick(alice, bob);
        assertEquals(3, alice.others.get(bob.id).totalLevel(alice.content));
        assertEquals(List.of(bob.id), alice.othersAt(new Board.Position(0, 0)).stream().map(Event.EntityInfo::id).toList());

        alice.send(new Intent.Admin("1", List.of("1")));
        tick(alice, bob);
        assertEquals(4, alice.others.get(bob.id).totalLevel(alice.content));
    }

    @Test
    void newContentReachesEveryoneAsOneBatch() {
        Player alice = join("alice");
        Player bob = join("bob");
        alice.send(new Intent.Admin("1", List.of("4")));
        tick(alice, bob);
        assertEquals(server.world.content.version(), alice.content.version());
        assertEquals(server.world.content.version(), bob.content.version());
        assertEquals(4, bob.content.skills.size());
        assertEquals(server.world.content.history(), alice.content.history());
    }

    @Test
    void movementHappensOnTheTickAndOthersSeeIt() {
        Player alice = join("alice");
        Player bob = join("bob");
        alice.moveBy(1, 0);
        assertEquals(1, alice.x, "predicted right away");
        assertEquals(new Board.Position(0, 0), alice.serverPosition());
        assertFalse(bob.others.get(alice.id).position().x() == 1, "others only see it on the tick");
        tick(alice, bob);
        assertEquals(1, alice.x);
        assertEquals(new Board.Position(1, 0), alice.serverPosition());
        assertEquals(new Board.Position(1, 0), bob.others.get(alice.id).position());

        alice.send(new Intent.WalkTo(new Board.Position(1, 3)));
        for (int i = 0; i < 3; i++) {
            tick(alice, bob);
        }
        assertEquals(new Board.Position(1, 3), alice.position());
    }

    @Test
    void predictionNeverGetsMoreThanTwoStepsAhead() {
        Player alice = join("alice");
        alice.moveBy(1, 0);
        alice.moveBy(1, 0);
        alice.moveBy(1, 0);
        assertEquals(2, alice.x);
        tick(alice);
        assertEquals(new Board.Position(1, 0), alice.serverPosition());
        assertEquals(2, alice.x);
        tick(alice);
        assertEquals(new Board.Position(2, 0), alice.serverPosition());
        assertEquals(2, alice.x);
        tick(alice);
        assertEquals(new Board.Position(2, 0), alice.position());
    }

    @Test
    void predictionSnapsBackWhenTheServerDropsYourSteps() {
        Player alice = join("alice");
        alice.moveBy(1, 0);
        alice.moveBy(1, 0);
        alice.send(new Intent.WalkTo(new Board.Position(0, 3)));
        assertEquals(2, alice.x);
        tick(alice);
        assertEquals(new Board.Position(0, 1), alice.position());
        tick(alice);
        tick(alice);
        assertEquals(new Board.Position(0, 3), alice.position());
    }

    @Test
    void stepThenPerformActsOnTheNewTile() {
        Player alice = join("alice");
        alice.send(new Intent.Admin("1", List.of("1")));
        tick(alice);
        Recipe tier0 = alice.content.skills.get(0).productLines.get(0).recipes.get(0);
        WorldContent scratch = server.world.content.copy();
        scratch.addNode(new Node(scratch.allocateId(), new Board.Position(1, 0), scratch.recipe(tier0.id), Node.UNLIMITED, Node.UNLIMITED, 0));
        server.applyContent(scratch.diff(server.world.content));
        tick(alice);

        alice.moveBy(1, 0);
        alice.send(new Intent.Perform());
        tick(alice);
        assertEquals(new Board.Position(1, 0), alice.serverPosition());
        assertNotNull(alice.currentAction);
        tick(alice);
        tick(alice);
        assertEquals(1, alice.inventory.get(tier0.product));
    }

    @Test
    void actionsRepeatAndReportXpAndItems() {
        Player alice = join("alice");
        alice.send(new Intent.Admin("1", List.of("1")));
        tick(alice);
        Skill skill = alice.content.skills.get(0);
        Recipe tier0 = skill.productLines.get(0).recipes.get(0);
        placeNodeFor(tier0);

        alice.send(new Intent.Perform());
        tick(alice);
        assertNotNull(alice.currentAction);
        assertEquals(0.0, alice.actionFraction(), 0.6);

        tick(alice);
        assertEquals(0L, alice.getExperience(skill));
        tick(alice);
        assertEquals((long) tier0.expGained, alice.getExperience(skill));
        assertEquals(1, alice.inventory.get(tier0.product));
        assertEquals(new Board.Position(0, 0), alice.lastActionAt);

        tick(alice);
        tick(alice);
        assertEquals(2, alice.inventory.get(tier0.product));

        alice.send(new Intent.Perform());
        tick(alice);
        assertNull(alice.currentAction);
        assertTrue(log.contains("Stopped."));
    }

    @Test
    void movingInterruptsAnAction() {
        Player alice = join("alice");
        alice.send(new Intent.Admin("1", List.of("1")));
        tick(alice);
        placeNodeFor(alice.content.skills.get(0).productLines.get(0).recipes.get(0));
        alice.send(new Intent.Perform());
        tick(alice);
        alice.moveBy(0, 1);
        tick(alice);
        assertNull(alice.currentAction);
        assertTrue(log.contains("Interrupted."));
    }

    @Test
    void lockedNodesCanBecomeGoals() {
        Player alice = join("alice");
        alice.send(new Intent.Admin("1", List.of("1")));
        tick(alice);
        List<Recipe> recipes = alice.content.skills.get(0).productLines.get(0).recipes;
        Recipe locked = recipes.get(recipes.size() - 1);
        Node node = placeNodeFor(locked);
        tick(alice);
        Node mirrored = alice.content.node(node.id);

        alice.send(new Intent.AddGoal());
        tick(alice);
        assertTrue(log.contains("Try it first (q)."));
        assertTrue(alice.goals.isEmpty());

        alice.send(new Intent.Perform());
        tick(alice);
        assertTrue(alice.hasTried(mirrored));
        assertNull(alice.currentAction);

        alice.send(new Intent.AddGoal());
        alice.send(new Intent.AddGoal());
        tick(alice);
        assertEquals(1, alice.goals.size());
        assertEquals(locked.levelNeeded, alice.goals.get(0).level());
        assertTrue(log.contains("Already a goal."));
    }

    @Test
    void badAdminCommandsAreReportedNotThrown() {
        Player alice = join("alice");
        alice.send(new Intent.Admin("2", List.of()));
        alice.send(new Intent.Admin("1", List.of("abc")));
        tick(alice);
        assertTrue(log.stream().anyMatch(m -> m.startsWith("Couldn't run 2")));
        assertTrue(log.stream().anyMatch(m -> m.startsWith("Couldn't run 1")));
    }

    /** Puts a node for {@code recipe} at the origin, as one content batch like everything else. */
    private Node placeNodeFor(Recipe recipe) {
        WorldContent scratch = server.world.content.copy();
        Node node = new Node(scratch.allocateId(), new Board.Position(0, 0), scratch.recipe(recipe.id), Node.UNLIMITED, Node.UNLIMITED, 0);
        scratch.addNode(node);
        server.applyContent(scratch.diff(server.world.content));
        return node;
    }
}
