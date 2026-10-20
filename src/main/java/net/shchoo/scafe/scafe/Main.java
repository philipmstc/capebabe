package net.shchoo.scafe.scafe;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.GameLog;
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
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;
import com.googlecode.lanterna.terminal.ansi.ANSITerminal;
import com.googlecode.lanterna.terminal.ansi.CygwinTerminal;
import com.googlecode.lanterna.terminal.ansi.UnixTerminal;
import com.googlecode.lanterna.terminal.swing.AWTTerminalFontConfiguration;
import com.googlecode.lanterna.terminal.swing.SwingTerminalFontConfiguration;
import java.awt.*;
import javax.swing.JOptionPane;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.List;
import java.nio.file.Path;


public class Main {

    public static void main(String[] args) throws Exception {
        try {
            run(Options.parse(args));
        } catch (WorldInUseException e) {
            fail(e.getMessage());
        } catch (CouldNotConnect e) {
            fail(e.getMessage());
        }
    }

    /** Says why (in a popup too: a double-clicked jar has no console) and quits. */
    private static void fail(String message) {
        System.err.println(message);
        if (!GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(null, message, "capebabe", JOptionPane.WARNING_MESSAGE);
        }
        System.exit(1);
    }

    private static class CouldNotConnect extends Exception {
        CouldNotConnect(String message) {
            super(message);
        }
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
        GameConnection connection;
        String where;
        String footer = "";
        if (options.connect() != null) {
            String server = SocketConnection.normalize(options.connect());
            Tls.Client tls = Tls.client(options.data().resolve("known-servers"), server);
            try {
                connection = SocketConnection.connect(server, tls);
            } catch (IOException e) {
                throw new CouldNotConnect("Couldn't connect to " + server + ": " + e.getMessage());
            }
            where = "server " + server + (tls.trust().firstVisit() ? " (first visit: check the fingerprint below matches the host's)" : "");
            footer = "server fingerprint " + tls.trust().fingerprint();
        } else {
            GameServer server = startServer(options);
            where = "your own world";
            if (options.host()) {
                Tls.Server tls = Tls.server(options.data());
                NetworkServer network = new NetworkServer(server, options.port(), options.admin(), tls, limits);
                network.startInBackground();
                where = "your world (hosting on port " + options.port() + ")";
                footer = "your fingerprint " + tls.fingerprint();
                System.out.println("Fingerprint (friends see this when they first connect): " + tls.fingerprint());
            }
            connection = new LocalConnection(server.connect());
        }
        runClient(options, connection, where, footer);
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

    private static void runClient(Options options, GameConnection connection, String where, String footer) throws Exception {
        Player player = new Player(connection);
        Board board = new Board(player.content);
        GameLog.subscribe(board.log::add);
        SwingTerminalFontConfiguration fontConfig = new SwingTerminalFontConfiguration(
                true, AWTTerminalFontConfiguration.BoldMode.EVERYTHING_BUT_SYMBOLS,
                new Font("Consolas", Font.PLAIN, 14));

        DefaultTerminalFactory factory = new DefaultTerminalFactory();

        factory.setTerminalEmulatorFontConfiguration(fontConfig);
        factory.setInitialTerminalSize(Layout.TERMINAL_SIZE);

//                factory.setForceTextTerminal(true);


//        Terminal terminal = new CygwinTerminal(System.in, System.out, Charset.defaultCharset());

        Screen screen = factory.createScreen();
        screen.startScreen();
        screen.setCursorPosition(null);

        if (new LoginScreen(player, where, footer, options.name()).run(screen) == LoginScreen.Outcome.EXIT) {
            connection.close();
            System.exit(0);
        }

        MapMenu map = new MapMenu();
        CommandLine commandLine = new CommandLine();
        TabBar tabs = new TabBar(Layout.TABS, new InventoryPanel(Layout.INVENTORY),
                List.of(map, new SkillsMenu(), new GoalsMenu()), new SettingsMenu());
        Runnable quit = () -> {
            connection.close();
            System.exit(0);
        };
        // Order matters: earlier widgets get each key first, later ones draw on top.
        List<Widget> widgets = List.of(
                commandLine,
                new MapView(Layout.MAP),
                new ProgressBarView(Layout.PROGRESS),
                tabs,
                new MapDetailView(Layout.DETAIL, map::hovered),
                new XpTrackerView(Layout.XP),
                new MessageLogView(Layout.LOG, commandLine),
                new KeyCommands(quit),
                new FrameRateAnimation());

        while (true) {
            long frameStart = System.currentTimeMillis();
            player.receive();
            player.frame();
            screen.clear();
            for (Widget widget : widgets) {
                widget.draw(screen, player, board);
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
    }

    /**
     * Command line, all optional:
     * {@code [--name n] [--world id-or-name] [--data dir]} plays locally (saves in {@code dir}, default ~/.capebabe);
     * add {@code --host [--port p] [--admin] --world-password s} to let others join your game;
     * {@code --server [--port p] [--admin] --world-password s} runs a world with no window;
     * {@code --connect host[:port] [--name n]} joins someone else's. {@code --name} only fills in the login screen.
     * Hosting needs {@code --world-password} (or {@code --no-world-password} to go without, on a trusted network);
     * {@code --max-per-ip n} lets n connections in from one address (default 1, e.g. 2 for two friends in one house).
     */
    record Options(String name, String world, Path data, boolean server, boolean host, int port, boolean admin, String connect,
                   String worldPassword, boolean noWorldPassword, int maxPerIp) {
        static Options parse(String[] args) {
            String name = defaultName();
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

        private static String defaultName() {
            String name = System.getProperty("user.name", "player").replaceAll("[^A-Za-z0-9_-]", "_");
            name = name.length() > 20 ? name.substring(0, 20) : name;
            return name.isEmpty() ? "player" : name;
        }
    }
}
