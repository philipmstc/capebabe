package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.GameLog;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.ui.Clicks;
import net.shchoo.scafe.scafe.client.ui.MonstersMenu;
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
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.model.WorldContent;
import net.shchoo.scafe.scafe.protocol.Intent;

import com.googlecode.lanterna.input.KeyStroke;
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

class NodeCapacityTest {
    private final List<String> log = new ArrayList<>();
    private final Consumer<String> listener = log::add;
    private GameServer server;
    private Recipe raw;
    private final List<Player> players = new ArrayList<>();

    @BeforeEach
    void setUp() {
        GameLog.subscribe(listener);
        World world = new World(1, "w", 5);
        world.content.apply(WorldGenerator.newSkills(world, 3));
        raw = world.content.skills.stream().filter(s -> s.type == SkillType.PRIMARY).findFirst().orElseThrow()
                .productLines.get(0).recipes.get(0);
        server = new GameServer(world);
        server.setDiscovery(Discovery.off());
    }

    @AfterEach
    void tearDown() {
        GameLog.unsubscribe(listener);
    }

    private Player join(String name) {
        Player player = new Player(new LocalConnection(server.connect()));
        player.send(new Intent.Join(name));
        players.add(player);
        tick();
        return player;
    }

    private void tick() {
        server.tick();
        players.forEach(Player::receive);
    }

    private Node node(Position at, int capacity) {
        WorldContent scratch = server.world.content.copy();
        Node node = new Node(scratch.allocateId(), at, scratch.recipe(raw.id), capacity, Node.UNLIMITED, 0);
        scratch.addNode(node);
        server.applyContent(scratch.diff(server.world.content));
        tick();
        return node;
    }

    @Test
    void onePerPowerOfFour() {
        assertEquals(List.of(0, 0, 0, 1, 1, 2, 2, 3),
                List.of(1, 2, 3, 4, 15, 16, 63, 64).stream().map(Node::capacityBonus).toList());
        Node node = new Node(1, new Position(0, 0), null, 2, 1, 0);
        assertEquals(3, node.capacity(1));
        assertEquals(Node.UNLIMITED, new Node(2, new Position(0, 0), null, Node.UNLIMITED, 1, 0).capacity(5));
    }

    @Test
    void craftingForRealBumpsAnAfkerOffAFullNode() {
        Player alice = join("alice");
        Player bob = join("bob");
        Node node = node(new Position(1, 1), 1);
        server.online(alice.id).moveTo(new Position(1, 0));
        server.online(bob.id).moveTo(new Position(1, 2));
        tick();
        alice.craft(node.position, true);
        tick();
        assertNotNull(server.online(alice.id).currentAction, "alice AFK on it");

        bob.craft(node.position, true);
        tick();
        assertNull(server.online(bob.id).currentAction, "AFK can't bump AFK");
        assertTrue(log.stream().anyMatch(m -> m.startsWith("Someone else is using that (1/1)")));

        bob.craft(node.position, false);
        tick();
        tick();
        assertNotNull(server.online(bob.id).currentAction, "bob crafting for real");
        assertNull(server.online(alice.id).currentAction, "alice bumped");
        assertNull(alice.currentAction);
        assertTrue(log.stream().anyMatch(m -> m.contains("you were bumped off it")));
    }

    @Test
    void moreOnlineMoreRoom() {
        Player first = join("p0");
        for (int i = 1; i < 4; i++) {
            join("p" + i);
        }
        tick();
        assertEquals(1, first.capacityBonus, "4 online: +1, told to everyone");
        Player late = join("p4");
        assertEquals(1, late.capacityBonus, "and to whoever joins");
    }

    @Test
    void rightClickingASkillOffersDetailsAndShare() throws Exception {
        Player alice = join("alice");
        Board board = new Board(alice.content);
        SkillsMenu skills = new SkillsMenu();
        TabBar tabs = new TabBar(Layout.TABS, new InventoryPanel(Layout.INVENTORY), new MapMenu(),
                List.of(skills, new MonstersMenu(), new StatsMenu()), new SettingsMenu());
        tabs.onKey(new KeyStroke('1', false, false), alice, board);
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(Layout.TERMINAL_SIZE));
        screen.startScreen();
        board.clicks.clear();
        tabs.draw(screen, alice, board);
        int y = Layout.TABS.y() + 1;
        assertTrue(board.clicks.click(Clicks.Button.RIGHT, Layout.TABS.x() + 5, y));
        board.clicks.clear();
        tabs.draw(screen, alice, board);
        tabs.drawOnTop(screen, alice, board);
        assertFalse(skills.skillOpen(), "a menu, not the page");
        tabs.onKey(new KeyStroke(com.googlecode.lanterna.input.KeyType.ArrowDown), alice, board);
        tabs.onKey(new KeyStroke(com.googlecode.lanterna.input.KeyType.Enter), alice, board);
        tick();
        String skill = board.skills.get(0).name;
        assertTrue(log.stream().anyMatch(m -> m.startsWith("alice shares: " + skill + " Lv. 1")), log.toString());
    }
}
