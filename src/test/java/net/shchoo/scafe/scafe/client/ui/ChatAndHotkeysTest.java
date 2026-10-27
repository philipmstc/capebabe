package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.LogEntry;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.SkillStyle;
import net.shchoo.scafe.scafe.model.Goal;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.Skill;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.protocol.Event;
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

class ChatAndHotkeysTest {
    private GameServer server;
    private Player player;
    private Board board;
    private CommandLine commandLine;
    private TabBar tabs;
    private List<Widget> widgets;

    @BeforeEach
    void setUp() {
        server = new GameServer(new World(1, "w", 1));
        server.setDiscovery(Discovery.off());
        player = new Player(new LocalConnection(server.connect()));
        board = new Board(player.content);
        player.send(new Intent.Join("alice"));
        player.send(new Intent.Admin("1", List.of("2")));
        tick();
        commandLine = new CommandLine();
        tabs = new TabBar(Layout.TABS, new InventoryPanel(Layout.INVENTORY), new MapMenu(),
                List.of(new SkillsMenu(), new MonstersMenu(), new StatsMenu()), new SettingsMenu());
        widgets = List.of(commandLine, tabs, new KeyCommands(() -> {}));
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

    private void type(String text) {
        for (char c : text.toCharArray()) {
            press(new KeyStroke(c, false, false));
        }
    }

    private Screen drawLog() throws IOException {
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(Layout.TERMINAL_SIZE));
        screen.startScreen();
        new MessageLogView(Layout.LOG, commandLine).draw(screen, player, board);
        return screen;
    }

    private static String row(Screen screen, int y) {
        StringBuilder line = new StringBuilder();
        for (int x = Layout.LOG.x(); x <= Layout.LOG.right(); x++) {
            line.append(screen.getBackCharacter(x, y).getCharacterString());
        }
        return line.toString();
    }

    @Test
    void compactChatHidesOnlyTheRoutineLines() throws IOException {
        board.log.add(LogEntry.of(Event.MessageKind.ROUTINE, "Made skill0{0}-0"));
        board.log.add(LogEntry.of(Event.MessageKind.NOTICE, "skill0 is now Lv. 2!"));
        board.log.add(LogEntry.chat("bob", 3, "nice"));
        assertEquals(3, board.log.latest(10, false).size());
        List<LogEntry> compact = board.log.latest(10, true);
        assertEquals(List.of("skill0 is now Lv. 2!", "nice"), compact.stream().map(LogEntry::text).toList());

        player.compactChat = true;
        Screen screen = drawLog();
        String all = String.join("\n", java.util.stream.IntStream.rangeClosed(Layout.LOG.y(), Layout.LOG.bottom())
                .mapToObj(y -> row(screen, y)).toList());
        assertFalse(all.contains("Made skill0"));
        assertTrue(all.contains("bob: nice"));
    }

    @Test
    void chatShowsTheSpeakersNameInTheirColour() throws IOException {
        board.log.add(LogEntry.chat("bob", 3, "nice"));
        Screen screen = drawLog();
        int y = Layout.LOG.y() + 1;
        int x = Layout.LOG.x() + 1;
        TextCharacter b = screen.getBackCharacter(x, y);
        assertEquals("b", b.getCharacterString());
        assertEquals(SkillStyle.playerColor(3), b.getForegroundColor());
        assertNotEquals(SkillStyle.playerColor(3), screen.getBackCharacter(x + 5, y).getForegroundColor(), "the text isn't");
    }

    @Test
    void slashTypesAtTheBottomOfTheLogAndSaysItToEveryone() throws IOException {
        type("/");
        type("hi all");
        assertTrue(row(drawLog(), Layout.LOG.bottom() - 1).contains("> hi all_"), "typed on the box's last row");
        List<String> heard = new java.util.ArrayList<>();
        java.util.function.Consumer<String> listener = heard::add;
        net.shchoo.scafe.scafe.client.GameLog.subscribe(listener);
        try {
            press(new KeyStroke(KeyType.Enter));
            tick();
        } finally {
            net.shchoo.scafe.scafe.client.GameLog.unsubscribe(listener);
        }
        assertTrue(heard.contains("alice: hi all"));
        assertFalse(row(drawLog(), Layout.LOG.bottom() - 1).contains("hi all"), "the input row is clear again");
    }

    @Test
    void aLineStartingWithSlashIsACommand() {
        int before = player.content.skills.size();
        type("/");
        type("/1 2");
        press(new KeyStroke(KeyType.Enter));
        tick();
        assertEquals(before + 2, player.content.skills.size(), "//1 2 made two skills");
    }

    @Test
    void numbersJumpToTabsAndEscGoesBackToTheMap() {
        type("1");
        assertEquals("Skills", tabs.selected().title());
        assertTrue(tabs.menuFocused());
        press(new KeyStroke(KeyType.Escape));
        assertFalse(tabs.menuFocused(), "back to the map");

        type("3");
        assertEquals("Stats", tabs.selected().title());
        type("4");
        assertTrue(tabs.settingsOpen(), "4 = Settings");
        type("i");
        assertEquals("Inventory", tabs.selected().title());
    }

    @Test
    void vHoversTheMap() {
        type("v");
        assertTrue(tabs.map().isFocused());
        press(new KeyStroke(KeyType.Escape));
        assertFalse(tabs.map().isFocused());
        assertFalse(tabs.menuFocused());
    }

    @Test
    void digitsNoLongerRunDebugCommands() {
        long xp = player.content.skills.stream().mapToLong(player::getExperience).sum();
        type("5");
        tick();
        assertEquals(xp, player.content.skills.stream().mapToLong(player::getExperience).sum(), "5 used to give 10,000 xp");
    }
}
