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
import net.shchoo.scafe.scafe.persistence.AccountStore;
import net.shchoo.scafe.scafe.persistence.WorldStore;
import net.shchoo.scafe.scafe.protocol.Intent;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ChurchTest {
    private final List<String> log = new ArrayList<>();
    private final Consumer<String> listener = log::add;
    private GameServer server;
    private Player player;
    private GameCharacter alice;
    private Building church;

    @BeforeEach
    void setUp() {
        GameLog.subscribe(listener);
        World world = new World(1, "w", 7);
        server = new GameServer(world);
        server.setDiscovery(Discovery.off());
        server.ensureBuildings();
        church = world.content.buildings().stream().filter(b -> b.kind() == Building.Kind.CHURCH).findFirst().orElseThrow();
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

    @Test
    void aChurchNearTheOriginWithTheSameRulesAsTheBank() {
        Building bank = server.world.content.buildings().stream().filter(b -> b.kind() == Building.Kind.BANK).findFirst().orElseThrow();
        assertEquals(10, church.tiles().size(), "3 x 3, and the cross on top");
        for (Position tile : church.tiles()) {
            int away = Math.max(Math.abs(tile.x()), Math.abs(tile.y()));
            assertTrue(away > WorldGenerator.BUILDING_CLEAR_RADIUS && away <= WorldGenerator.BUILDING_MAX_RADIUS, tile.toString());
            assertFalse(server.passable(tile));
            assertFalse(bank.within(tile, 1), "never touching the bank");
        }
        Position besideTheCross = new Position(church.corner().x(), church.corner().y());
        assertTrue(server.passable(besideTheCross), "the cross stands on its own");
        server.ensureBuildings();
        assertEquals(Building.Kind.values().length, server.world.content.buildings().size(), "one of each");
    }

    @Test
    void itLooksLikeAChurch() throws IOException {
        player.x = church.corner().x() + 1;
        player.y = church.corner().y() + 6;
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(Layout.TERMINAL_SIZE));
        screen.startScreen();
        new MapView(Layout.MAP).draw(screen, player, new Board(player.content));
        int x = Layout.tileScreenX(church.corner().x(), player.x), y = Layout.tileScreenY(church.corner().y(), player.y);
        String[] expected = {"\u2022 \u253c \u2022", "\u2554\u2500\u2567\u2500\u2557", "\u2502CHU\u2502", "\u2502RCH\u2502"};
        for (int row = 0; row < expected.length; row++) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                line.append(screen.getBackCharacter(x + i, y + row).getCharacterString());
            }
            assertEquals(expected[row], line.toString(), "row " + row);
        }
        assertEquals(TextColor.ANSI.YELLOW_BRIGHT, screen.getBackCharacter(x + 1, y + 2).getForegroundColor(), "the C in a gap");
    }

    @Test
    void hpDoesntComeBackByItself() {
        alice.hp = 3;
        for (int i = 0; i < 50; i++) {
            tick();
        }
        assertEquals(3, alice.hp);
    }

    @Test
    void prayingAtTheChurchRestoresAllYourHp() {
        alice.hp = 3;
        alice.moveTo(new Position(church.corner().x() + 1, church.corner().y() + church.kind().height()));
        alice.face(Direction.UP);
        tick();
        player.send(new Intent.Move(0, -1));
        tick();
        player.perform();
        tick();
        assertEquals(alice.maxHp(), alice.hp);
        assertEquals(alice.maxHp(), player.hp);
        assertTrue(log.stream().anyMatch(m -> m.startsWith("You pray, and feel restored")));
        assertFalse(player.bankOpen);

        player.perform();
        tick();
        assertTrue(log.contains("You're already at full health."));
    }

    @Test
    void loggingOutAndBackInDoesntHeal(@TempDir Path dir) throws Exception {
        try (AccountStore accounts = new AccountStore(dir.resolve("accounts.db"));
             WorldStore store = new WorldStore(dir.resolve("world.db"), accounts)) {
            GameServer saved = new GameServer(store.loadOrCreateWorld(1, "w", 3), store);
            Player bob = new Player(new LocalConnection(saved.connect()));
            bob.send(new Intent.Join("bob"));
            saved.tick();
            bob.receive();
            saved.online(bob.id).hp = 5;
            bob.send(new Intent.Leave());
            saved.tick();
            assertEquals(5, store.loadOrCreateCharacter("bob").savedHp, "saved");
            Player again = new Player(new LocalConnection(saved.connect()));
            again.send(new Intent.Join("bob"));
            saved.tick();
            again.receive();
            assertEquals(5, again.hp, "and still 5 when you're back");
        }
    }
}
