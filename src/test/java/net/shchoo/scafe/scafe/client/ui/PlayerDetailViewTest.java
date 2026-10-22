package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.protocol.Intent;
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

import static org.junit.jupiter.api.Assertions.*;

class PlayerDetailViewTest {
    private Player player;
    private Board board;
    private boolean menuFocused;
    private int logOuts;
    private PlayerDetailView view;

    @BeforeEach
    void setUp() {
        GameServer server = new GameServer(new World(1, "w", 1));
        player = new Player(new LocalConnection(server.connect()));
        board = new Board(player.content);
        player.send(new Intent.Join("alice"));
        server.tick();
        player.receive();
        view = new PlayerDetailView(Layout.PLAYER_DETAILS, () -> !menuFocused, () -> logOuts++);
    }

    private boolean press(KeyType key) {
        return view.onKey(new KeyStroke(key), player, board);
    }

    private boolean type(char c) {
        return view.onKey(new KeyStroke(c, false, false), player, board);
    }

    private String popupText() throws IOException {
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(Layout.TERMINAL_SIZE));
        screen.startScreen();
        view.draw(screen, player, board);
        view.drawOnTop(screen, player, board);
        StringBuilder text = new StringBuilder();
        for (int y = 0; y < Layout.TERMINAL_SIZE.getRows(); y++) {
            for (int x = 0; x < Layout.TERMINAL_SIZE.getColumns(); x++) {
                TextCharacter c = screen.getBackCharacter(x, y);
                text.append(c == null ? ' ' : c.getCharacterString());
            }
            text.append('\n');
        }
        return text.toString();
    }

    @Test
    void escSelectsLogOutAndEnterAsksBeforeLoggingOut() throws IOException {
        assertTrue(press(KeyType.Escape));
        assertTrue(view.active());
        assertTrue(press(KeyType.Enter));
        assertTrue(popupText().contains("Log out?"));
        assertTrue(press(KeyType.Enter), "No is picked to start with");
        assertEquals(0, logOuts);
        assertFalse(view.active());

        press(KeyType.Escape);
        press(KeyType.Enter);
        press(KeyType.ArrowLeft);
        press(KeyType.Enter);
        assertEquals(1, logOuts, "Yes");
    }

    @Test
    void yAndNAnswerToo() {
        press(KeyType.Escape);
        press(KeyType.Enter);
        assertTrue(type('n'));
        assertEquals(0, logOuts);
        press(KeyType.Escape);
        press(KeyType.Enter);
        assertTrue(type('y'));
        assertEquals(1, logOuts);
    }

    @Test
    void escStillBacksOutOfATabFirst() {
        menuFocused = true;
        assertFalse(press(KeyType.Escape), "left for the tab to back out of");
        assertFalse(view.active());
    }

    @Test
    void anyOtherKeyPutsLogOutDownAndCarriesOn() {
        press(KeyType.Escape);
        assertFalse(type('d'), "the d still walks");
        assertFalse(view.active());
        press(KeyType.Escape);
        assertTrue(press(KeyType.Escape), "Esc again just puts it down");
        assertFalse(view.active());
    }

    @Test
    void totalLevelIsShown() throws IOException {
        assertTrue(popupText().contains("[Total Lv. " + player.totalLevel() + "]"));
    }
}
