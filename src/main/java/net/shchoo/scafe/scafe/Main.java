package net.shchoo.scafe.scafe;

import com.googlecode.lanterna.*;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;
import com.googlecode.lanterna.terminal.ansi.ANSITerminal;
import com.googlecode.lanterna.terminal.ansi.CygwinTerminal;
import com.googlecode.lanterna.terminal.ansi.UnixTerminal;
import com.googlecode.lanterna.terminal.swing.AWTTerminalFontConfiguration;
import com.googlecode.lanterna.terminal.swing.SwingTerminalFontConfiguration;

import java.awt.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.List;
import java.nio.file.Path;

import static net.shchoo.scafe.scafe.ConsoleAction.*;

public class Main {

    public static final int BOARD_HEIGHT = 7;
    public static final int BOARD_WIDTH = 11;
    public static final TerminalSize TERMINAL_SIZE = new TerminalSize(100, 28);

    // POSITIONS
    public static final int BOARD_POS_X = 0;
    public static final int BOARD_POS_Y = 1;
    public static final int COMMAND_POS_X = 0;
    public static final int COMMAND_POS_Y = BOARD_POS_Y + 2 * BOARD_HEIGHT + 4;
    public static final int HEADER_POS_X = BOARD_POS_X + BOARD_WIDTH - 1;
    public static final int HEADER_POS_Y = 0;
    public static final int HEADER_GAP = 5;
    public static final int LIST_ITEM_POS_X = BOARD_POS_X + 2 * BOARD_WIDTH + 2;
    public static final int LIST_ITEM_POS_Y = 0;
    public static final int LOG_POS_X = 0;
    public static final int LOG_POS_Y = COMMAND_POS_Y + 1;
    public static final int LOG_LINES = 7;
    public static final int LIST_ROWS = COMMAND_POS_Y - 1 - LIST_ITEM_POS_Y;



    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        if (options.server()) {
            GameServer server = startServer(options);
            NetworkServer network = new NetworkServer(server, options.port(), options.admin());
            System.out.println(server.world.name + " is up on port " + network.port()
                    + (options.admin() ? " (admin commands on for everyone)" : ""));
            network.acceptForever();
            return;
        }
        GameConnection connection;
        if (options.connect() != null) {
            connection = SocketConnection.connect(options.connect());
        } else {
            GameServer server = startServer(options);
            if (options.host()) {
                NetworkServer network = new NetworkServer(server, options.port(), options.admin());
                network.startInBackground();
            }
            connection = new LocalConnection(server.connect());
        }
        runClient(options, connection);
    }

    /** Opens the save file, loads (or creates) the world and starts ticking. Saves on exit. */
    private static GameServer startServer(Options options) throws Exception {
        GameStore store = GameStore.openFile(options.db());
        World world = store.loadOrCreateWorld(options.worldId(), "world" + options.worldId(), new Random().nextLong());
        GameServer server = new GameServer(world, store);
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            try {
                store.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }));
        return server;
    }

    private static void runClient(Options options, GameConnection connection) throws Exception {
        Player player = new Player(connection);
        Board board = new Board(player.content);
        GameLog.subscribe(board.log::add);
        player.send(new Intent.Join(options.name()));
        SwingTerminalFontConfiguration fontConfig = new SwingTerminalFontConfiguration(
                false, AWTTerminalFontConfiguration.BoldMode.NOTHING,
                new Font("ComicMono Nerd Font", Font.PLAIN, 24));

        DefaultTerminalFactory factory = new DefaultTerminalFactory();
        factory.setTerminalEmulatorFontConfiguration(fontConfig);
        factory.setInitialTerminalSize(TERMINAL_SIZE);

//                factory.setForceTextTerminal(true);


//        Terminal terminal = new CygwinTerminal(System.in, System.out, Charset.defaultCharset());

        Screen screen = factory.createScreen();
        screen.startScreen();
        screen.setCursorPosition(null);
        List<Menu> menus = List.of(new MapMenu(), new SkillsMenu(), new InventoryMenu(), new GoalsMenu());
        boolean typingCommand = false;
        String currentCommand = "";
        int selection = 0;

//        DEBUG_CREATE_NEW_RANDOM_SKILL.onSelect(player, board, Arrays.asList("30"));
//        DEBUG_CREATE_RANDOM_PRODUCT_LINE.onSelect(player, board, Arrays.asList("10"));
//        LIST.onSelect(player, board, new ArrayList<>());
        while (true) {
            long frameStart = System.currentTimeMillis();
            player.receive();
            player.frame();
            screen.clear();
            drawScene(player, board, screen, menus, selection, typingCommand ? currentCommand : null);
            screen.refresh();
            KeyStroke keyStroke;
            while ((keyStroke = screen.pollInput()) != null) {
                Menu menu = menus.get(selection);
                KeyType type = keyStroke.getKeyType();
                if (typingCommand) {
                    switch (type) {
                        case Character -> currentCommand = currentCommand + keyStroke.getCharacter();
                        case Backspace -> {
                            if (!currentCommand.isEmpty()) {
                                currentCommand = currentCommand.substring(0, currentCommand.length() - 1);
                            }
                        }
                        case Enter -> {
                            GameLog.say("/" + currentCommand);
                            submitCommand(currentCommand, player, board);
                            currentCommand = "";
                            typingCommand = false;
                        }
                        case Escape -> {
                            currentCommand = "";
                            typingCommand = false;
                        }
                        default -> {}
                    }
                } else if (menu.isFocused()) {
                    Menu.Result result = menu.handleKey(keyStroke, player, board);
                    if (result == Menu.Result.RELEASE) {
                        menu.unfocus();
                    } else if (result == Menu.Result.IGNORED) {
                        typingCommand = handleGlobalKey(keyStroke, player, board);
                    }
                } else {
                    switch (type) {
                        case Escape -> {
                            connection.close();
                            System.exit(0);
                        }
                        case ArrowLeft -> selection = Math.max(0, selection - 1);
                        case ArrowRight -> selection = Math.min(menus.size() - 1, selection + 1);
                        case ArrowDown, Enter -> menu.focus(player, board);
                        default -> typingCommand = handleGlobalKey(keyStroke, player, board);
                    }
                }
            }
            Thread.sleep(Math.max(0, GameClock.FRAME_MILLIS - (System.currentTimeMillis() - frameStart)));
        }
    }

    /**
     * Command line, all optional:
     * {@code [--name n] [--world id] [--db file]} plays locally;
     * add {@code --host [--port p] [--admin]} to let others join your game;
     * {@code --server [--port p] [--admin]} runs a world with no window;
     * {@code --connect host[:port] [--name n]} joins someone else's.
     */
    record Options(String name, int worldId, Path db, boolean server, boolean host, int port, boolean admin, String connect) {
        static Options parse(String[] args) {
            String name = defaultName();
            int worldId = 1;
            Path db = GameStore.defaultFile();
            boolean server = false, host = false, admin = false;
            int port = NetworkServer.DEFAULT_PORT;
            String connect = null;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--name" -> name = args[++i];
                    case "--world" -> worldId = Integer.parseInt(args[++i]);
                    case "--db" -> db = Path.of(args[++i]);
                    case "--server" -> server = true;
                    case "--host" -> host = true;
                    case "--port" -> port = Integer.parseInt(args[++i]);
                    case "--admin" -> admin = true;
                    case "--connect" -> connect = args[++i];
                    default -> throw new IllegalArgumentException("Unknown option " + args[i]);
                }
            }
            return new Options(name, worldId, db, server, host, port, admin, connect);
        }

        private static String defaultName() {
            String name = System.getProperty("user.name", "player").replaceAll("[^A-Za-z0-9_-]", "_");
            name = name.length() > 20 ? name.substring(0, 20) : name;
            return name.isEmpty() ? "player" : name;
        }
    }

    /** Single-key commands available everywhere. Returns true if the player started typing a /command. */
    private static boolean handleGlobalKey(KeyStroke keyStroke, Player player, Board board) {
        if (keyStroke.getKeyType() != KeyType.Character) {
            return false;
        }
        if (keyStroke.getCharacter() == '/') {
            return true;
        }
        submitCommand(keyStroke.getCharacter().toString(), player, board);
        return false;
    }

    private static void submitCommand(String s, Player player, Board board) {
        String command = s.trim();
        if (command.isEmpty()) {
            return;
        }
        for (ConsoleAction ca : ConsoleAction.values()) {
            if (ca.input == command.charAt(0)) {
                String[] parts = command.split("\\s+");
                List<String> additionalInputs = List.of(parts).subList(1, parts.length);
                try {
                    ca.onSelect(player, board, additionalInputs);
                } catch (Exception e) {
                    // A bad command (e.g. "/1 abc") shouldn't take the whole game down with it.
                    GameLog.say("Couldn't run [" + command + "]: "
                            + e.getClass().getSimpleName() + ": " + e.getMessage());
                    e.printStackTrace();
                }
                return;
            }
        }
        GameLog.say("Unrecognized command [" + command.charAt(0) + "]");
    }

    private static void drawScene(Player player,
                                  Board board,
                                  Screen screen,
                                  List<Menu> menus,
                                  int selection,
                                  String currentCommand) {
        BoardView.draw(player, board, screen);
        if (currentCommand != null) {
            drawCommand(screen, currentCommand);
        }
        int headersEnd = drawHeaders(screen, menus, selection);
        XpTrackerView.draw(player, screen, headersEnd - HEADER_GAP + 2);
        menus.get(selection).draw(screen, player, board);
        drawLog(board, screen);
    }

    /** Returns where a next header would go (last header + gap). */
    private static int drawHeaders(Screen screen, List<Menu> menus, int selection) {
        int x = HEADER_POS_X;
        for (int i = 0; i < menus.size(); i++) {
            TextGraphics header = screen.newTextGraphics();
            if (i == selection) {
                header.setForegroundColor(TextColor.ANSI.DEFAULT);
                header.setBackgroundColor(TextColor.ANSI.BLACK_BRIGHT);
                header.enableModifiers(SGR.BOLD);
            } else {
                header.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
                header.setBackgroundColor(TextColor.ANSI.DEFAULT);
            }
            String title = menus.get(i).title();
            header.putString(x, HEADER_POS_Y, title);
            // the first header sits over the map, the rest over the menu area
            x = i == 0 ? LIST_ITEM_POS_X + 1 : x + title.length() + HEADER_GAP;
        }
        return x;
    }

    private static void drawLog(Board board, Screen screen) {
        TerminalSize size = screen.getTerminalSize();
        int width = size.getColumns() - LOG_POS_X;
        int visibleLines = Math.min(LOG_LINES, size.getRows() - LOG_POS_Y);
        if (width <= 0 || visibleLines <= 0) {
            return;
        }
        TextGraphics graphics = screen.newTextGraphics();
        graphics.setForegroundColor(TextColor.ANSI.DEFAULT);
        graphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
        List<String> lines = board.log.latest(visibleLines);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.length() > width) {
                line = line.substring(0, width - 1) + "~";
            }
            graphics.putString(LOG_POS_X, LOG_POS_Y + i, line);
        }
    }

    private static void drawCommand(Screen screen, String currentCommand) {
        TextGraphics graphics = screen.newTextGraphics();
        graphics.setForegroundColor(TextColor.ANSI.DEFAULT);
        graphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
        graphics.putString(COMMAND_POS_X, COMMAND_POS_Y, "/" + currentCommand);
    }
}
