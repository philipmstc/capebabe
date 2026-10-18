package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.protocol.Intent;
import net.shchoo.scafe.scafe.server.Discovery;
import net.shchoo.scafe.scafe.server.GameServer;
import net.shchoo.scafe.scafe.server.LocalConnection;

import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SettingsMenuTest {
    private GameServer server;
    private Player player;
    private Board board;
    private TabBar tabs;
    private List<Widget> widgets;
    private boolean quit;

    @BeforeEach
    void setUp() {
        server = new GameServer(new World(1, "w", 1));
        server.setDiscovery(Discovery.off());
        player = new Player(new LocalConnection(server.connect()));
        board = new Board(player.content);
        player.send(new Intent.Join("alice"));
        tick();
        tabs = new TabBar(List.of(new MapMenu(), new SkillsMenu(), new InventoryMenu(), new GoalsMenu()), new SettingsMenu());
        widgets = List.of(new CommandLine(), new MapView(), tabs, new MessageLogView(), new KeyCommands(() -> quit = true));
    }

    private void tick() {
        server.tick();
        player.receive();
    }

    private void press(KeyStroke key) {
        for (Widget widget : widgets) {
            if (widget.onKey(key, player, board)) {
                return;
            }
        }
    }

    private void press(KeyType type) {
        press(new KeyStroke(type));
    }

    private void type(char c) {
        press(new KeyStroke(c, false, false));
    }

    private String screenText() throws IOException {
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(Layout.TERMINAL_SIZE));
        screen.startScreen();
        for (Widget widget : widgets) {
            widget.draw(screen, player, board);
        }
        StringBuilder text = new StringBuilder();
        for (int y = 0; y < Layout.TERMINAL_SIZE.getRows(); y++) {
            for (int x = 0; x < Layout.TERMINAL_SIZE.getColumns(); x++) {
                TextCharacter c = screen.getBackCharacter(x, y);
                text.append(c == null ? ' ' : c.getCharacterString());
            }
            text.append('\n');
        }
        screen.stopScreen();
        return text.toString();
    }

    @Test
    void commaOpensSettingsOverTheTabsAndEscClosesIt() throws IOException {
        assertFalse(tabs.overlayOpen());
        type(',');
        assertTrue(tabs.overlayOpen());
        String shown = screenText();
        assertTrue(shown.contains("Settings"));
        assertTrue(shown.contains("Controls"));
        assertTrue(shown.contains("< cyan >"));
        press(KeyType.Escape);
        assertFalse(tabs.overlayOpen());
        assertFalse(quit, "Esc closes the menu first, it doesn't quit");
    }

    @Test
    void leftRightChangesYourColor() throws IOException {
        type(',');
        press(KeyType.ArrowRight);
        tick();
        assertEquals(1, player.colorIndex);
        assertTrue(screenText().contains("< green >"));
        press(KeyType.ArrowLeft);
        press(KeyType.ArrowLeft);
        tick();
        assertEquals(Intent.PLAYER_COLORS - 1, player.colorIndex);
    }

    @Test
    void theSecondRowTogglesRunning() {
        type(',');
        press(KeyType.ArrowDown);
        press(KeyType.Enter);
        tick();
        assertTrue(player.running);
    }

    @Test
    void movementStillWorksWithSettingsOpen() {
        type(',');
        type('d');
        tick();
        assertEquals(1, player.x);
    }

    @Test
    void aCommaWhileTypingACommandIsJustText() {
        type('/');
        type(',');
        assertFalse(tabs.overlayOpen());
    }
}
