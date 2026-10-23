package net.shchoo.scafe.scafe;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.ClientPrefs;
import net.shchoo.scafe.scafe.client.GameLog;
import net.shchoo.scafe.scafe.client.LogEntry;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.ui.*;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.net.NetworkServer;
import net.shchoo.scafe.scafe.net.SocketConnection;
import net.shchoo.scafe.scafe.net.Tls;
import net.shchoo.scafe.scafe.persistence.AccountStore;
import net.shchoo.scafe.scafe.persistence.WorldInUseException;
import net.shchoo.scafe.scafe.persistence.WorldStore;
import net.shchoo.scafe.scafe.protocol.GameClock;
import net.shchoo.scafe.scafe.protocol.GameConnection;
import net.shchoo.scafe.scafe.server.GameServer;
import net.shchoo.scafe.scafe.server.LocalConnection;

import com.googlecode.lanterna.*;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.swing.AWTTerminalFontConfiguration;
import com.googlecode.lanterna.terminal.swing.SwingTerminalFontConfiguration;
import java.awt.*;
import javax.swing.JOptionPane;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import java.nio.file.Path;

import static java.awt.Font.PLAIN;


public class Main {

    public static void main(String[] args) throws Exception {
        try {
            run(Options.parse(args));
        } catch (WorldInUseException e) {
            fail(e.getMessage());
        }
    }

    /** Fonts to draw with, best first: the grid needs a monospaced one, and not every computer has Consolas. */
    private static final List<String> FONTS = List.of("Comic Mono NerdFont", "Consolas", "Menlo", "Monaco", "Cascadia Mono", "DejaVu Sans Mono",
            "Liberation Mono", "Ubuntu Mono", "Courier New");

    private static String monospacedFont() {
        Set<String> installed = Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        return FONTS.stream().filter(installed::contains).findFirst().orElse(Font.MONOSPACED);
    }

    /** Says why (in a popup too: a double-clicked jar has no console) and quits. */
    private static void fail(String message) {
        System.err.println(message);
        if (!GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(null, message, "capebabe", JOptionPane.WARNING_MESSAGE);
        }
        System.exit(1);
    }



    private static void run(Options options) throws Exception {
        if ((options.server() || options.host()) && options.worldPassword() == null && !options.noWorldPassword()) {
            System.err.println("Hosting needs a world password, so only people you've told it to can get in:");
            System.err.println("  --world-password <secret>   (or --no-world-password, e.g. on a home network)");
            System.exit(2);
        }
        NetworkServer.Limits limits = NetworkServer.Limits.defaults().withPerAddress(options.maxPerIp());
        if (options.server()) {
            GameServer server = startServer(options);
            Tls.Server tls = Tls.server(options.data());
            NetworkServer network = new NetworkServer(server, options.port(), options.admin(), tls, limits);
            System.out.println(server.world.name + " is up on port " + network.port()
                    + (options.admin() ? " (admin commands on for everyone)" : ""));
            System.out.println("Fingerprint (friends see this when they first connect): " + tls.fingerprint());
            network.acceptForever();
            return;
        }
        ClientPrefs prefs = ClientPrefs.load(options.data());
        String name = options.name() != null ? options.name() : prefs.name() != null ? prefs.name() : Options.defaultName();
        Screen screen = openWindow();
        GameServer hosted = null;
        Tls.Server hostTls = null;
        if (options.host()) {
            hosted = startServer(options);
            hostTls = Tls.server(options.data());
            NetworkServer network = new NetworkServer(hosted, options.port(), options.admin(), hostTls, limits);
            network.startInBackground();
            System.out.println("Fingerprint (friends see this when they first connect): " + hostTls.fingerprint());
        }
        String server = options.connect() != null ? options.connect() : prefs.server() != null ? prefs.server() : "";
        // log in, play, and after a log out, back to the login (hosting: the world and its guests carry on meanwhile)
        while (true) {
            LoginScreen login;
            if (hosted != null) {
                GameConnection local = new LocalConnection(hosted.connect());
                login = new LoginScreen(new LoginScreen.Connection(local, new Player(local),
                        "your world (hosting on port " + options.port() + ")", "your fingerprint " + hostTls.fingerprint()), name);
            } else {
                login = new LoginScreen(target -> target.isEmpty() ? ownWorld(options) : connect(options, target), server, name);
            }
            if (login.run(screen) == LoginScreen.Outcome.EXIT) {
                if (login.connection() != null) {
                    login.connection().connection().close();
                }
                System.exit(0);
            }
            name = login.connection().player().name;
            if (hosted == null) {
                server = login.server();
                prefs.save(options.data(), server, name);
            }
            play(screen, login);
        }
    }

