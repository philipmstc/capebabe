package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.GameLog;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.ui.BankView;
import net.shchoo.scafe.scafe.client.ui.InventoryPanel;
import net.shchoo.scafe.scafe.client.ui.Layout;
import net.shchoo.scafe.scafe.client.ui.MapView;
import net.shchoo.scafe.scafe.generation.WorldGenerator;
import net.shchoo.scafe.scafe.model.Building;
import net.shchoo.scafe.scafe.model.Direction;
import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.Recipe;
import net.shchoo.scafe.scafe.model.Stacks;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.protocol.Intent;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class BankTest {
    private final List<String> log = new ArrayList<>();
    private final Consumer<String> listener = log::add;
    private GameServer server;
    private Player player;
    private GameCharacter alice;
    private Building bank;
    private Product item;

    @BeforeEach
    void setUp() {
        GameLog.subscribe(listener);
        World world = new World(1, "w", 7);
        world.content.apply(WorldGenerator.newSkills(world, 1));
        server = new GameServer(world);
        server.setDiscovery(Discovery.off());
        server.ensureBuildings();
        bank = world.content.buildings().stream().filter(b -> b.kind() == Building.Kind.BANK).findFirst().orElseThrow();
        item = world.content.skills.get(0).productLines.get(0).recipes.get(0).product;
        player = new Player(new LocalConnection(server.connect()));
        player.send(new Intent.Join("alice"));
        tick();
        alice = server.online(player.id);
    }

    @AfterEach
    void tearDown() {
        GameLog.unsubscribe(listener);
    }

    private void tick() {
        server.tick();
        player.receive();
    }

    /** Just below the bank's open side, facing it. */
    private void standAtTheBank() {
        alice.moveTo(new Position(bank.corner().x() + 1, bank.corner().y() + bank.kind().height()));
        alice.face(Direction.UP);
        tick();
        player.send(new Intent.Move(0, -1));
        tick();
    }

    private Product mine(Product serverSide) {
        return player.content.product(serverSide.id);
    }

    @Test
    void everyWorldGetsOneBankNearButNotAtTheOrigin() {
        server.ensureBuildings();
        assertEquals(2, server.world.content.buildings().size(), "a bank and a church, only if there isn't one already");
        assertEquals(Building.Kind.BANK, bank.kind());
        for (Position tile : bank.tiles()) {
            int away = Math.max(Math.abs(tile.x()), Math.abs(tile.y()));
            assertTrue(away > WorldGenerator.BUILDING_CLEAR_RADIUS && away <= WorldGenerator.BUILDING_MAX_RADIUS, tile.toString());
            assertFalse(server.passable(tile), "you can't walk through it");
            assertEquals(bank, player.content.buildingAt(tile), "the client knows it too");
        }
        assertEquals(12, bank.tiles().size(), "4 x 3");
    }

    @Test
    void itLooksLikeABank() {
        String[] rows = {"\u2554\u2550\u2550\u2557", "\u2502BA\u2502", "\u2502NK\u2502"};
        for (int dy = 0; dy < 3; dy++) {
            for (int dx = 0; dx < 4; dx++) {
                assertEquals(rows[dy].charAt(dx), bank.glyph(new Position(bank.corner().x() + dx, bank.corner().y() + dy)));
            }
        }
    }

    @Test
    void itsDrawnInBrightYellowWithAnUnbrokenRoof() throws IOException {
        player.x = bank.corner().x() + 1;
        player.y = bank.corner().y() + 5;
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(Layout.TERMINAL_SIZE));
        screen.startScreen();
        new MapView(Layout.MAP).draw(screen, player, new Board(player.content));
        int x = Layout.tileScreenX(bank.corner().x(), player.x), y = Layout.tileScreenY(bank.corner().y(), player.y);
        StringBuilder roof = new StringBuilder(), middle = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            roof.append(screen.getBackCharacter(x + i, y).getCharacterString());
            middle.append(screen.getBackCharacter(x + i, y + 1).getCharacterString());
        }
        assertEquals("\u2554\u2500\u2550\u2500\u2550\u2500\u2557", roof.toString(), "double tiles, single gaps");
        assertEquals("\u2502 B A \u2502", middle.toString());
        assertEquals(TextColor.ANSI.YELLOW_BRIGHT, screen.getBackCharacter(x + 2, y + 1).getForegroundColor());
    }

    @Test
    void qAtTheBankOpensItAndYouCanDepositAndWithdraw() {
        alice.changeInventory(item, 5);
        standAtTheBank();
        player.perform();
        assertTrue(player.bankOpen, "straight away, before the server's tick");
        tick();
        assertTrue(player.bankOpen);

        player.deposit(mine(item), 3);
        tick();
        assertEquals(2, player.inventory.get(mine(item)));
        assertEquals(3, player.bank.get(mine(item)));

        player.withdraw(mine(item), 99);
        tick();
        assertEquals(5, player.inventory.get(mine(item)), "as many as there are");
        assertFalse(player.bank.containsKey(mine(item)), "the bank's empty again");
    }

    @Test
    void onlyAtTheBank() {
        alice.changeInventory(item, 5);
        tick();
        player.deposit(mine(item), 1);
        tick();
        assertTrue(log.contains("You need to be at a bank."));
        assertEquals(5, player.inventory.get(mine(item)));
    }

    @Test
    void theInventoryHolds100StacksAndWithdrawingStopsThere() {
        alice.changeInventory(item, Stacks.INVENTORY_SLOTS + 10);
        tick();
        assertEquals(Stacks.INVENTORY_SLOTS + 10, player.slotsUsed(), "already over (stacks of 1 at level 1)");
        standAtTheBank();
        player.deposit(mine(item), 20);
        tick();
        player.withdraw(mine(item), 20);
        tick();
        assertEquals(Stacks.INVENTORY_SLOTS, player.inventory.get(mine(item)), "only up to the cap");
        assertEquals(10, player.bank.get(mine(item)));
        assertTrue(log.contains("Your inventory is full."));
    }

    @Test
    void craftingStopsWhenThereIsNoRoomForWhatYouMake() {
        Recipe raw = server.world.content.skills.get(0).productLines.get(0).recipes.get(0);
        Product other = server.world.content.skills.get(0).productLines.get(0).recipes.get(1).product;
        alice.changeInventory(other, Stacks.INVENTORY_SLOTS);
        WorldGeneratorAccess.place(server, raw, alice.ahead());
        tick();
        player.send(new Intent.Perform());
        for (int i = 0; i < 6; i++) {
            tick();
        }
        assertTrue(log.contains("Your inventory is full."), log.toString());
        assertEquals(0, player.inventory.getOrDefault(mine(raw.product), 0));
    }

    @Test
    void theBankWindowMovesStacksAcross() {
        alice.changeInventory(item, 5);
        standAtTheBank();
        player.perform();
        BankView view = new BankView();
        Board board = new Board(player.content);
        assertTrue(view.onKey(new KeyStroke('w', false, false), player, board), "it takes every key");
        view.onKey(new KeyStroke(KeyType.Enter), player, board);
        assertTrue(view.asking(), "how many?");
        view.onKey(new KeyStroke(KeyType.ArrowDown), player, board);
        view.onKey(new KeyStroke(KeyType.ArrowDown), player, board);
        view.onKey(new KeyStroke(KeyType.Enter), player, board);
        view.onKey(new KeyStroke('4', false, false), player, board);
        view.onKey(new KeyStroke(KeyType.Enter), player, board);
        tick();
        assertEquals(4, player.bank.get(mine(item)), "Amount... 4");

        view.onKey(new KeyStroke(KeyType.Tab), player, board);
        assertTrue(view.onBankSide());
        view.onKey(new KeyStroke(KeyType.Enter), player, board);
        view.onKey(new KeyStroke(KeyType.Enter), player, board);
        tick();
        assertEquals(3, player.bank.get(mine(item)), "1 withdrawn");
        assertEquals(2, player.inventory.get(mine(item)));

        view.onKey(new KeyStroke(KeyType.Escape), player, board);
        assertFalse(player.bankOpen);
        assertFalse(view.onKey(new KeyStroke('w', false, false), player, board), "closed: keys go back to the game");
    }

    @Test
    void theWindowShowsBothSides() throws IOException {
        alice.changeInventory(item, 5);
        standAtTheBank();
        player.perform();
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(Layout.TERMINAL_SIZE));
        screen.startScreen();
        new BankView().drawOnTop(screen, player, new Board(player.content));
        StringBuilder text = new StringBuilder();
        for (int y = Layout.MAP.y(); y <= Layout.MAP.bottom(); y++) {
            for (int x = Layout.MAP.x(); x <= Layout.MAP.right(); x++) {
                text.append(screen.getBackCharacter(x, y).getCharacterString());
            }
            text.append('\n');
        }
        String shown = text.toString();
        assertTrue(shown.contains("Inventory 5/100"), shown);
        assertTrue(shown.contains("Bank"));
        assertTrue(shown.contains("empty"));
        assertTrue(shown.contains("[Enter] deposit"));
    }

    @Test
    void depositingShowsItsOnItsWayUntilTheServerTicks() {
        alice.changeInventory(item, 5);
        standAtTheBank();
        player.perform();
        player.deposit(mine(item), 1);
        assertTrue(player.bankBusy());
        tick();
        assertFalse(player.bankBusy());
        assertEquals(1, player.bank.get(mine(item)));
    }

    @Test
    void newNodesNeverHaveANeighbour() {
        Recipe raw = server.world.content.skills.get(0).productLines.get(0).recipes.get(0);
        Position near = new Position(bank.corner().x() + 1, bank.corner().y() + bank.kind().height() + 2);
        WorldGeneratorAccess.place(server, raw, near);
        java.util.Random random = new java.util.Random(1);
        for (int i = 0; i < 300; i++) {
            Position spot = Discovery.nodeSpot(server, near, 1, 4, random);
            assertNotNull(spot);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    Position next = new Position(spot.x() + dx, spot.y() + dy);
                    assertTrue(server.passable(next) || next.equals(spot), spot + " is next to " + next);
                }
            }
        }
    }
}
