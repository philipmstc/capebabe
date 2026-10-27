package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.ui.BankView;
import net.shchoo.scafe.scafe.client.ui.ChurchView;
import net.shchoo.scafe.scafe.client.ui.Clicks;
import net.shchoo.scafe.scafe.client.ui.CommandLine;
import net.shchoo.scafe.scafe.client.ui.EquipmentView;
import net.shchoo.scafe.scafe.client.ui.MonstersMenu;
import net.shchoo.scafe.scafe.client.ui.InventoryPanel;
import net.shchoo.scafe.scafe.client.ui.Layout;
import net.shchoo.scafe.scafe.client.ui.MapMenu;
import net.shchoo.scafe.scafe.client.ui.MapView;
import net.shchoo.scafe.scafe.client.ui.MessageLogView;
import net.shchoo.scafe.scafe.client.ui.PlayerDetailView;
import net.shchoo.scafe.scafe.client.ui.SettingsMenu;
import net.shchoo.scafe.scafe.client.ui.SkillsMenu;
import net.shchoo.scafe.scafe.client.ui.StationView;
import net.shchoo.scafe.scafe.client.ui.StatsMenu;
import net.shchoo.scafe.scafe.client.ui.TabBar;
import net.shchoo.scafe.scafe.client.ui.Widget;
import net.shchoo.scafe.scafe.generation.WorldGenerator;
import net.shchoo.scafe.scafe.model.Building;
import net.shchoo.scafe.scafe.model.EquipSlot;
import net.shchoo.scafe.scafe.model.MonsterType;
import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.Recipe;
import net.shchoo.scafe.scafe.model.SkillType;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.model.WorldContent;
import net.shchoo.scafe.scafe.protocol.Intent;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Clicking about, with the widgets wired up as the game does. */
class MouseTest {
    private GameServer server;
    private Player player;
    private Board board;
    private MapMenu map;
    private TabBar tabs;
    private boolean loggedOut;
    private List<Widget> widgets;
    private CommandLine commandLine;
    private Recipe raw;
    private Screen screen;

    @BeforeEach
    void setUp() throws IOException {
        World world = new World(1, "w", 2);
        world.content.apply(WorldGenerator.newSkills(world, 3));
        raw = world.content.skills.stream().filter(s -> s.type == SkillType.PRIMARY).findFirst().orElseThrow()
                .productLines.get(0).recipes.get(0);
        server = new GameServer(world);
        server.setDiscovery(Discovery.off());
        player = new Player(new LocalConnection(server.connect()));
        player.send(new Intent.Join("alice"));
        tick();
        board = new Board(player.content);
        map = new MapMenu();
        tabs = new TabBar(Layout.TABS, new InventoryPanel(Layout.INVENTORY), map,
                List.of(new SkillsMenu(), new MonstersMenu(), new StatsMenu()), new SettingsMenu());
        widgets = List.of(new BankView(), new StationView(), new ChurchView(),
                new PlayerDetailView(Layout.PLAYER_DETAILS, () -> !tabs.menuFocused(), () -> loggedOut = true),
                new MapView(Layout.MAP, map), new EquipmentView(Layout.EQUIPMENT), tabs,
                new MessageLogView(Layout.LOG, commandLine = new CommandLine()));
        screen = new TerminalScreen(new DefaultVirtualTerminal(Layout.TERMINAL_SIZE));
        screen.startScreen();
    }

