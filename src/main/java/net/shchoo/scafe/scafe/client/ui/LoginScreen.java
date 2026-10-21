package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.GameLog;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.protocol.GameClock;
import net.shchoo.scafe.scafe.protocol.GameConnection;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The first screens, so the game can be started with a double click:
 * <pre>
 *        capebabe                         Join a world
 *
 *            Name ____                            Host ____
 *        Password ____                  World Password ____
 *
 *      > Login   Exit                 > Continue   Switch User
 * </pre>
 * Accounts live on the server, so the name and password are only checked once a host is picked: Continue connects
 * (through {@link Connector}; a blank host = your own world here), logs in and enters the world. A wrong account
 * password sends you back to the first page; a wrong world password, or a host that can't be reached, keeps you
 * on the second. When hosting there's no second page: Login goes straight into your own world.
 * Up/Down move between rows, Left/Right between buttons, Tab through everything, Enter moves on or presses,
 * Esc quits (on the second page: back to the first).
 */
public class LoginScreen {
    public enum Outcome { IN_WORLD, EXIT }

    /** Opens a connection to {@code server} ("" = your own world here); throws with a message to show if it can't. */
    public interface Connector {
        Connection connect(String server) throws Exception;
    }

    /**
     * A connection, the player on it, the line under the title ("server 1.2.3.4:43594 ...") and the bottom line
     * (the server's fingerprint, "" when there's none).
     */
    public record Connection(GameConnection connection, Player player, String where, String footer) {}

    static final String TITLE = "capebabe";
    static final String WORLD_TITLE = "Join a world";
    private static final int FIELD_WIDTH = 20;
    private static final int HOST_WIDTH = 30;
    private static final int MAX_PASSWORD = 64;
    private static final int MAX_HOST = 100;

    private enum Page { ACCOUNT, WORLD }

    private enum Item {
        NAME("Name"), PASSWORD("Password"), LOGIN("Login"), EXIT("Exit"),
        HOST("Host"), WORLD_PASSWORD("World Password"), CONTINUE("Continue"), SWITCH_USER("Switch User");

        final String label;

        Item(String label) {
            this.label = label;
        }

        boolean button() {
            return this == LOGIN || this == EXIT || this == CONTINUE || this == SWITCH_USER;
        }
    }

    private enum Waiting { NOTHING, LOGIN, ENTER }

    private final Connector connector;
    private final boolean pickHost;
    private Connection connection;
    /** The host text the current connection was made for. */
    private String connectedTo;
    /** What the log said since the current connection was made ("Welcome to ..."), for the game's log. */
    private final List<String> heard = new ArrayList<>();

    private Page page = Page.ACCOUNT;
    private Waiting waiting = Waiting.NOTHING;
    private Item selected;
    private String host;
    private String name;
    private String password = "";
    private String worldPassword = "";
    private String status = "";
    private boolean statusIsError;

    /** Pick a host on the second page (prefilled with {@code host}, "" = your own world). */
    public LoginScreen(Connector connector, String host, String defaultName) {
        this(connector, true, host, defaultName);
    }

    /** Already connected (hosting, or tests): just the first page. */
    public LoginScreen(Connection fixed, String defaultName) {
        this(s -> fixed, false, "", defaultName);
    }

