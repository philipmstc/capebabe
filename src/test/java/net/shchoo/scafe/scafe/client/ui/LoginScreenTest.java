package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.protocol.GameConnection;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LoginScreenTest {
    private GameServer server;
    private final List<String> connects = new ArrayList<>();
    private LoginScreen login;

    @BeforeEach
    void setUp() {
        server = new GameServer(new World(1, "w", 1));
        server.setDiscovery(Discovery.off());
        server.setWorldPassword("open sesame");
        login = new LoginScreen(this::connect, "", "");
    }

    /** "" = this computer's world; "friend" = over the network (so it wants the world password); else it fails. */
    private LoginScreen.Connection connect(String target) throws IOException {
        connects.add(target);
        GameConnection connection;
        if (target.isEmpty()) {
            connection = new LocalConnection(server.connect());
        } else if (target.equals("friend")) {
            connection = new LocalConnection(server.connectRemote(false, "1.2.3.4"));
        } else {
            throw new IOException("Couldn't connect to " + target + ":43594: Connection refused");
        }
        return new LoginScreen.Connection(connection, new Player(connection), "server " + target,
                target.isEmpty() ? "" : "server fingerprint AB:CD:EF");
    }

    private void type(String text) {
        for (char c : text.toCharArray()) {
            login.onKey(new KeyStroke(c, false, false));
        }
    }

    private LoginScreen.Outcome press(KeyType key) {
        return login.onKey(new KeyStroke(key));
    }

    /** Ticks until the login screen is done or has had its say. */
    private LoginScreen.Outcome settle() {
        LoginScreen.Outcome outcome = null;
        for (int i = 0; i < 4 && outcome == null; i++) {
            server.tick();
            outcome = login.update();
        }
        return outcome;
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

    private boolean onOneLine(String a, String b) throws IOException {
        return Arrays.stream(screenText().split("\n")).anyMatch(line -> line.contains(a) && line.contains(b));
    }

    /** Page one: name, password, Login. */
    private void account(String name, String password) {
        type(name);
        press(KeyType.Enter);
        type(password);
        press(KeyType.Enter);
    }

    /** Page two: host, world password, Continue. */
    private void world(String host, String worldPassword) {
        type(host);
        press(KeyType.Enter);
        type(worldPassword);
        press(KeyType.Enter);
    }

    @Test
    void pageOneIsNameAndPasswordWithTheButtonsSideBySide() throws IOException {
        String shown = screenText();
        assertTrue(shown.contains("capebabe"));
        assertTrue(shown.contains("Name") && shown.contains("Password"));
        assertFalse(shown.contains("Host"), "the host is the second page's");
        assertTrue(onOneLine("Login", "Exit"));
    }

    @Test
    void pageTwoIsHostAndWorldPassword() throws IOException {
        account("alice", "hunter22");
        assertTrue(connects.isEmpty(), "nothing's connected until a host is picked");
        String shown = screenText();
        assertTrue(shown.contains("Join a world"));
        assertTrue(shown.contains("Host") && shown.contains("World Password"));
        assertTrue(shown.contains("as alice"));
        assertTrue(onOneLine("Continue", "Switch User"));
    }

    @Test
    void aBlankHostIsYourOwnWorld() throws IOException {
        account("alice", "hunter22");
        assertFalse(screenText().contains("hunter22"), "passwords are hidden");
        world("", "");
        assertEquals(LoginScreen.Outcome.IN_WORLD, settle(), "no world password asked of your own world");
        assertEquals(List.of(""), connects);
        assertEquals("alice", login.connection().player().name);
    }

    @Test
    void aFriendsWorldWithItsWorldPassword() throws IOException {
        account("alice", "hunter22");
        world("friend", "open sesame");
        assertEquals(LoginScreen.Outcome.IN_WORLD, settle());
        assertEquals("friend", login.server());
        assertTrue(screenText().contains("server fingerprint AB:CD:EF"), "the fingerprint shows once connected");
    }

    @Test
    void aWrongWorldPasswordStaysOnPageTwoAndReusesTheConnection() throws IOException {
        account("alice", "hunter22");
        world("friend", "open says me");
        assertNull(settle());
        String shown = screenText();
        assertTrue(shown.contains("Join a world"));
        assertTrue(shown.contains("Wrong world password."));

        type("open sesame");
        press(KeyType.Enter);
        assertEquals(LoginScreen.Outcome.IN_WORLD, settle());
        assertEquals(List.of("friend"), connects, "connected once");
    }

    @Test
    void aWrongAccountPasswordGoesBackToPageOne() throws IOException {
        Player first = new Player(new LocalConnection(server.connect()));
        first.login("alice", "hunter22");
        server.tick();
        first.receive();
        first.enterWorld(null);
        server.tick();
        first.receive();
        first.send(new Intent.Leave());
        server.tick();

        account("alice", "wrong-one");
        world("", "");
        assertNull(settle());
        String shown = screenText();
        assertTrue(shown.contains("capebabe") && !shown.contains("Join a world"), "back on page one");
        assertTrue(shown.contains("Wrong name or password."));

        type("hunter22");
        press(KeyType.Enter);
        world("", "");
        assertEquals(LoginScreen.Outcome.IN_WORLD, settle());
    }

    @Test
    void aHostThatCantBeReachedSaysSoOnPageTwo() throws IOException {
        account("alice", "hunter22");
        world("nowhere", "");
        String shown = screenText();
        assertTrue(shown.contains("Join a world"));
        assertTrue(shown.contains("Couldn't connect to nowhere:43594: Connection refused"));
        assertNull(login.connection());
    }

    @Test
    void switchUserGoesBackToPageOne() throws IOException {
        account("alice", "hunter22");
        press(KeyType.ArrowDown);
        press(KeyType.ArrowDown);
        press(KeyType.ArrowRight);
        press(KeyType.Enter);
        assertTrue(screenText().contains("capebabe"));
        assertFalse(screenText().contains("Join a world"));

        account("bob", "pw1234");
        press(KeyType.Escape);
        assertFalse(screenText().contains("Join a world"), "Esc on page two is Switch User too");
    }

    @Test
    void hostingIsJustPageOne() throws IOException {
        GameConnection local = new LocalConnection(server.connect());
        login = new LoginScreen(new LoginScreen.Connection(local, new Player(local), "your world (hosting on port 43594)",
                "your fingerprint AB:CD:EF"), "phili");
        String[] lines = screenText().split("\n");
        assertTrue(lines[lines.length - 1].contains("your fingerprint AB:CD:EF"), "the fingerprint is on the bottom line");
        type("pw1234");
        press(KeyType.Enter);
        assertEquals(LoginScreen.Outcome.IN_WORLD, settle(), "Login goes straight in");
    }

    @Test
    void exitAndEscQuit() {
        press(KeyType.ArrowDown);
        press(KeyType.ArrowDown);
        press(KeyType.ArrowRight);
        assertEquals(LoginScreen.Outcome.EXIT, press(KeyType.Enter), "Down to the buttons, Right to Exit");
        assertEquals(LoginScreen.Outcome.EXIT, press(KeyType.Escape));
    }
}