    /** Your own world on this computer (started the first time it's picked). */
    private static LoginScreen.Connection ownWorld(Options options) throws Exception {
        if (ownWorld == null) {
            ownWorld = startServer(options);
        }
        GameConnection local = new LocalConnection(ownWorld.connect());
        return new LoginScreen.Connection(local, new Player(local), "your own world", "");
    }

    private static GameServer ownWorld;

    private static LoginScreen.Connection connect(Options options, String target) throws Exception {
        String server = SocketConnection.normalize(target);
        Tls.Client tls = Tls.client(options.data().resolve("known-servers"), server);
        GameConnection connection;
        try {
            connection = SocketConnection.connect(server, tls);
        } catch (IOException e) {
            throw new IOException("Couldn't connect to " + server + ": " + e.getMessage(), e);
        }
        String where = "server " + server
                + (tls.trust().firstVisit() ? " (first visit: check the fingerprint below matches the host's)" : "");
        return new LoginScreen.Connection(connection, new Player(connection), where,
                "server fingerprint " + tls.trust().fingerprint());
    }

    /** Opens the account and world databases, loads (or creates) the world and starts ticking. Saves on exit. */
    private static GameServer startServer(Options options) throws Exception {
        AccountStore accounts = new AccountStore(options.data().resolve("accounts.db"));
        // "--world 2" is world-2.db, named "world2"; "--world "Gabe's World"" is world-gabes-world.db, named exactly that
        boolean numbered = options.world().matches("\\d+");
        int worldId = numbered ? Integer.parseInt(options.world()) : 0;
        WorldStore store = new WorldStore(numbered ? WorldStore.fileFor(options.data(), worldId)
                : WorldStore.fileFor(options.data(), options.world()), accounts);
        World world = store.loadOrCreateWorld(worldId, numbered ? "world" + worldId : options.world(), new Random().nextLong());
        GameServer server = new GameServer(world, store);
        server.ensureBuildings();
        server.setWorldPassword(options.worldPassword());
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            try {
                store.close();
                accounts.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }));
        return server;
    }

    private static Screen openWindow() throws IOException {
        SwingTerminalFontConfiguration fontConfig = new SwingTerminalFontConfiguration(
                true, AWTTerminalFontConfiguration.BoldMode.EVERYTHING_BUT_SYMBOLS,
        new Font(monospacedFont(), Font.PLAIN, 14));

        DefaultTerminalFactory factory = new DefaultTerminalFactory();
        factory.setTerminalEmulatorFontConfiguration(fontConfig);
        factory.setInitialTerminalSize(Layout.TERMINAL_SIZE);
        // always our own window: left to itself, lanterna takes over whatever terminal started it (Git Bash: flicker
        // and broken keys) or refuses (Windows Terminal: "use javaw"), depending on how it was launched
        factory.setPreferTerminalEmulator(true);

        Screen screen = factory.createScreen();
        screen.startScreen();
        screen.setCursorPosition(null);
        return screen;
    }

    /** Plays until you log out (closing the window quits the whole game). */
    private static void play(Screen screen, LoginScreen login) throws Exception {
        GameConnection connection = login.connection().connection();
        Player player = login.connection().player();
        Board board = new Board(player.content);
        login.heard().forEach(line -> board.log.add(line));
        Consumer<LogEntry> toLog = entry -> board.log.add(entry);
        GameLog.subscribeEntries(toLog);
        boolean[] loggedOut = {false};

        MapMenu map = new MapMenu();
        CommandLine commandLine = new CommandLine();
        TabBar tabs = new TabBar(Layout.TABS, new InventoryPanel(Layout.INVENTORY), map,
                List.of(new SkillsMenu(), new GoalsMenu(), new StatsMenu()), new SettingsMenu());
        Runnable quit = () -> {
            connection.close();
            System.exit(0);
        };
        // Order matters: earlier widgets get each key first, later ones draw on top (popups: drawOnTop).
        List<Widget> widgets = List.of(
                new BankView(),
                commandLine,
                new PlayerDetailView(Layout.PLAYER_DETAILS, () -> !tabs.menuFocused(), () -> loggedOut[0] = true),
                new MapView(Layout.MAP),
                new ProgressBarView(Layout.PROGRESS),
                new EquipmentView(Layout.EQUIPMENT),
                tabs,
                new MapDetailView(Layout.DETAIL, map::hovered),
                new XpTrackerView(Layout.XP),
                new MessageLogView(Layout.LOG, commandLine),
                new KeyCommands(quit),
                new FrameRateAnimation());

        while (!loggedOut[0]) {
            long frameStart = System.currentTimeMillis();
            player.receive();
            player.frame();
            screen.clear();
            for (Widget widget : widgets) {
                widget.draw(screen, player, board);
            }
            for (Widget widget : widgets) {
                widget.drawOnTop(screen, player, board);
            }
            if (player.lightMode) {
                LightMode.invert(screen);
            }
            screen.refresh();
            KeyStroke key;
            while ((key = screen.pollInput()) != null) {
                // closing the window only disposes it; without this the game (and its autosaves) kept running unseen
                if (key.getKeyType() == KeyType.EOF) {
                    quit.run();
                }
                for (Widget widget : widgets) {
                    if (widget.onKey(key, player, board)) {
                        break;
                    }
                }
            }
            Thread.sleep(Math.max(0, GameClock.FRAME_MILLIS - (System.currentTimeMillis() - frameStart)));
        }
        // the server saves your character when the connection closes
        GameLog.unsubscribeEntries(toLog);
        connection.close();
    }

    /**
     * Command line, all optional. With nothing, the login screen asks for a server (blank = your own world here,
     * {@code [--world id-or-name] [--data dir]}, saved in {@code dir}, default ~/.capebabe) and remembers the last
     * one; {@code --connect host[:port]} and {@code --name n} just fill it in.
     * {@code --host [--port p] [--admin] --world-password s} plays your world and lets others join it;
     * {@code --server [--port p] [--admin] --world-password s} runs a world with no window.
     * Hosting needs {@code --world-password} (or {@code --no-world-password} to go without, on a trusted network);
     * {@code --max-per-ip n} lets n connections in from one address (default 1, e.g. 2 for two friends in one house).
     */
    record Options(String name, String world, Path data, boolean server, boolean host, int port, boolean admin, String connect,
                   String worldPassword, boolean noWorldPassword, int maxPerIp) {
        static Options parse(String[] args) {
            String name = null;
            String world = "1";
            Path data = Path.of(System.getProperty("user.home"), ".capebabe");
            boolean server = false, host = false, admin = false;
            int port = NetworkServer.DEFAULT_PORT;
            String connect = null;
            String worldPassword = System.getenv("CAPEBABE_WORLD_PASSWORD");
            boolean noWorldPassword = false;
            int maxPerIp = 1;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--name" -> name = args[++i];
                    case "--world" -> world = args[++i].trim();
                    case "--data" -> data = Path.of(args[++i]);
                    case "--server" -> server = true;
                    case "--host" -> host = true;
                    case "--port" -> port = Integer.parseInt(args[++i]);
                    case "--admin" -> admin = true;
                    case "--connect" -> connect = args[++i];
                    case "--world-password" -> worldPassword = args[++i];
                    case "--no-world-password" -> noWorldPassword = true;
                    case "--max-per-ip" -> maxPerIp = Math.max(1, Integer.parseInt(args[++i]));
                    default -> throw new IllegalArgumentException("Unknown option " + args[i]);
                }
            }
            if (world.isEmpty() || world.length() > 40 || WorldStore.fileName(world).equals("named-")) {
                throw new IllegalArgumentException("--world takes a number or a name (up to 40 characters, with some letters or digits)");
            }
            return new Options(name, world, data, server, host, port, admin, connect,
                    worldPassword == null || worldPassword.isEmpty() ? null : worldPassword, noWorldPassword, maxPerIp);
        }

        /** The OS user name, cleaned up to a valid game name: the login screen's name when nothing else is known. */
        static String defaultName() {
            String name = System.getProperty("user.name", "player").replaceAll("[^A-Za-z0-9_-]", "_");
            name = name.length() > 20 ? name.substring(0, 20) : name;
            return name.isEmpty() ? "player" : name;
        }
    }
}