    private LoginScreen(Connector connector, boolean pickHost, String host, String defaultName) {
        this.connector = connector;
        this.pickHost = pickHost;
        this.host = host == null ? "" : host;
        this.name = defaultName == null ? "" : defaultName;
        selected = this.name.isEmpty() ? Item.NAME : Item.PASSWORD;
        if (!pickHost) {
            connectedTo = "";
            try {
                use(connector.connect(""));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** The connection you got in on (after IN_WORLD). */
    public Connection connection() {
        return connection;
    }

    /** The host you got in on, as typed ("" = your own world). */
    public String server() {
        return connectedTo == null ? host : connectedTo;
    }

    /** What the log said on the way in (the welcome, who's here), to start the game's log with. */
    public List<String> heard() {
        return List.copyOf(heard);
    }

    /** Runs until you're in the world or choose Exit. */
    public Outcome run(Screen screen) throws IOException, InterruptedException {
        Consumer<String> messages = text -> {
            heard.add(text);
            status = text;
            statusIsError = true;
        };
        GameLog.subscribe(messages);
        try {
            while (true) {
                long frameStart = System.currentTimeMillis();
                Outcome outcome = update();
                if (outcome != null) {
                    return outcome;
                }
                screen.clear();
                draw(screen);
                screen.refresh();
                KeyStroke key;
                while ((key = screen.pollInput()) != null) {
                    if (key.getKeyType() == KeyType.Enter && submits()) {
                        // connecting can take a moment: show that before it starts
                        status = "Connecting...";
                        statusIsError = false;
                        screen.clear();
                        draw(screen);
                        screen.refresh();
                    }
                    outcome = onKey(key);
                    if (outcome != null) {
                        return outcome;
                    }
                }
                Thread.sleep(Math.max(0, GameClock.FRAME_MILLIS - (System.currentTimeMillis() - frameStart)));
            }
        } finally {
            GameLog.unsubscribe(messages);
        }
    }

    /** Whether Enter on the selected item connects (so the screen should say so first). */
    private boolean submits() {
        return page == Page.WORLD ? selected == Item.CONTINUE || selected == Item.WORLD_PASSWORD
                : !pickHost && (selected == Item.LOGIN || selected == Item.PASSWORD);
    }

    /** Reacts to what the server said. */
    Outcome update() {
        if (connection == null) {
            return null;
        }
        Player player = connection.player();
        player.receive();
        if (player.inWorld()) {
            return Outcome.IN_WORLD;
        }
        if (player.loginRefused != null) {
            status = player.loginRefused;
            statusIsError = true;
            player.loginRefused = null;
            if (waiting == Waiting.ENTER) {
                worldPassword = "";
                selected = Item.WORLD_PASSWORD;
            } else if (waiting == Waiting.LOGIN) {
                // name / password / version trouble: that's the first page's business
                password = "";
                page = Page.ACCOUNT;
                selected = Item.PASSWORD;
            }
            waiting = Waiting.NOTHING;
        } else if (player.loginAccepted != null && waiting == Waiting.LOGIN) {
            boolean needed = player.loginAccepted.worldPasswordNeeded();
            player.loginAccepted = null;
            player.enterWorld(needed ? worldPassword : null);
            waiting = Waiting.ENTER;
            status = "Entering the world...";
            statusIsError = false;
        }
        return null;
    }

    Outcome onKey(KeyStroke key) {
        if (key.getKeyType() == KeyType.EOF) {
            return Outcome.EXIT;
        }
        if (waiting != Waiting.NOTHING) {
            return key.getKeyType() == KeyType.Escape ? Outcome.EXIT : null;
        }
        List<Item> items = items();
        int at = items.indexOf(selected);
        switch (key.getKeyType()) {
            case Tab -> selected = items.get((at + 1) % items.size());
            case ReverseTab -> selected = items.get((at + items.size() - 1) % items.size());
            case ArrowDown -> selected = rowStep(items, 1);
            case ArrowUp -> selected = rowStep(items, -1);
            case ArrowLeft, ArrowRight -> {
                if (selected.button()) {
                    List<Item> buttons = items.stream().filter(Item::button).toList();
                    int b = buttons.indexOf(selected);
                    int step = key.getKeyType() == KeyType.ArrowRight ? 1 : -1;
                    selected = buttons.get((b + step + buttons.size()) % buttons.size());
                }
            }
            case Escape -> {
                if (page == Page.ACCOUNT) {
                    return Outcome.EXIT;
                }
                switchUser();
            }
            case Enter -> {
                return press(items, at);
            }
            case Backspace -> edit(text -> text.isEmpty() ? text : text.substring(0, text.length() - 1));
            case Character -> {
                char c = key.getCharacter();
                if (!Character.isISOControl(c)) {
                    edit(text -> text + c);
                }
            }
            default -> {}
        }
        return null;
    }

    private List<Item> items() {
        return page == Page.ACCOUNT ? List.of(Item.NAME, Item.PASSWORD, Item.LOGIN, Item.EXIT)
                : List.of(Item.HOST, Item.WORLD_PASSWORD, Item.CONTINUE, Item.SWITCH_USER);
    }

    /** Up/Down: each field is a row, the buttons share one (arriving on it picks its first button). */
    private Item rowStep(List<Item> items, int step) {
        List<Item> rows = items.stream().filter(i -> !i.button() || i == firstButton(items)).toList();
        Item row = selected.button() ? firstButton(items) : selected;
        return rows.get((rows.indexOf(row) + step + rows.size()) % rows.size());
    }

    private static Item firstButton(List<Item> items) {
        return items.stream().filter(Item::button).findFirst().orElseThrow();
    }

    private Outcome press(List<Item> items, int at) {
        switch (selected) {
            case NAME, HOST -> selected = items.get(at + 1);
            case PASSWORD, LOGIN -> toWorldPage();
            case WORLD_PASSWORD, CONTINUE -> logIn();
            case SWITCH_USER -> switchUser();
            case EXIT -> {
                return Outcome.EXIT;
            }
        }
        return null;
    }

    /** First page done: on to picking a world (hosting: straight in). */
    private void toWorldPage() {
        if (name.isBlank()) {
            status = "Type a name first.";
            statusIsError = true;
            selected = Item.NAME;
            return;
        }
        if (!pickHost) {
            logIn();
            return;
        }
        page = Page.WORLD;
        selected = host.isBlank() ? Item.HOST : Item.WORLD_PASSWORD;
        status = "";
    }

    private void switchUser() {
        page = Page.ACCOUNT;
        password = "";
        selected = Item.NAME;
        status = "";
    }

    private void logIn() {
        String target = host.trim();
        if (connection == null || !target.equals(connectedTo) || !connection.connection().isOpen()) {
            if (connection != null && pickHost) {
                connection.connection().close();
            }
            try {
                use(connector.connect(target));
            } catch (Exception e) {
                connection = null;
                status = e.getMessage() == null ? e.toString() : e.getMessage();
                statusIsError = true;
                selected = Item.HOST;
                return;
            }
            connectedTo = target;
        }
        connection.player().login(name, password);
        waiting = Waiting.LOGIN;
        status = "Logging in...";
        statusIsError = false;
    }

    private void use(Connection made) {
        connection = made;
        heard.clear();
    }

    private interface Edit {
        String apply(String text);
    }

    private void edit(Edit edit) {
        switch (selected) {
            case HOST -> host = limit(edit.apply(host), MAX_HOST);
            case NAME -> name = limit(edit.apply(name), FIELD_WIDTH);
            case PASSWORD -> password = limit(edit.apply(password), MAX_PASSWORD);
            case WORLD_PASSWORD -> worldPassword = limit(edit.apply(worldPassword), MAX_PASSWORD);
            default -> {}
        }
    }

    private static String limit(String text, int max) {
        return text.length() > max ? text.substring(0, max) : text;
    }

    void draw(Screen screen) {
        int columns = screen.getTerminalSize().getColumns();
        int rows = screen.getTerminalSize().getRows();
        int top = Math.max(1, rows / 3 - 4);
        TextGraphics g = screen.newTextGraphics();
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);

        String title = page == Page.ACCOUNT ? TITLE : WORLD_TITLE;
        g.setForegroundColor(TextColor.ANSI.YELLOW_BRIGHT);
        g.enableModifiers(SGR.BOLD);
        g.putString((columns - title.length()) / 2, top, title);
        g.disableModifiers(SGR.BOLD);
        String under;
        if (page == Page.ACCOUNT) {
            under = pickHost || connection == null ? "" : connection.where();
        } else if (connection != null && host.trim().equals(connectedTo)) {
            under = connection.where();
        } else {
            under = host.isBlank() ? "as " + name + " -- leave Host blank to play your own world" : "as " + name;
        }
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        g.putString((columns - under.length()) / 2, top + 1, under);

        // labels end just left of the middle, fields start just right of it
        int fieldX = columns / 2 - 2;
        int y = top + 4;
        boolean busy = waiting != Waiting.NOTHING;
        List<Item> buttons = new ArrayList<>();
        for (Item item : items()) {
            if (item.button()) {
                buttons.add(item);
                continue;
            }
            boolean isSelected = !busy && item == selected;
            String value = switch (item) {
                case NAME -> name;
                case PASSWORD -> password;
                case HOST -> host;
                default -> worldPassword;
            };
            boolean secret = item == Item.PASSWORD || item == Item.WORLD_PASSWORD;
            field(g, fieldX, y, item.label, value, secret, item == Item.HOST ? HOST_WIDTH : FIELD_WIDTH, isSelected);
            y += 2;
        }
        y++;
        StringBuilder row = new StringBuilder();
        for (Item button : buttons) {
            row.append(row.isEmpty() ? "" : "    ").append("  ").append(button.label);
        }
        int x = (columns - row.length()) / 2;
        for (Item button : buttons) {
            boolean isSelected = !busy && button == selected;
            g.setForegroundColor(isSelected ? TextColor.ANSI.WHITE_BRIGHT : TextColor.ANSI.DEFAULT);
            if (isSelected) {
                g.enableModifiers(SGR.BOLD);
            }
            g.putString(x, y, (isSelected ? "> " : "  ") + button.label);
            g.disableModifiers(SGR.BOLD);
            x += 2 + button.label.length() + 4;
        }
        if (!status.isEmpty()) {
            g.setForegroundColor(statusIsError ? TextColor.ANSI.RED_BRIGHT : TextColor.ANSI.YELLOW);
            String line = Layout.fit(status, columns - 4);
            g.putString((columns - line.length()) / 2, y + 3, line);
        }
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        String back = page == Page.ACCOUNT ? "quit" : "switch user";
        String hint = busy ? "[Esc] quit" : "[Up/Down/Left/Right/Tab] move  [Enter] next / press  [Esc] " + back;
        g.putString((columns - hint.length()) / 2, rows - 2, hint);
        String footer = connection == null ? "" : connection.footer();
        if (!footer.isEmpty()) {
            String line = Layout.fit(footer, columns - 2);
            g.putString((columns - line.length()) / 2, rows - 1, line);
        }
    }

    /** A field with its label right-aligned to end just before {@code fieldX}. */
    private static void field(TextGraphics g, int fieldX, int y, String label, String value, boolean secret,
                              int width, boolean isSelected) {
        g.setForegroundColor(isSelected ? TextColor.ANSI.WHITE_BRIGHT : TextColor.ANSI.DEFAULT);
        g.putString(fieldX - 2 - label.length(), y, label);
        String shown = secret ? "*".repeat(value.length()) : value;
        String visible = shown.length() > width ? shown.substring(shown.length() - width) : shown;
        g.setBackgroundColor(isSelected ? TextColor.ANSI.BLACK_BRIGHT : TextColor.ANSI.DEFAULT);
        String cell = visible + (isSelected ? "_" : "");
        g.putString(fieldX, y, cell + (isSelected ? " " : "_").repeat(Math.max(0, width - cell.length())));
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
    }
}
