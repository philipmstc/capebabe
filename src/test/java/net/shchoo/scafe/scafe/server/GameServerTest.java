package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.model.Stat;
import net.shchoo.scafe.scafe.client.GameLog;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.model.ContentBatch;
import net.shchoo.scafe.scafe.model.Direction;
import net.shchoo.scafe.scafe.model.Levels;
import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.Recipe;
import net.shchoo.scafe.scafe.model.Skill;
import net.shchoo.scafe.scafe.model.Tiers;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.model.WorldContent;
import net.shchoo.scafe.scafe.protocol.Event;
import net.shchoo.scafe.scafe.protocol.Intent;

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
    void newContentRemembersWhoseActionMadeItAndWhen() {
        Player alice = join("alice");
        long before = System.currentTimeMillis();
        alice.send(new Intent.Admin("1", List.of("1")));
        tick(alice);
        long after = System.currentTimeMillis();
        ContentBatch.Origin origin = alice.content.origin(alice.content.skills.get(0).id);
        assertEquals("alice", origin.by());
        assertTrue(origin.at() >= before && origin.at() <= after);

        Player bob = join("bob");
        assertEquals(origin, bob.content.origin(bob.content.skills.get(0).id), "players joining later see it too");
    }

    @Test
    void adminFiveGivesExperienceBySkillNameOrId() {
        Player alice = join("alice");
        alice.send(new Intent.Admin("1", List.of("1")));
        tick(alice);
        Skill skill = alice.content.skills.get(0);

        alice.send(new Intent.Admin("5", List.of(skill.name)));
        tick(alice);
        assertEquals(10_000, alice.getExperience(skill));
        alice.send(new Intent.Admin("5", List.of(String.valueOf(skill.id), "500")));
        tick(alice);
        assertEquals(10_500, alice.getExperience(skill));

        alice.send(new Intent.Admin("5", List.of(skill.name, "-50")));
        alice.send(new Intent.Admin("5", List.of("nope")));
        alice.send(new Intent.Admin("5", List.of()));
        tick(alice);
        assertEquals(10_500, alice.getExperience(skill));
        assertTrue(log.contains("Experience has to be more than 0."));
        assertTrue(log.contains("No skill called nope."));
        assertTrue(log.contains("Usage: /5 <skill name or id> [amount]"));
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
        bob.send(new Intent.WalkTo(new Position(far, 0)));
        for (int i = 0; i < far; i++) {
            tick(alice, bob);
        }
        assertFalse(alice.others.containsKey(bob.id));
        assertFalse(bob.others.containsKey(alice.id));

        Player carol = join("carol");
        tick(alice, bob, carol);
        assertTrue(carol.others.containsKey(alice.id));
        assertFalse(carol.others.containsKey(bob.id));

        bob.send(new Intent.WalkTo(new Position(far - 1, 0)));
        tick(alice, bob, carol);
        assertEquals(new Position(far - 1, 0), alice.others.get(bob.id).position());
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
        assertEquals(List.of(bob.id), alice.othersAt(new Position(0, 0)).stream().map(Event.EntityInfo::id).toList());

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
        assertEquals(new Position(0, 0), alice.serverPosition());
        assertFalse(bob.others.get(alice.id).position().x() == 1, "others only see it on the tick");
        tick(alice, bob);
        assertEquals(1, alice.x);
        assertEquals(new Position(1, 0), alice.serverPosition());
        assertEquals(new Position(1, 0), bob.others.get(alice.id).position());

        alice.send(new Intent.WalkTo(new Position(1, 3)));
        for (int i = 0; i < 3; i++) {
            tick(alice, bob);
        }
        assertEquals(new Position(1, 3), alice.position());
    }

    @Test
    void predictionNeverGetsMoreThanTwoStepsAhead() {
        Player alice = join("alice");
        alice.moveBy(1, 0);
        alice.moveBy(1, 0);
        alice.moveBy(1, 0);
        assertEquals(2, alice.x);
        tick(alice);
        assertEquals(new Position(1, 0), alice.serverPosition());
        assertEquals(2, alice.x);
        tick(alice);
        assertEquals(new Position(2, 0), alice.serverPosition());
        assertEquals(2, alice.x);
        tick(alice);
        assertEquals(new Position(2, 0), alice.position());
    }

    @Test
    void predictionSnapsBackWhenTheServerDropsYourSteps() {
        Player alice = join("alice");
        alice.moveBy(1, 0);
        alice.moveBy(1, 0);
        alice.send(new Intent.WalkTo(new Position(0, 3)));
        assertEquals(2, alice.x);
        tick(alice);
        assertEquals(new Position(0, 1), alice.position());
        tick(alice);
        tick(alice);
        assertEquals(new Position(0, 3), alice.position());
    }

    @Test
    void walkingIntoANodeTurnsYouToFaceItAndQUsesIt() {
        Player alice = join("alice");
        alice.send(new Intent.Admin("1", List.of("1")));
        tick(alice);
        Recipe tier0 = alice.content.skills.get(0).productLines.get(0).recipes.get(0);
        WorldContent scratch = server.world.content.copy();
        scratch.addNode(new Node(scratch.allocateId(), new Position(1, 0), scratch.recipe(tier0.id), Node.UNLIMITED, Node.UNLIMITED, 0));
        server.applyContent(scratch.diff(server.world.content));
        tick(alice);

        alice.moveBy(1, 0);
        assertEquals(new Position(0, 0), alice.position(), "predicted: the node blocks the step");
        assertEquals(Direction.RIGHT, alice.facing, "...but you turn to face it");
        alice.send(new Intent.Perform());
        tick(alice);
        assertEquals(new Position(0, 0), alice.serverPosition());
        assertEquals(Direction.RIGHT, alice.facing);
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
        assertEquals(new Position(0, 0), alice.lastActionAt);

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
        alice.moveBy(1, 0);
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

        // the steps are the goal's skill's (by its type): work them out rather than guess the type
        Skill skill = alice.goals.get(0).skill();
        List<Integer> steps = java.util.Arrays.stream(alice.goals.get(0).levels()).boxed().toList();
        int at = steps.indexOf(locked.levelNeeded);
        alice.stepGoalLevel(skill, 1);
        tick(alice);
        assertEquals(steps.get(at + 1), alice.goals.get(0).level(), "up to the next milestone");
        for (int i = 0; i < steps.size(); i++) {
            alice.stepGoalLevel(skill, 1);
            tick(alice);
        }
        assertEquals(Levels.MAX_LEVEL, alice.goals.get(0).level(), "up to the max, and nowhere past it");
        int belowMax = steps.get(steps.size() - 2);
        alice.stepGoalLevel(skill, -1);
        alice.stepGoalLevel(skill, -1);
        tick(alice);
        assertEquals(belowMax, alice.goals.get(0).level(), "both steps were worked out from 100 before the server answered");

        alice.send(new Intent.SetGoal(skill.id, 42));
        alice.send(new Intent.SetGoal(-5, 20));
        tick(alice);
        assertEquals(belowMax, alice.goals.get(0).level(), "only the goal levels, only your goals");

        int below = steps.get(steps.size() - 3);
        alice.send(new Intent.Admin("5", List.of(skill.name, String.valueOf(Skill.XP_PER_LEVEL[below - 1]))));
        tick(alice);
        assertEquals(below, alice.getLevel(skill));
        int stepBack = steps.get(steps.size() - 2);
        alice.send(new Intent.Admin("5", List.of(skill.name, String.valueOf(Skill.XP_PER_LEVEL[stepBack - 1]))));
        tick(alice);
        alice.stepGoalLevel(skill, -1);
        tick(alice);
        assertEquals(belowMax, alice.goals.get(0).level(), stepBack + " is your level already: Left does nothing");
        alice.send(new Intent.SetGoal(skill.id, below));
        tick(alice);
        assertEquals(belowMax, alice.goals.get(0).level(), "and the server won't take it either");
        assertTrue(log.contains("You're already past Lv. " + below + "."));
    }

    @Test
    void oneGoalASkill_gAimsForTheNextMilestone_andZeroClearsIt() {
        Player alice = join("alice");
        alice.send(new Intent.Admin("1", List.of("2")));
        tick(alice);
        Skill skill = alice.content.craftingSkills().get(0);
        int[] steps = Tiers.milestones(skill.type);
        alice.goalForNextMilestone(skill);
        tick(alice);
        assertEquals(1, alice.goals.size());
        assertEquals(steps[0], alice.goal(skill).level(), "the first milestone past Lv. 1");
        assertTrue(log.contains("New goal: " + alice.goal(skill).describe()));
        alice.stepGoalLevel(skill, 1);
        tick(alice);
        assertEquals(1, alice.goals.size(), "moved, not another");
        assertEquals(steps[1], alice.goal(skill).level());

        Skill other = alice.content.craftingSkills().get(1);
        alice.goalForNextMilestone(other);
        tick(alice);
        assertEquals(2, alice.goals.size(), "one each");

        alice.send(new Intent.Admin("5", List.of(skill.name, String.valueOf(Skill.XP_PER_LEVEL[steps[2] - 1]))));
        tick(alice);
        alice.goalForNextMilestone(skill);
        tick(alice);
        assertEquals(steps[3], alice.goal(skill).level(), "met: g aims past where you are now");

        alice.clearGoal(skill);
        tick(alice);
        assertNull(alice.goal(skill));
        assertEquals(1, alice.goals.size());
        assertEquals(1, server.online(alice.id).goals.size(),
                "the server's too");
    }

    @Test
    void badAdminCommandsAreReportedNotThrown() {
        Player alice = join("alice");
        alice.send(new Intent.Admin("2", List.of("lots")));
        alice.send(new Intent.Admin("1", List.of("abc")));
        tick(alice);
        assertTrue(log.stream().anyMatch(m -> m.startsWith("Couldn't run 2")));
        assertTrue(log.stream().anyMatch(m -> m.startsWith("Couldn't run 1")));
    }

    /** Puts a node for {@code recipe} just below the origin -- in front of a fresh character, who faces down. */
    private Node placeNodeFor(Recipe recipe) {
        WorldContent scratch = server.world.content.copy();
        Node node = new Node(scratch.allocateId(), new Position(0, 1), scratch.recipe(recipe.id), Node.UNLIMITED, Node.UNLIMITED, 0);
        scratch.addNode(node);
        server.applyContent(scratch.diff(server.world.content));
        return node;
    }

    @Test
    void everyoneHearsWhoLogsInAndOut() {
        Player alice = join("alice");
        log.clear();
        Player bob = new Player(new LocalConnection(server.connect()));
        bob.send(new Intent.Join("bob"));
        server.tick();
        alice.receive();
        assertTrue(log.contains("bob logged in."));
        log.clear();
        bob.receive();
        assertTrue(log.contains("Welcome to test, bob!"));
        assertTrue(log.contains("Also here: alice."));
        assertFalse(log.contains("bob logged in."), "not to yourself");

        bob.send(new Intent.Leave());
        server.tick();
        log.clear();
        alice.receive();
        assertTrue(log.contains("bob logged out."));
    }

    @Test
    void yourTotalLevelIsWhatOthersSee() {
        Player alice = join("alice");
        alice.send(new Intent.Admin("1", List.of("3")));
        tick(alice);
        assertEquals(3, alice.totalLevel(), "three skills, all level 1");
        Skill skill = alice.content.skills.get(0);
        alice.send(new Intent.Admin("5", List.of(skill.name, String.valueOf(Skill.XP_PER_LEVEL[4]))));
        tick(alice);
        assertEquals(7, alice.totalLevel(), "one of them up to 5");

        Player bob = join("bob");
        tick(alice, bob);
        assertEquals(alice.totalLevel(), bob.others.get(alice.id).totalLevel(bob.content));
    }

    @Test
    void chatGoesToEveryoneWithWhoSaidIt() {
        Player alice = join("alice");
        Player bob = join("bob");
        log.clear();
        alice.say("  hello\u0007 there  ");
        tick(alice, bob);
        assertEquals(2, log.stream().filter(line -> line.equals("alice: hello there")).count(),
                "both hear it (one line each), control characters and spaces trimmed");

        log.clear();
        alice.say("x".repeat(500));
        alice.say("   ");
        tick(alice, bob);
        assertEquals(2, log.size(), "a blank line says nothing");
        assertEquals("alice: " + "x".repeat(GameServer.MAX_CHAT), log.get(0), "long lines are cut");
    }

    @Test
    void stepsCountOncePerTickEvenRunning() {
        Player alice = join("alice");
        alice.toggleRunning();
        alice.send(new Intent.WalkTo(new Position(10, 0)));
        for (int i = 0; i < 8; i++) {
            tick(alice);
        }
        assertEquals(10, alice.x);
        assertEquals(5L, alice.stats.get(Stat.STEPS), "10 tiles running = 5 ticks of moving");
    }
}
