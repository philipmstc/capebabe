package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.GameLog;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.ui.Layout;
import net.shchoo.scafe.scafe.client.ui.MapView;
import net.shchoo.scafe.scafe.generation.WorldGenerator;
import net.shchoo.scafe.scafe.model.Building;
import net.shchoo.scafe.scafe.model.Direction;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.protocol.Intent;

import com.googlecode.lanterna.TextColor;
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

class MerchantTest {
    private final List<String> log = new ArrayList<>();
    private final Consumer<String> listener = log::add;
    private GameServer server;
    private Player player;
    private Building merchant;

    @BeforeEach
    void setUp() {
        GameLog.subscribe(listener);
        server = new GameServer(new World(1, "w", 7));
        server.setDiscovery(Discovery.off());
        server.ensureBuildings();
        merchant = server.world.content.buildings().stream().filter(b -> b.kind() == Building.Kind.MERCHANT).findFirst().orElseThrow();
        player = new Player(new LocalConnection(server.connect()));
        player.send(new Intent.Join("alice"));
        server.tick();
        player.receive();
    }

    @AfterEach
    void tearDown() {
        GameLog.unsubscribe(listener);
    }

    @Test
    void aMerchantWithTheSameRulesAsTheOthers() {
        assertEquals(4, merchant.kind().width());
        assertEquals(4, merchant.kind().height());
        assertEquals(16, merchant.tiles().size(), "the sign, the counter and the stall");
        for (Position tile : merchant.tiles()) {
            assertTrue(Math.max(Math.abs(tile.x()), Math.abs(tile.y())) > WorldGenerator.BUILDING_CLEAR_RADIUS, tile.toString());
            assertFalse(server.passable(tile));
        }
        for (Building other : server.world.content.buildings()) {
            if (other != merchant) {
                assertTrue(other.tiles().stream().noneMatch(t -> merchant.within(t, 1)), "not touching the " + other.kind().label);
            }
        }
    }

    @Test
    void itLooksLikeTheSketch() throws Exception {
        player.x = merchant.corner().x() + 2;
        player.y = merchant.corner().y() + 7;
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(Layout.TERMINAL_SIZE));
        screen.startScreen();
        new MapView(Layout.MAP).draw(screen, player, new Board(player.content));
        int x = Layout.tileScreenX(merchant.corner().x(), player.x), y = Layout.tileScreenY(merchant.corner().y(), player.y);
        String[] expected = {"\u2502MERCH\u2502", "\u2502A N T\u2502", "\u2500\u2500\u2500\u2500\u2500\u2500\u2500", "\u2502  \u0436  \u2502"};
        for (int row = 0; row < expected.length; row++) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < 7; i++) {
                line.append(screen.getBackCharacter(x + i, y + row).getCharacterString());
            }
            assertEquals(expected[row], line.toString(), "row " + row);
        }
        assertEquals(TextColor.ANSI.YELLOW_BRIGHT, screen.getBackCharacter(x + 1, y + 2).getForegroundColor());
        assertEquals(TextColor.ANSI.RED, screen.getBackCharacter(x + 3, y + 3).getForegroundColor(), "the ant, in ant red");
    }

    @Test
    void theAntMindingTheStallWavesItsAntennae() throws Exception {
        player.x = merchant.corner().x() + 2;
        player.y = merchant.corner().y() + 7;
        assertEquals("Merch Ant", merchant.kind().label);
        assertTrue(merchant.isKeeper('\u0436'));
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(Layout.TERMINAL_SIZE));
        screen.startScreen();
        java.util.Set<String> looks = new java.util.HashSet<>();
        for (int frame = 0; frame < 40; frame++) {
            player.frame();
            new MapView(Layout.MAP).draw(screen, player, new Board(player.content));
            // in the stall's middle: the gap right of its second tile, bottom row
            looks.add(screen.getBackCharacter(Layout.tileScreenX(merchant.corner().x(), player.x) + 3,
                    Layout.tileScreenY(merchant.corner().y() + 3, player.y)).getCharacterString());
        }
        assertEquals(java.util.Set.of("\u0436", "\u0416"), looks, "six legs, now and then flexing");
    }

    @Test
    void itsNotOpenYet() {
        GameCharacter alice = server.online(player.id);
        alice.moveTo(new Position(merchant.corner().x() + 1, merchant.corner().y() + 4));
        alice.face(Direction.UP);
        server.tick();
        player.receive();
        player.send(new Intent.Move(0, -1));
        server.tick();
        player.receive();
        player.perform();
        server.tick();
        player.receive();
        assertTrue(log.contains("The ant waves its antennae: not open for business yet."), log.toString());
    }
}
