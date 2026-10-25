package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.GameLog;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.protocol.GameClock;
import net.shchoo.scafe.scafe.protocol.GameConnection;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
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
 *        capebabe                         Join a world                              Connect to a server
 *
 *            Name                                                                          Host
 *        Password                                                                World Password
 *
 *      > Login   Exit         > Play locally   Connect to server   Switch User      > Continue   Back
 * </pre>
 * Accounts live on the server, so the name and password are only checked once a world is picked: Play locally
 * (your own world here) or Continue connects (through {@link Connector}; "" = your own world), logs in and enters
 * the world. A wrong account password sends you back to the first page; a wrong world password, or a host that
 * can't be reached, keeps you on the server page. When hosting there's only the first page: Login goes straight
 * into your own world. Up/Down move between rows, Left/Right between buttons, Tab through everything, Enter moves
 * on or presses, Esc goes back a page (quits from the first).
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
    static final String SERVER_TITLE = "Connect to a server";
    private static final int FIELD_WIDTH = 20;
    private static final int HOST_WIDTH = 30;
    private static final int MAX_PASSWORD = 64;
    private static final int MAX_HOST = 100;

    private enum Page { ACCOUNT, CHOOSE, SERVER }

    enum Item {
        NAME("Name"), PASSWORD("Password"), LOGIN("Login"), EXIT("Exit"),
        LOCAL("Play locally"), SERVER("Connect to server"), SWITCH_USER("Switch User"),
        HOST("Host"), WORLD_PASSWORD("World Password"), CONTINUE("Continue"), BACK("Back");

        final String label;

        Item(String label) {
            this.label = label;
        }

        boolean button() {
            return this != NAME && this != PASSWORD && this != HOST && this != WORLD_PASSWORD;
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

    /** Pick your own world or a host (prefilled with {@code host}) on the next pages. */
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

    /** The host to offer next time: the last one typed, even if you then played locally. */
    public String server() {
        return host.trim();
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
                    if (key instanceof MouseAction mouse) {
                        if (mouse.getActionType() != MouseActionType.CLICK_DOWN || mouse.getButton() != 1) {
                            continue;
                        }
                        // a click picks a field, or presses a button (as Enter would)
                        Item item = itemAt(mouse.getPosition().getColumn(), mouse.getPosition().getRow());
                        if (item == null) {
                            continue;
                        }
                        selected = item;
                        if (!item.button()) {
                            continue;
                        }
                        key = new KeyStroke(KeyType.Enter);
                    }
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
        return switch (page) {
            case ACCOUNT -> !pickHost && (selected == Item.LOGIN || selected == Item.PASSWORD);
            case CHOOSE -> selected == Item.LOCAL;
            case SERVER -> (selected == Item.CONTINUE || selected == Item.WORLD_PASSWORD) && !host.isBlank();
        };
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
            if (waiting == Waiting.ENTER && page == Page.SERVER) {
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
                switch (page) {
                    case ACCOUNT -> {
                        return Outcome.EXIT;
                    }
                    case CHOOSE -> switchUser();
                    case SERVER -> toChoosePage();
                }
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
        return switch (page) {
            case ACCOUNT -> List.of(Item.NAME, Item.PASSWORD, Item.LOGIN, Item.EXIT);
            case CHOOSE -> List.of(Item.LOCAL, Item.SERVER, Item.SWITCH_USER);
            case SERVER -> List.of(Item.HOST, Item.WORLD_PASSWORD, Item.CONTINUE, Item.BACK);
        };
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
            case PASSWORD, LOGIN -> toChoosePage();
            case LOCAL -> logIn("");
            case SERVER -> {
                page = Page.SERVER;
                selected = host.isBlank() ? Item.HOST : Item.WORLD_PASSWORD;
                status = "";
            }
            case WORLD_PASSWORD, CONTINUE -> {
                if (host.isBlank()) {
                    status = "Type a host first.";
                    statusIsError = true;
                    selected = Item.HOST;
                } else {
                    logIn(host.trim());
                }
            }
            case BACK -> toChoosePage();
            case SWITCH_USER -> switchUser();
            case EXIT -> {
                return Outcome.EXIT;
            }
        }
        return null;
    }

    /** First page done: on to picking a world (hosting: straight in). */
    private void toChoosePage() {
        if (name.isBlank()) {
            status = "Type a name first.";
            statusIsError = true;
            selected = Item.NAME;
            return;
        }
        if (!pickHost) {
            logIn("");
            return;
        }
        page = Page.CHOOSE;
        selected = host.isBlank() ? Item.LOCAL : Item.SERVER;
        status = "";
    }

    private void switchUser() {
        page = Page.ACCOUNT;
        password = "";
        selected = Item.NAME;
        status = "";
    }

    private void logIn(String target) {
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
                if (page == Page.SERVER) {
                    selected = Item.HOST;
                }
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

    /** Where each field and button was last drawn: what a click there picks. */
    private final java.util.Map<Item, Rect> drawnAt = new java.util.EnumMap<>(Item.class);

    /** The field or button at screen cell {@code x}, {@code y} on this page, or null. */
    Item itemAt(int x, int y) {
        for (Item item : items()) {
            Rect r = drawnAt.get(item);
            if (r != null && x >= r.x() && y >= r.y() && x < r.x() + r.width() && y < r.y() + r.height()) {
                return item;
            }
        }
        return null;
    }

    void draw(Screen screen) {
        drawnAt.clear();
        int columns = screen.getTerminalSize().getColumns();
        int rows = screen.getTerminalSize().getRows();
        int top = Math.max(1, rows / 3 - 4);
        TextGraphics g = screen.newTextGraphics();
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);

        String title = switch (page) {
            case ACCOUNT -> TITLE;
            case CHOOSE -> WORLD_TITLE;
            case SERVER -> SERVER_TITLE;
        };
        g.setForegroundColor(TextColor.ANSI.YELLOW_BRIGHT);
        g.enableModifiers(SGR.BOLD);
        g.putString((columns - title.length()) / 2, top, title);
        g.disableModifiers(SGR.BOLD);
        String under;
        if (page == Page.ACCOUNT) {
            under = pickHost || connection == null ? "" : connection.where();
        } else if (page == Page.SERVER && connection != null && !connectedTo.isEmpty() && host.trim().equals(connectedTo)) {
            under = connection.where();
        } else {
            under = "as " + name;
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
            int width = item == Item.HOST ? HOST_WIDTH : FIELD_WIDTH;
            field(g, fieldX, y, item.label, value, secret, width, isSelected);
            drawnAt.put(item, new Rect(fieldX - 2 - item.label.length(), y, item.label.length() + 2 + width, 1));
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
            drawnAt.put(button, new Rect(x, y, 2 + button.label.length(), 1));
            g.disableModifiers(SGR.BOLD);
            x += 2 + button.label.length() + 4;
        }
        if (!status.isEmpty()) {
            g.setForegroundColor(statusIsError ? TextColor.ANSI.RED_BRIGHT : TextColor.ANSI.YELLOW);
            String line = Layout.fit(status, columns - 4);
            g.putString((columns - line.length()) / 2, y + 3, line);
        }
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        String back = switch (page) {
            case ACCOUNT -> "quit";
            case CHOOSE -> "switch user";
            case SERVER -> "back";
        };
        String hint = busy ? "[Esc] quit" : "[Up/Down/Left/Right/Tab] move  [Enter] next / press  [Esc] " + back;
        g.putString((columns - hint.length()) / 2, rows - 2, hint);
        String footer = connection == null || page == Page.CHOOSE ? "" : connection.footer();
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
        String cell = visible + (isSelected ? " " : "");
        g.putString(fieldX, y, cell + (" ").repeat(Math.max(0, width - cell.length())));
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
    }
}
