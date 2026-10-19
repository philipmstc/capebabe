package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.model.World;
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

import static org.junit.jupiter.api.Assertions.*;

class LoginScreenTest {
    private GameServer server;
    private Player player;
    private LoginScreen login;

    @BeforeEach
    void setUp() {
        server = new GameServer(new World(1, "w", 1));
        server.setDiscovery(Discovery.off());
        player = new Player(new LocalConnection(server.connect()));
        login = new LoginScreen(player, "your own world", "");
    }

    private LoginScreen.Outcome type(String text) {
        LoginScreen.Outcome outcome = null;
        for (char c : text.toCharArray()) {
            outcome = login.onKey(new KeyStroke(c, false, false));
        }
        return outcome;
    }

    private LoginScreen.Outcome press(KeyType key) {
        return login.onKey(new KeyStroke(key));
    }

    private LoginScreen.Outcome serverTurn() {
        server.tick();
        player.receive();
        return login.update();
    }

    private String screenText() throws IOException {
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(Layout.TERMINAL_SIZE));
        screen.startScreen();
        login.draw(screen);
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
    void typeANameAndPasswordThenLogIn() throws IOException {
        String shown = screenText();
        assertTrue(shown.contains("capebabe"));
        assertTrue(shown.contains("Name:") && shown.contains("Password:") && shown.contains("Log In") && shown.contains("Exit"));

        type("alice");
        press(KeyType.Enter);
        type("hunter22");
        assertTrue(screenText().contains("********"), "the password is hidden");
        assertFalse(screenText().contains("hunter22"));
        assertNull(press(KeyType.Enter), "Enter on the password logs in");
        assertNull(serverTurn());
        assertEquals(LoginScreen.Outcome.IN_WORLD, serverTurn(), "no world password locally, so straight in");
        assertEquals("alice", player.name);
    }

    @Test
    void aWrongPasswordShowsWhyAndYouCanTryAgain() throws IOException {
        type("alice");
        press(KeyType.Tab);
        type("hunter22");
        press(KeyType.Enter);
        serverTurn();
        serverTurn();
        assertTrue(player.inWorld());

        Player second = new Player(new LocalConnection(server.connect()));
        login = new LoginScreen(second, "your own world", "alice");
        player = second;
        type("nope");
        press(KeyType.Enter);
        serverTurn();
        assertTrue(screenText().contains("already playing"), "alice is still in");
    }

    @Test
    void exitAndEscQuit() {
        press(KeyType.ArrowDown);
        press(KeyType.ArrowDown);
        press(KeyType.ArrowDown);
        assertEquals(LoginScreen.Outcome.EXIT, press(KeyType.Enter), "the Exit button");
        assertEquals(LoginScreen.Outcome.EXIT, press(KeyType.Escape));
    }
}