    private void tick() {
        server.tick();
        player.receive();
    }

    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            tick();
        }
    }

    /** A frame, as the game draws it (and so what's clickable). */
    private void draw() {
        board.clicks.clear();
        screen.clear();
        for (Widget widget : widgets) {
            widget.draw(screen, player, board);
        }
        for (Widget widget : widgets) {
            widget.drawOnTop(screen, player, board);
        }
    }

    private void click(Clicks.Button button, int x, int y) {
        draw();
        assertTrue(board.clicks.click(button, x, y), "something at " + x + "," + y);
    }

    private void clickTile(Clicks.Button button, Position tile) {
        click(button, Layout.tileScreenX(tile.x(), player.x), Layout.tileScreenY(tile.y(), player.y));
    }

    /** Where {@code text} is on screen (its first character), after a fresh frame. */
    private int[] find(String text) {
        draw();
        for (int y = 0; y < Layout.TERMINAL_SIZE.getRows(); y++) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < Layout.TERMINAL_SIZE.getColumns(); x++) {
                row.append(screen.getBackCharacter(x, y).getCharacterString());
            }
            int at = row.indexOf(text);
            if (at >= 0) {
                return new int[]{at, y};
            }
        }
        fail("'" + text + "' isn't on screen");
        return null;
    }

    private void clickText(Clicks.Button button, String text) {
        int[] at = find(text);
        assertTrue(board.clicks.click(button, at[0], at[1]), "'" + text + "' does something");
    }

    private Node node(Position at) {
        WorldContent scratch = server.world.content.copy();
        Node node = new Node(scratch.allocateId(), at, scratch.recipe(raw.id), Node.UNLIMITED, Node.UNLIMITED, 0);
        scratch.addNode(node);
        server.applyContent(scratch.diff(server.world.content));
        tick();
        return node;
    }

    private MonsterType monsterKind() {
        WorldContent scratch = server.world.content.copy();
        if (scratch.combatSkill() == null) {
            scratch.addSkill(new net.shchoo.scafe.scafe.model.Skill(scratch.allocateId(), "combat", SkillType.COMBAT, 0, 'x'));
        }
        MonsterType kind = new MonsterType(scratch.allocateId(), "monster0", 3, 20, 1, 0, 'M', 0, 1000);
        scratch.addMonster(kind);
        server.applyContent(scratch.diff(server.world.content));
        tick();
        return server.world.content.monster(kind.id);
    }

    @Test
    void leftClickingTheGroundWalksThere() {
        clickTile(Clicks.Button.LEFT, new Position(3, 2));
        ticks(6);
        assertEquals(new Position(3, 2), player.position());
    }

    @Test
    void leftClickingANodeWalksUpAndCrafts() {
        node(new Position(3, 0));
        clickTile(Clicks.Button.LEFT, new Position(3, 0));
        for (int i = 0; i < 8 && player.currentAction == null; i++) {
            tick();
        }
        assertEquals(raw.product.name, player.currentAction.label(), "normal crafting");
    }

    @Test
    void rightClickingANodeOpensItsMenuAndWalkHereWalksBesideIt() {
        node(new Position(4, 0));
        clickTile(Clicks.Button.RIGHT, new Position(4, 0));
        assertNotNull(map.popup());
        assertEquals(raw.product.name, map.popup().title());
        assertEquals(List.of("Craft " + raw.product.name, "AFK " + raw.product.name, "Walk here", "Cancel"),
                map.popup().labels());
        clickText(Clicks.Button.LEFT, "Walk here");
        assertNull(map.popup(), "closed");
        assertFalse(map.isFocused(), "the map's put down");
        ticks(6);
        assertEquals(new Position(3, 0), player.position(), "beside it, not crafting");
        assertNull(player.currentAction);
    }

    @Test
    void clickingTheMapOffAPopupClosesItAndWalksThere() {
        node(new Position(4, 0));
        clickTile(Clicks.Button.RIGHT, new Position(4, 0));
        assertNotNull(map.popup());
        clickTile(Clicks.Button.LEFT, new Position(-2, 3));
        assertNull(map.popup());
        for (int i = 0; i < 10 && !player.position().equals(new Position(-2, 3)); i++) {
            tick();
            player.frame();
        }
        assertEquals(new Position(-2, 3), player.position(), "the click went on to the map, as in RuneScape");
    }

    @Test
    void aPopupOverTheMapHidesTheTileUnderIt() {
        node(new Position(4, 0));
        clickTile(Clicks.Button.RIGHT, new Position(4, 0));
        draw();
        int[] fight = find("Craft");
        board.mouseX = fight[0];
        board.mouseY = fight[1];
        board.mouseSettled();
        assertNull(MapView.hoveredTile(board, player), "the popup's in the way: no hover");
        board.mouseX = Layout.tileScreenX(-2, player.x);
        board.mouseY = Layout.tileScreenY(3, player.y);
        board.mouseSettled();
        assertEquals(new Position(-2, 3), MapView.hoveredTile(board, player), "the map beside it still hovers");
    }

    @Test
    void leftClickingAMonsterWalksUpAndFightsIt() {
        MonsterType kind = monsterKind();
        server.spawnMonster(kind, new Position(4, 0));
        tick();
        clickTile(Clicks.Button.LEFT, new Position(4, 0));

        for (int i = 0; i < 10 && player.fightingId == null; i++) {
            tick();
        }
        assertNotNull(player.fightingId, "walked over and started fighting");
    }

    @Test
    void rightClickingAMonsterOffersFightAndWalkHere() {
        MonsterType kind = monsterKind();
        server.spawnMonster(kind, new Position(1, 1));
        tick();
        clickTile(Clicks.Button.RIGHT, new Position(1, 1));
        assertEquals("monster0 (Lv 3)", map.popup().title());
        assertEquals(List.of("Fight", "Walk here", "Cancel"), map.popup().labels());
        clickText(Clicks.Button.LEFT, "Fight");
        tick();
        assertNotNull(player.fightingId);
    }

    @Test
    void rightClickingAPlayerShowsTheirCombatLevelAndWhatsToCome() {
        Player bob = new Player(new LocalConnection(server.connect()));
        bob.send(new Intent.Join("bob"));
        tick();
        bob.receive();
        bob.moveBy(1, 0);
        tick();
        bob.receive();
        tick();
        Position at = player.others.get(bob.id).position();
        clickTile(Clicks.Button.RIGHT, at);
        assertEquals("bob (Lv 1)", map.popup().title());
        assertEquals(List.of("Trade", "Fight", "Friend", "Walk here", "Cancel"), map.popup().labels());
        clickText(Clicks.Button.LEFT, "Trade");
        assertNull(map.popup(), "not there yet: just closes");

        // AFK only shows while they are
        GameCharacter b = server.online(bob.id);
        b.training = new Combat.Training(b.position(), b.facing, Long.MAX_VALUE);
        tick();
        clickTile(Clicks.Button.RIGHT, player.others.get(bob.id).position());
        assertEquals("bob (Lv 1) AFK", map.popup().title());
        clickText(Clicks.Button.LEFT, "Cancel");
        server.online(bob.id).training = null;
        tick();
        clickTile(Clicks.Button.RIGHT, player.others.get(bob.id).position());
        assertEquals("bob (Lv 1)", map.popup().title());
    }

    @Test
    void rightClickingYourselfOffersBroadcastingWhereYouAre() {
        List<String> said = new java.util.ArrayList<>();
        net.shchoo.scafe.scafe.client.GameLog.subscribe(said::add);
        clickTile(Clicks.Button.RIGHT, player.position());
        assertEquals(player.name + "  Total Lv. " + player.totalLevel() + "  Combat Lv. 1", map.popup().title());
        assertEquals(List.of("Broadcast position", "Cancel"), map.popup().labels());
        clickText(Clicks.Button.LEFT, "Broadcast position");
        tick();
        assertTrue(said.contains(player.name + " is standing at (" + player.x + ", " + player.y + ")"), said.toString());
        assertNull(map.popup());
    }

    @Test
    void leftClickingAnItemAimsItAndAClickOnANodeUsesIt() {
        Recipe crafted = server.world.content.skills.stream().filter(s -> s.type == SkillType.SECONDARY).findFirst().orElseThrow()
                .productLines.get(0).recipes.get(0);
        Product part = crafted.components.keySet().iterator().next();
        WorldContent scratch = server.world.content.copy();
        scratch.addNode(new Node(scratch.allocateId(), new Position(0, 1), scratch.recipe(crafted.id), Node.UNLIMITED, Node.UNLIMITED, 0));
        server.applyContent(scratch.diff(server.world.content));
        crafted.components.forEach((component, count) -> server.online(player.id).changeInventory(component, 3 * count));
        tick();
        click(Clicks.Button.LEFT, Layout.INVENTORY.x() + 3, Layout.INVENTORY.y() + 2);
        assertEquals(player.content.product(part.id), map.using(), "aiming it");
        clickTile(Clicks.Button.LEFT, new Position(0, 1));
        tick();
        tick();
        assertNotNull(player.currentAction, "used on the node");
    }

    @Test
    void rightClickingAnItemOpensItsPopupAndAClickEquipsIt() {
        server.online(player.id).changeInventory(raw.product, 3);
        tick();
        click(Clicks.Button.RIGHT, Layout.INVENTORY.x() + 3, Layout.INVENTORY.y() + 2);
        clickText(Clicks.Button.LEFT, "Equip weapon");
        tick();
        assertEquals(player.content.product(raw.product.id), player.weapon);
    }

    @Test
    void atTheBankLeftDepositsAllAndWithdrawsAStack() {
        server.ensureBuildings();
        Building bank = server.world.content.buildings().stream().filter(b -> b.kind() == Building.Kind.BANK).findFirst().orElseThrow();
        GameCharacter alice = server.online(player.id);
        alice.changeInventory(raw.product, 7);
        alice.moveTo(bank.platform());
        tick();
        player.bankOpen = true;
        click(Clicks.Button.LEFT, Layout.INVENTORY.x() + 3, Layout.INVENTORY.y() + 2);
        tick();
        assertEquals(7, player.bank.get(player.content.product(raw.product.id)), "deposit all");
        click(Clicks.Button.LEFT, Layout.MAP.x() + 3, Layout.INVENTORY.y() + 2);
        tick();
        assertEquals(6, player.bank.get(player.content.product(raw.product.id)), "a stack (1, at Lv. 1) back out");
        click(Clicks.Button.LEFT, Layout.MAP.x() + 40, Layout.MAP.y() + 12);
        tick();
        assertEquals(1, player.inventory.get(player.content.product(raw.product.id)), "the map's not clickable under the bank");
    }

    @Test
    void clickingATabHeaderShowsIt() {
        clickText(Clicks.Button.LEFT, "2 Monsters");
        assertEquals("Monsters", tabs.selected().title());
        clickText(Clicks.Button.LEFT, "1 Skills");
        assertEquals("Skills", tabs.selected().title());
        assertTrue(tabs.menuFocused());
    }

    @Test
    void onASkillsPageLeftClickingATierSetsAWaypointAndRightClickOffersMarking() {
        node(new Position(5, 5));
        clickText(Clicks.Button.LEFT, "1 Skills");
        clickText(Clicks.Button.LEFT, raw.skill.name);
        SkillsMenu skills = (SkillsMenu) tabs.selected();
        assertTrue(skills.skillOpen(), "a row opens its page");
        int[] lv = find("x1     x2    x3");
        lv[1]++;
        assertTrue(board.clicks.click(Clicks.Button.LEFT, lv[0] + 3, lv[1]));
        assertTrue(player.waypoints.has(player.content.recipe(raw.id)), "waypoint");
        assertTrue(skills.skillOpen(), "and the page stays open");
        draw();
        assertTrue(board.clicks.click(Clicks.Button.RIGHT, lv[0] + 3, lv[1]));
        assertTrue(skills.skillOpen());
        clickText(Clicks.Button.LEFT, "Mark all nodes");
        assertTrue(player.highlightedRecipes.contains(player.content.recipe(raw.id)));
        assertTrue(skills.skillOpen(), "still on the page after the popup");
    }

    @Test
    void logOutAsksAndNoMeansNo() {
        clickText(Clicks.Button.LEFT, "Log Out");
        clickText(Clicks.Button.LEFT, "No");
        assertFalse(loggedOut);
        clickText(Clicks.Button.LEFT, "Log Out");
        clickText(Clicks.Button.LEFT, "Yes");
        assertTrue(loggedOut);
    }

    @Test
    void leftClickingABuildingWalksUpAndUsesIt() {
        server.ensureBuildings();
        Building bank = server.world.content.buildings().stream().filter(b -> b.kind() == Building.Kind.BANK).findFirst().orElseThrow();
        player.send(new Intent.SetRunning(true));
        player.walkTo(new Position(bank.platform().x(), bank.platform().y() + 2));
        ticks(40);
        clickTile(Clicks.Button.LEFT, new Position(bank.corner().x() + 1, bank.corner().y() + 1));
        for (int i = 0; i < 10 && !player.bankOpen; i++) {
            tick();
            player.frame();
        }
        assertTrue(player.bankOpen, "walked up to it and opened it, as q would");
    }

    @Test
    void clickingASettingChangesIt() {
        clickText(Clicks.Button.LEFT, "4 Settings");
        boolean running = player.running;
        clickText(Clicks.Button.LEFT, "Running");
        assertEquals(!running, player.running);
    }

    @Test
    void theArrowsStepASettingThatWay() {
        clickText(Clicks.Button.LEFT, "4 Settings");
        int[] color = find("Color");
        StringBuilder row = new StringBuilder();
        for (int x = 0; x < Layout.TERMINAL_SIZE.getColumns(); x++) {
            row.append(screen.getBackCharacter(x, color[1]).getCharacterString());
        }
        int left = row.indexOf("<", color[0]), right = row.indexOf(">", left);
        int start = player.colorIndex;
        click(Clicks.Button.LEFT, left, color[1]);
        assertEquals(Math.floorMod(start - 1, Intent.PLAYER_COLORS), player.colorIndex, "< goes back");
        click(Clicks.Button.LEFT, right, color[1]);
        click(Clicks.Button.LEFT, right, color[1]);
        assertEquals(Math.floorMod(start + 1, Intent.PLAYER_COLORS), player.colorIndex, "> goes on");
    }

    @Test
    void clickingWhatYouHaveOnTakesItOff() {
        server.online(player.id).changeInventory(raw.product, 3);
        tick();
        player.equip(EquipSlot.WEAPON, raw.product);
        player.equip(EquipSlot.ARMOR, raw.product);
        tick();
        assertNotNull(player.weapon);
        clickText(Clicks.Button.LEFT, "Weapon");
        tick();
        assertNull(player.weapon);
        assertNotNull(player.armor, "only that one");
        clickText(Clicks.Button.LEFT, "Armor");
        tick();
        assertNull(player.armor);
    }

    @Test
    void clickingTheLogStartsTyping() {
        assertNull(commandLine.typed());
        click(Clicks.Button.LEFT, Layout.LOG.x() + 2, Layout.LOG.y() + 2);
        assertEquals("", commandLine.typed());
    }

    @Test
    void anOptionUnderTheMouseLightsUp() {
        int[] at = find("4 Settings");
        board.clicks.highlight(screen, at[0], at[1]);
        assertEquals(Clicks.HOVER, screen.getBackCharacter(at[0], at[1]).getBackgroundColor());
        int[] map = {Layout.MAP.x() + 3, Layout.MAP.y() + 3};
        draw();
        board.clicks.highlight(screen, map[0], map[1]);
        assertNotEquals(Clicks.HOVER, screen.getBackCharacter(map[0], map[1]).getBackgroundColor(), "the map isn't an option");
    }

    @Test
    void clickingTheMapBesideAWindowClosesItAndWalksThere() {
        for (Runnable open : List.<Runnable>of(() -> player.bankOpen = true, () -> player.stationMenuOpen = true,
                () -> player.churchMenuOpen = true)) {
            open.run();
            Position from = player.position(), to = new Position(from.x() + 14, from.y() + 10); // the bank covers all but the map's right edge
            clickTile(Clicks.Button.LEFT, to);
            assertFalse(player.bankOpen || player.stationMenuOpen || player.churchMenuOpen, "closed");
            for (int i = 0; i < 40 && !player.position().equals(to); i++) {
                tick();
                player.frame();
            }
            assertEquals(to, player.position(), "and walked there");
        }
    }

    @Test
    void clickingInsideAWindowDoesntCloseIt() {
        player.churchMenuOpen = true;
        clickText(Clicks.Button.LEFT, "Church");
        assertTrue(player.churchMenuOpen, "the title isn't the map");
    }

    @Test
    void wasdClosesAWindowAndWalks() {
        player.bankOpen = true;
        draw();
        KeyStroke s = new KeyStroke('s', false, false);
        for (Widget widget : widgets) {
            if (widget.onKey(s, player, board)) {
                fail(widget + " kept the key");
            }
        }
        assertFalse(player.bankOpen);
    }
}
