package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.ui.Tutorial;
import net.shchoo.scafe.scafe.generation.WorldGenerator;
import net.shchoo.scafe.scafe.model.Recipe;
import net.shchoo.scafe.scafe.model.Skill;
import net.shchoo.scafe.scafe.model.SkillType;
import net.shchoo.scafe.scafe.model.Stat;
import net.shchoo.scafe.scafe.protocol.Intent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TutorialTest {
    private GameServer server;
    private Player player;
    private GameCharacter alice;
    private int stepX = 1;

    private void start(TutorialDiscovery.Pacing pacing) {
        server = TutorialWorld.create(pacing);
        player = new Player(new LocalConnection(server.connect()));
        player.send(new Intent.Join("alice"));
        tick();
        alice = server.online(player.id);
    }

    private void tick() {
        server.tick();
        player.receive();
    }

    /** A step back and forth by the origin (a roll each). */
    private void step() {
        player.send(new Intent.Move(stepX, 0));
        stepX = -stepX;
        tick();
    }

    private Skill skill(SkillType type) {
        return server.world.content.craftingSkills().stream().filter(s -> s.type == type).findFirst().orElse(null);
    }

    private static Recipe tier0(Skill skill) {
        return skill.productLines.get(0).recipes.get(0);
    }

    @Test
    void theScriptRunsInOrder_onlyWhenForcedWithForcedPacing() {
        start(TutorialDiscovery.ONLY_FORCED);
        TutorialDiscovery script = TutorialWorld.script(server);
        for (int i = 0; i < 20; i++) {
            step();
        }
        assertTrue(server.world.content.craftingSkills().isEmpty(), "nothing by chance");
        assertEquals(TutorialDiscovery.Stage.PRIMARY, script.next());

        script.force(null);
        step();
        assertNotNull(skill(SkillType.PRIMARY), "the first skill is primary");
        assertEquals(1, server.world.content.nodes().size(), "with its node");
        assertEquals(TutorialDiscovery.Stage.SECONDARY, script.next());
        step();
        assertNull(skill(SkillType.SECONDARY), "one stage a force");

        script.force(TutorialDiscovery.Stage.LOCKED_NODE);
        step();
        assertNotNull(skill(SkillType.SECONDARY));
        assertFalse(server.world.content.monsters.isEmpty());
        assertTrue(server.world.content.nodes().stream().anyMatch(alice::isLockedFor), "a node too high level");
        assertEquals(TutorialDiscovery.Stage.TERTIARY, script.next());
        assertTrue(skill(SkillType.SECONDARY).productLines.get(0).recipes.get(0).components.keySet().stream()
                .anyMatch(p -> server.world.content.madeBy(p).skill == skill(SkillType.PRIMARY)), "it uses the primary's items");

        player.send(new Intent.Admin("6", List.of()));
        tick();
        step();
        assertNotNull(skill(SkillType.TERTIARY), "/6: the next stage");
        assertNull(script.next(), "the script's over");
    }

    @Test
    void theUsualPacingFollowsWhatYouDo() {
        start(TutorialDiscovery.DEFAULT_PACING);
        TutorialDiscovery script = TutorialWorld.script(server);
        for (int i = 0; i < TutorialDiscovery.FIRST_STEPS; i++) {
            step();
        }
        assertNotNull(skill(SkillType.PRIMARY), "after a little walking");
        alice.count(Stat.ITEMS_CRAFTED, TutorialDiscovery.FIRST_ITEMS);
        step();
        assertNotNull(skill(SkillType.SECONDARY), "after a few items");
        step();
        assertEquals(TutorialDiscovery.Stage.MONSTER, script.next(), "not until something's made with it");
        alice.gainExperience(skill(SkillType.SECONDARY), 10);
        step();
        assertEquals(TutorialDiscovery.Stage.LOCKED_NODE, script.next());
        alice.count(Stat.MONSTERS_KILLED);
        step();
        assertEquals(TutorialDiscovery.Stage.TERTIARY, script.next());
        alice.setGoal(skill(SkillType.PRIMARY), Tiers().get(0));
        step();
        assertNull(script.next());
        assertNotNull(skill(SkillType.TERTIARY));
    }

    private List<Integer> Tiers() {
        return java.util.Arrays.stream(net.shchoo.scafe.scafe.model.Tiers.milestones(SkillType.PRIMARY)).boxed().toList();
    }

    @Test
    void theBoxSitsOnTheMap_andEnterOrGotItDismissesAnExplanation() throws Exception {
        start(TutorialDiscovery.ONLY_FORCED);
        TutorialWorld.script(server).force(null);
        step();
        alice.changeInventory(tier0(skill(SkillType.PRIMARY)).product, 1);
        tick();
        net.shchoo.scafe.scafe.client.ui.TutorialView view = new net.shchoo.scafe.scafe.client.ui.TutorialView(
                new Tutorial(), new net.shchoo.scafe.scafe.client.ui.SkillsMenu());
        net.shchoo.scafe.scafe.client.Board board = new net.shchoo.scafe.scafe.client.Board(player.content);
        com.googlecode.lanterna.screen.Screen screen = new com.googlecode.lanterna.screen.TerminalScreen(
                new com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal(net.shchoo.scafe.scafe.client.ui.Layout.TERMINAL_SIZE));
        screen.startScreen();
        view.draw(screen, player, board);
        view.draw(screen, player, board);
        assertEquals(Tutorial.Step.ITEM, view.tutorial().step());
        view.drawOnTop(screen, player, board);
        var map = net.shchoo.scafe.scafe.client.ui.Layout.MAP;
        StringBuilder shown = new StringBuilder();
        for (int y = map.y(); y <= map.bottom(); y++) {
            for (int x = map.x(); x <= map.right(); x++) {
                shown.append(screen.getBackCharacter(x, y).getCharacterString());
            }
            shown.append('\n');
        }
        assertTrue(shown.toString().contains("Your first item!"), shown.toString());
        assertTrue(shown.toString().contains("<-- dots either side are the item's tier"), shown.toString());
        assertTrue(shown.toString().contains("[ Got it ]"), shown.toString());
        assertTrue(view.onKey(new com.googlecode.lanterna.input.KeyStroke(com.googlecode.lanterna.input.KeyType.Enter), player, board));
        assertEquals(Tutorial.Step.KEEP_CRAFTING, view.tutorial().step());
        assertFalse(view.onKey(new com.googlecode.lanterna.input.KeyStroke(com.googlecode.lanterna.input.KeyType.Enter), player, board),
                "nothing to dismiss: Enter's for the game");
    }

    /** The guide, start to end, with the script forced along. */
    @Test
    void theGuideWalksThroughEachStep() {
        start(TutorialDiscovery.ONLY_FORCED);
        TutorialDiscovery script = TutorialWorld.script(server);
        Tutorial tutorial = new Tutorial();
        boolean[] page = {false}, link = {false};
        Tutorial.Screen screen = new Tutorial.Screen() {
            @Override
            public boolean skillPageOpen() {
                return page[0];
            }

            @Override
            public boolean followedLink() {
                return link[0];
            }
        };
        tutorial.update(player, screen);
        assertEquals(Tutorial.Step.EXPLORE, tutorial.step());
        assertTrue(String.join(" ", tutorial.text(player)).contains("explore"));

        script.force(null);
        step();
        tutorial.update(player, screen);
        assertEquals(Tutorial.Step.CRAFT, tutorial.step());
        assertTrue(String.join(" ", tutorial.text(player)).contains("press q"));

        Skill primary = skill(SkillType.PRIMARY);
        alice.changeInventory(tier0(primary).product, 1);
        tick();
        tutorial.update(player, screen);
        assertEquals(Tutorial.Step.ITEM, tutorial.step());
        String item = String.join("\n", tutorial.text(player));
        assertTrue(item.contains(":    <-- dots either side are the item's tier"), item);
        assertTrue(item.contains(primary.symbol + "     <-- the skill that makes it"), item);
        assertTrue(item.contains("x1    <-- how many in this stack"), item);
        assertTrue(tutorial.dismissable());
        tutorial.dismiss();
        assertEquals(Tutorial.Step.KEEP_CRAFTING, tutorial.step());

        script.force(null);
        step();
        tutorial.update(player, screen);
        assertEquals(Tutorial.Step.SECONDARY, tutorial.step());
        Skill secondary = skill(SkillType.SECONDARY);
        assertTrue(String.join(" ", tutorial.text(player)).contains("uses your " + primary.name + " items"));
        alice.changeInventory(tier0(secondary).product, 1);
        tick();
        tutorial.update(player, screen);
        assertEquals(Tutorial.Step.RECIPE, tutorial.step());
        page[0] = true;
        tutorial.update(player, screen);
        assertEquals(Tutorial.Step.LINKS, tutorial.step());
        link[0] = true;
        tutorial.update(player, screen);
        assertEquals(Tutorial.Step.WAIT_MONSTER, tutorial.step(), "followed a link");

        script.force(null);
        step();
        tutorial.update(player, screen);
        assertEquals(Tutorial.Step.FIGHT, tutorial.step());
        assertTrue(String.join(" ", tutorial.text(player)).contains("Fight"));
        alice.count(Stat.MONSTERS_KILLED);
        tick();
        tutorial.update(player, screen);
        assertEquals(Tutorial.Step.WAIT_LOCKED, tutorial.step());

        // a drop, whenever: explained once, over the step
        server.applyContent(WorldGenerator.newDrop(server.world, server.world.content.monsters.get(0).id, "alice"), alice);
        tick();
        tutorial.update(player, screen);
        assertTrue(tutorial.dropShowing());
        assertTrue(String.join(" ", tutorial.text(player)).contains("trophy"));
        tutorial.dismiss();
        tutorial.update(player, screen);
        assertFalse(tutorial.dropShowing(), "once");

        script.force(null);
        step();
        tutorial.update(player, screen);
        assertEquals(Tutorial.Step.GOAL, tutorial.step());
        assertTrue(String.join(" ", tutorial.text(player)).contains("then g to make it a goal"));
        player.goalForNextMilestone(primary);
        tick();
        tutorial.update(player, screen);
        assertEquals(Tutorial.Step.WAIT_TERTIARY, tutorial.step());

        script.force(null);
        step();
        tutorial.update(player, screen);
        assertEquals(Tutorial.Step.WAYPOINT, tutorial.step());
        String waypoint = String.join(" ", tutorial.text(player));
        assertTrue(waypoint.contains(tier0(secondary).product.name), waypoint);
        player.waypoints.toggle(player.content.recipe(tier0(secondary).id), player);
        tutorial.update(player, screen);
        assertEquals(Tutorial.Step.DONE, tutorial.step());
        tutorial.dismiss();
        assertEquals(Tutorial.Step.FINISHED, tutorial.step());
        assertTrue(tutorial.text(player).isEmpty());
    }
}
