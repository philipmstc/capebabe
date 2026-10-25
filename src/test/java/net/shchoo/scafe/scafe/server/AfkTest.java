package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.GameLog;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.ui.GoalsMenu;
import net.shchoo.scafe.scafe.client.ui.InventoryPanel;
import net.shchoo.scafe.scafe.client.ui.Layout;
import net.shchoo.scafe.scafe.client.ui.MapMenu;
import net.shchoo.scafe.scafe.client.ui.SettingsMenu;
import net.shchoo.scafe.scafe.client.ui.SkillsMenu;
import net.shchoo.scafe.scafe.client.ui.StatsMenu;
import net.shchoo.scafe.scafe.client.ui.TabBar;
import net.shchoo.scafe.scafe.generation.WorldGenerator;
import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.Recipe;
import net.shchoo.scafe.scafe.model.SkillType;
import net.shchoo.scafe.scafe.model.Stat;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.model.WorldContent;
import net.shchoo.scafe.scafe.protocol.Intent;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class AfkTest {
    private final List<String> log = new ArrayList<>();
    private final Consumer<String> listener = log::add;
    private GameServer server;
    private Player player;
    private Recipe raw;

    @BeforeEach
    void setUp() {
        GameLog.subscribe(listener);
        World world = new World(1, "w", 2);
        world.content.apply(WorldGenerator.newSkills(world, 3));
        raw = world.content.skills.stream().filter(s -> s.type == SkillType.PRIMARY).findFirst().orElseThrow()
                .productLines.get(0).recipes.get(0);
        server = new GameServer(world);
        server.setDiscovery(Discovery.off());
        player = new Player(new LocalConnection(server.connect()));
        player.send(new Intent.Join("alice"));
        ticks(1);
    }

    @AfterEach
    void tearDown() {
        GameLog.unsubscribe(listener);
    }

    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            server.tick();
            player.receive();
        }
    }

    /** A node for the raw recipe that runs out after {@code charges} and comes back {@code respawn} ticks later. */
    private Node node(Position at, int charges, int respawn) {
        WorldContent scratch = server.world.content.copy();
        Node node = new Node(scratch.allocateId(), at, scratch.recipe(raw.id), Node.UNLIMITED, charges, respawn);
        scratch.addNode(node);
        server.applyContent(scratch.diff(server.world.content));
        ticks(1);
        return node;
    }

    private long xp() {
        return player.getExperience(player.content.skill(raw.skill.id));
    }

    private int made() {
        return player.inventory.getOrDefault(player.content.product(raw.product.id), 0);
    }

    @Test
    void afkCarriesOnWhenTheNodeComesBackForHalfTheXp() {
        Node node = node(new Position(0, 1), 2, 6);
        player.craft(node.position, true);
        ticks(1);
        assertTrue(player.currentAction.label().endsWith("(AFK)"), player.currentAction.label());
        for (int i = 0; i < 3 * Node.DEFAULT_DURATION_TICKS + 2 && made() < 2; i++) {
            ticks(1);
        }
        assertEquals(2, made(), "both charges");
        assertEquals(2L * (raw.expGained / 2), xp(), "half the xp each");
        ticks(1);
        assertNotNull(player.currentAction, "still at it");
        assertTrue(player.currentAction.label().startsWith("waiting"), player.currentAction.label());
        for (int i = 0; i < 6 + 3 * Node.DEFAULT_DURATION_TICKS && made() < 3; i++) {
            ticks(1);
        }
        assertEquals(3, made(), "picked back up when it came back");
        assertFalse(log.contains("The " + raw.product.name + " ran out."), "never stopped for it");
    }

    @Test
    void normalCraftingStillStopsWhenItRunsOut() {
        Node node = node(new Position(0, 1), 1, 6);
        player.craft(node.position, false);
        for (int i = 0; i < 3 * Node.DEFAULT_DURATION_TICKS; i++) {
            ticks(1);
        }
        assertEquals(1, made());
        assertEquals((long) raw.expGained, xp(), "full xp");
        assertNull(player.currentAction);
        assertTrue(log.contains("The " + raw.product.name + " ran out."));
    }

    @Test
    void craftingFurtherOffWalksThereFirstAndQStopsAfk() {
        Node node = node(new Position(3, 0), Node.UNLIMITED, 0);
        player.craft(node.position, true);
        for (int i = 0; i < 8 && player.currentAction == null; i++) {
            ticks(1);
        }
        assertEquals(new Position(2, 0), player.position(), "walked up beside it");
        assertTrue(player.currentAction.label().endsWith("(AFK)"));
        player.send(new Intent.Perform());
        ticks(1);
        assertNull(player.currentAction, "q stops it like any action");
    }

    @Test
    void enterOnANodeOpensItsMenu() throws Exception {
        // the cursor starts on the tile you face (0, 1); two to the right
        Node node = node(new Position(2, 1), Node.UNLIMITED, 0);
        Board board = new Board(player.content);
        TabBar tabs = new TabBar(Layout.TABS, new InventoryPanel(Layout.INVENTORY), new MapMenu(),
                List.of(new SkillsMenu(), new GoalsMenu(), new StatsMenu()), new SettingsMenu());
        tabs.onKey(new KeyStroke('v', false, false), player, board);
        tabs.onKey(new KeyStroke(KeyType.ArrowRight), player, board);
        tabs.onKey(new KeyStroke(KeyType.ArrowRight), player, board);
        tabs.onKey(new KeyStroke(KeyType.Enter), player, board);
        assertTrue(tabs.map().nodeMenuOpen(), "a node: its menu, not a walk");

        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(Layout.TERMINAL_SIZE));
        screen.startScreen();
        tabs.drawOnTop(screen, player, board);
        StringBuilder text = new StringBuilder();
        for (int y = 0; y < Layout.TERMINAL_SIZE.getRows(); y++) {
            for (int x = 0; x < Layout.TERMINAL_SIZE.getColumns(); x++) {
                text.append(screen.getBackCharacter(x, y).getCharacterString());
            }
            text.append('\n');
        }
        String shown = text.toString();
        String name = raw.product.name;
        assertTrue(shown.contains("> Craft " + name + " "), shown);
        assertTrue(shown.contains("AFK " + name));
        assertTrue(shown.contains("Cancel"));

        tabs.onKey(new KeyStroke(KeyType.ArrowDown), player, board);
        tabs.onKey(new KeyStroke(KeyType.Enter), player, board);
        assertFalse(tabs.map().nodeMenuOpen());
        assertFalse(tabs.map().isFocused(), "off you go");
        for (int i = 0; i < 6 && player.currentAction == null; i++) {
            ticks(1);
        }
        assertEquals(name + " (AFK)", player.currentAction.label());
        assertEquals(node.position, player.position().step(player.facing));
    }

    @Test
    void enterOnEmptyGroundStillWalks() {
        Board board = new Board(player.content);
        TabBar tabs = new TabBar(Layout.TABS, new InventoryPanel(Layout.INVENTORY), new MapMenu(),
                List.of(new SkillsMenu(), new GoalsMenu(), new StatsMenu()), new SettingsMenu());
        tabs.onKey(new KeyStroke('v', false, false), player, board);
        tabs.onKey(new KeyStroke(KeyType.ArrowRight), player, board);
        tabs.onKey(new KeyStroke(KeyType.ArrowRight), player, board);
        tabs.onKey(new KeyStroke(KeyType.Enter), player, board);
        assertFalse(tabs.map().nodeMenuOpen());
        ticks(4);
        assertEquals(new Position(2, 1), player.position(), "walked to the empty tile");
    }

    @Test
    void timePlayedCountsUpAndShowsInTheStatsTab() throws Exception {
        ticks(100);
        assertEquals(101L, server.online(player.id).stats.get(Stat.TIME_PLAYED), "a tick a tick, quietly");
        assertTrue(player.timePlayedTicks() >= 100, "the client counts along: " + player.timePlayedTicks());
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(Layout.TERMINAL_SIZE));
        screen.startScreen();
        StatsMenu stats = new StatsMenu();
        stats.setBounds(Layout.TABS);
        stats.draw(screen, player, new Board(player.content));
        StringBuilder text = new StringBuilder();
        for (int y = Layout.TABS.y(); y <= Layout.TABS.bottom(); y++) {
            for (int x = Layout.TABS.x(); x <= Layout.TABS.right(); x++) {
                text.append(screen.getBackCharacter(x, y).getCharacterString());
            }
            text.append('\n');
        }
        assertTrue(text.toString().contains("Time played"), text.toString());
        assertTrue(text.toString().contains("1m"), "100 ticks = 1 minute: " + text);
    }
}
