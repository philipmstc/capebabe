package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.protocol.Intent;
import net.shchoo.scafe.scafe.server.Discovery;
import net.shchoo.scafe.scafe.server.GameServer;
import net.shchoo.scafe.scafe.server.LocalConnection;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MapMenuTest {
    private GameServer server;
    private Player player;
    private Board board;
    private final MapMenu map = new MapMenu();

    @BeforeEach
    void setUp() {
        server = new GameServer(new World(1, "w", 1));
        server.setDiscovery(Discovery.off());
        player = new Player(new LocalConnection(server.connect()));
        board = new Board(player.content);
        player.send(new Intent.Join("alice"));
        tick();
        map.focus(player, board);
    }

    private void tick() {
        server.tick();
        player.receive();
    }

    private void press(KeyType type) {
        map.handleKey(new KeyStroke(type), player, board);
    }

    @Test
    void theCursorStartsOnTheTileYoureFacing() {
        assertEquals(player.ahead(), map.cursor(player));
    }

    @Test
    void theCursorStaysPutOnScreenWhileYouMove() {
        for (int i = 0; i < 10; i++) {
            press(KeyType.ArrowRight);
        }
        int edge = Layout.BOARD_WIDTH - 1 - Layout.VIEW_CENTER_COLUMN;
        assertEquals(new Position(edge, 1), map.cursor(player), "clamped to the right edge of the view");

        press(KeyType.Enter);
        tick();
        tick();
        assertEquals(2, player.x, "two steps along");
        assertEquals(new Position(player.x + edge, player.y + 1), map.cursor(player), "the cursor moved with you");

        press(KeyType.Enter);
        for (int i = 0; i < 10; i++) {
            tick();
        }
        assertTrue(player.x > edge, "pressing Enter again keeps going the same way (now at " + player.x + ")");
        assertEquals(new Position(player.x + edge, player.y + 1), map.cursor(player));
    }
}
