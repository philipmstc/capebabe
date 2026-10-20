package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.GameLog;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.protocol.Event;
import net.shchoo.scafe.scafe.protocol.GameClock;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/**
 * The first screen: the title, then Name / Password / Log In / Exit. Once the server accepts the name and password
 * it asks for the world's password if the world has one (Enter / Back). Up/Down/Tab move between items, typing
 * fills the selected field, Enter moves on or presses the selected button, Esc exits (or goes back).
 */
public class LoginScreen {
    public enum Outcome { IN_WORLD, EXIT }

    private enum Step { LOGIN, WORLD_PASSWORD, WAITING }

    static final String TITLE = "capebabe";
    private static final int FIELD_WIDTH = 20;
    private static final int MAX_PASSWORD = 64;

    private static final int NAME = 0, PASSWORD = 1, LOG_IN = 2, EXIT = 3;
    private static final int WORLD = 0, ENTER = 1, BACK = 2;

    private final Player player;
    /** Where we're connecting, for the line under the title ("your own world", "server 1.2.3.4:43594"). */
    private final String where;
    /** The bottom line: the server's encryption fingerprint, to compare with the host's (empty when playing alone). */
    private final String footer;

    private Step step = Step.LOGIN;
    /** What we were doing before WAITING, to go back to if we're refused. */
    private Step waitingFor = Step.LOGIN;
    private int selected = NAME;
    private String name;
    private String password = "";
    private String worldPassword = "";
    private String status = "";
    private boolean statusIsError;
    private boolean newAccount;

    public LoginScreen(Player player, String where, String defaultName) {
        this(player, where, "", defaultName);
    }

    public LoginScreen(Player player, String where, String footer, String defaultName) {
        this.player = player;
        this.where = where;
        this.footer = footer == null ? "" : footer;
        this.name = defaultName == null ? "" : defaultName;
        selected = this.name.isEmpty() ? NAME : PASSWORD;
    }

