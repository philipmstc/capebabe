package net.shchoo.scafe.scafe;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.GameLog;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.ui.*;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.net.NetworkServer;
import net.shchoo.scafe.scafe.net.SocketConnection;
import net.shchoo.scafe.scafe.persistence.AccountStore;
import net.shchoo.scafe.scafe.persistence.WorldStore;
import net.shchoo.scafe.scafe.protocol.GameClock;
import net.shchoo.scafe.scafe.protocol.GameConnection;
import net.shchoo.scafe.scafe.protocol.Intent;
import net.shchoo.scafe.scafe.server.GameServer;
import net.shchoo.scafe.scafe.server.LocalConnection;

import com.googlecode.lanterna.*;
import com.googlecode.lanterna.input.KeyStroke;
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


public class Main {

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

    /** Opens the account and world databases, loads (or creates) the world and starts ticking. Saves on exit. */
    private static GameServer startServer(Options options) throws Exception {
        AccountStore accounts = new AccountStore(options.data().resolve("accounts.db"));
        WorldStore store = new WorldStore(WorldStore.fileFor(options.data(), options.worldId()), accounts);
        World world = store.loadOrCreateWorld(options.worldId(), "world" + options.worldId(), new Random().nextLong());
        GameServer server = new GameServer(world, store);
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
        factory.setInitialTerminalSize(Layout.TERMINAL_SIZE);

//                factory.setForceTextTerminal(true);


//        Terminal terminal = new CygwinTerminal(System.in, System.out, Charset.defaultCharset());

        Screen screen = factory.createScreen();
        screen.startScreen();
        screen.setCursorPosition(null);

        TabBar tabs = new TabBar(List.of(new MapMenu(), new SkillsMenu(), new InventoryMenu(), new GoalsMenu()),
                new SettingsMenu());
        // Order matters: earlier widgets get each key first, later ones draw on top.
        List<Widget> widgets = List.of(
                new CommandLine(),
                new MapView(),
                tabs,
                new XpTrackerView(() -> tabs.headersEnd() - Layout.HEADER_GAP + 2),
                new MessageLogView(),
                new KeyCommands(() -> {
                    connection.close();
                    System.exit(0);
                }));

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
     * {@code [--name n] [--world id] [--data dir]} plays locally (saves in {@code dir}, default ~/.capebabe);
     * add {@code --host [--port p] [--admin]} to let others join your game;
     * {@code --server [--port p] [--admin]} runs a world with no window;
     * {@code --connect host[:port] [--name n]} joins someone else's.
     */
    record Options(String name, int worldId, Path data, boolean server, boolean host, int port, boolean admin, String connect) {
        static Options parse(String[] args) {
            String name = defaultName();
            int worldId = 1;
            Path data = Path.of(System.getProperty("user.home"), ".capebabe");
            boolean server = false, host = false, admin = false;
            int port = NetworkServer.DEFAULT_PORT;
            String connect = null;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--name" -> name = args[++i];
                    case "--world" -> worldId = Integer.parseInt(args[++i]);
                    case "--data" -> data = Path.of(args[++i]);
                    case "--server" -> server = true;
                    case "--host" -> host = true;
                    case "--port" -> port = Integer.parseInt(args[++i]);
                    case "--admin" -> admin = true;
                    case "--connect" -> connect = args[++i];
                    default -> throw new IllegalArgumentException("Unknown option " + args[i]);
                }
            }
            return new Options(name, worldId, data, server, host, port, admin, connect);
        }

        private static String defaultName() {
            String name = System.getProperty("user.name", "player").replaceAll("[^A-Za-z0-9_-]", "_");
            name = name.length() > 20 ? name.substring(0, 20) : name;
            return name.isEmpty() ? "player" : name;
        }
    }
}