    /** Runs until you're in the world or choose Exit. */
    public Outcome run(Screen screen) throws IOException, InterruptedException {
        Consumer<String> messages = text -> {
            status = text;
            statusIsError = true;
        };
        GameLog.subscribe(messages);
        try {
            while (true) {
                long frameStart = System.currentTimeMillis();
                player.receive();
                Outcome outcome = update();
                if (outcome != null) {
                    return outcome;
                }
                screen.clear();
                draw(screen);
                screen.refresh();
                KeyStroke key;
                while ((key = screen.pollInput()) != null) {
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

    /** Reacts to what the server said. */
    Outcome update() {
        if (player.inWorld()) {
            return Outcome.IN_WORLD;
        }
        if (step != Step.WAITING) {
            if (player.loginRefused != null) {
                // turned away before we even asked (e.g. already connected from this address)
                status = player.loginRefused;
                statusIsError = true;
                player.loginRefused = null;
            }
            return null;
        }
        if (player.loginRefused != null) {
            status = player.loginRefused;
            statusIsError = true;
            player.loginRefused = null;
            step = waitingFor;
            selected = step == Step.LOGIN ? PASSWORD : WORLD;
            if (step == Step.LOGIN) {
                password = "";
            } else {
                worldPassword = "";
            }
        } else if (player.loginAccepted != null && waitingFor == Step.LOGIN) {
            Event.LoginAccepted accepted = player.loginAccepted;
            player.loginAccepted = null;
            newAccount = accepted.newAccount();
            if (accepted.worldPasswordNeeded()) {
                step = Step.WORLD_PASSWORD;
                selected = WORLD;
                status = newAccount ? "" : "";
                statusIsError = false;
            } else {
                player.enterWorld(null);
                waitingFor = Step.WORLD_PASSWORD;
                status = "Entering the world...";
                statusIsError = false;
            }
        }
        return null;
    }

    Outcome onKey(KeyStroke key) {
        if (key.getKeyType() == KeyType.EOF) {
            return Outcome.EXIT;
        }
        if (step == Step.WAITING) {
            return key.getKeyType() == KeyType.Escape ? Outcome.EXIT : null;
        }
        int items = step == Step.LOGIN ? 4 : 3;
        switch (key.getKeyType()) {
            case ArrowUp -> selected = (selected + items - 1) % items;
            case ArrowDown, Tab -> selected = (selected + 1) % items;
            case ReverseTab -> selected = (selected + items - 1) % items;
            case Escape -> {
                if (step == Step.LOGIN) {
                    return Outcome.EXIT;
                }
                back();
            }
            case Enter -> {
                return press();
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

    private Outcome press() {
        if (step == Step.LOGIN) {
            switch (selected) {
                case NAME -> selected = PASSWORD;
                case PASSWORD, LOG_IN -> logIn();
                case EXIT -> {
                    return Outcome.EXIT;
                }
                default -> {}
            }
        } else {
            switch (selected) {
                case WORLD, ENTER -> {
                    player.enterWorld(worldPassword);
                    waitingFor = Step.WORLD_PASSWORD;
                    step = Step.WAITING;
                    status = "Entering the world...";
                    statusIsError = false;
                }
                case BACK -> back();
                default -> {}
            }
        }
        return null;
    }

    private void logIn() {
        if (name.isBlank()) {
            status = "Type a name first.";
            statusIsError = true;
            selected = NAME;
            return;
        }
        player.login(name, password);
        waitingFor = Step.LOGIN;
        step = Step.WAITING;
        status = "Logging in...";
        statusIsError = false;
    }

    /** From the world password back to name / password (the server forgets the login when we log in again). */
    private void back() {
        step = Step.LOGIN;
        selected = PASSWORD;
        worldPassword = "";
        status = "";
    }

    private interface Edit {
        String apply(String text);
    }

    private void edit(Edit edit) {
        if (step == Step.LOGIN && selected == NAME) {
            name = limit(edit.apply(name), FIELD_WIDTH);
        } else if (step == Step.LOGIN && selected == PASSWORD) {
            password = limit(edit.apply(password), MAX_PASSWORD);
        } else if (step == Step.WORLD_PASSWORD && selected == WORLD) {
            worldPassword = limit(edit.apply(worldPassword), MAX_PASSWORD);
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

        g.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT);
        g.enableModifiers(SGR.BOLD);
        g.putString((columns - TITLE.length()) / 2, top, TITLE);
        g.disableModifiers(SGR.BOLD);
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        g.putString((columns - where.length()) / 2, top + 1, where);

        int labelX = columns / 2 - 16;
        int y = top + 4;
        boolean waiting = step == Step.WAITING;
        Step shown = waiting ? waitingFor : step;
        List<Item> items = shown == Step.LOGIN
                ? List.of(new Item("Name:", name, false), new Item("Password:", password, true),
                        new Item("Log In", null, false), new Item("Exit", null, false))
                : List.of(new Item("World password:", worldPassword, true),
                        new Item("Enter", null, false), new Item("Back", null, false));
        int fieldX = labelX + (shown == Step.LOGIN ? 11 : 17);
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            boolean isSelected = !waiting && i == selected;
            if (item.value != null) {
                g.setForegroundColor(isSelected ? TextColor.ANSI.WHITE_BRIGHT : TextColor.ANSI.DEFAULT);
                g.putString(labelX, y, item.label);
                String shownText = item.secret ? "*".repeat(item.value.length()) : item.value;
                String visible = shownText.length() > FIELD_WIDTH ? shownText.substring(shownText.length() - FIELD_WIDTH) : shownText;
                g.setBackgroundColor(isSelected ? TextColor.ANSI.BLACK_BRIGHT : TextColor.ANSI.DEFAULT);
                String cell = visible + (isSelected ? "_" : "");
                g.putString(fieldX, y, cell + (isSelected || !item.value().isEmpty() ? " " : "_").repeat(Math.max(0, FIELD_WIDTH - cell.length())));
                g.setBackgroundColor(TextColor.ANSI.DEFAULT);
                y += 2;
            } else {
                if (i > 0 && items.get(i - 1).value != null) {
                    y++;
                }
                g.setForegroundColor(isSelected ? TextColor.ANSI.WHITE_BRIGHT : TextColor.ANSI.DEFAULT);
                if (isSelected) {
                    g.enableModifiers(SGR.BOLD);
                }
                g.putString(labelX, y, (isSelected ? "> " : "  ") + item.label);
                g.disableModifiers(SGR.BOLD);
                y++;
            }
        }
        if (!status.isEmpty()) {
            g.setForegroundColor(statusIsError ? TextColor.ANSI.RED_BRIGHT : TextColor.ANSI.YELLOW);
            String line = Layout.fit(status, columns - 4);
            g.putString((columns - line.length()) / 2, y + 2, line);
        }
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        String hint = waiting ? "[Esc] quit" : "[Up/Down/Tab] move  [Enter] next / press  [Esc] " + (step == Step.LOGIN ? "quit" : "back");
        g.putString((columns - hint.length()) / 2, rows - 2, hint);
        if (!footer.isEmpty()) {
            String line = Layout.fit(footer, columns - 2);
            g.putString((columns - line.length()) / 2, rows - 1, line);
        }
    }

    private record Item(String label, String value, boolean secret) {}
}
