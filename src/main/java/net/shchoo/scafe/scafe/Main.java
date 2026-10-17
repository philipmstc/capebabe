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
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;
import java.util.List;

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


    static int skillCount = 0;
    static int plCount = 0;

    public static void main(String[] args) throws Exception {
        // DB loading disabled: the path below only exists on one machine (and startup failed
        // without it), and the queries are all commented out anyway. Kept for reference.
//        String url = "jdbc:sqlite:C:\\Users\\phili\\AppData\\Roaming\\DBeaverData\\workspace6\\.metadata\\sample-database-sqlite-1\\Chinook.db";
//        Connection connection = DriverManager.getConnection(url);
        List<Skill> skills = new ArrayList<>();
//        if (connection != null) {
//            System.out.println("Connected");
//            PreparedStatement preparedStatement = connection.prepareStatement("select * from skill");
//            boolean execute = preparedStatement.execute();
//            ResultSet rs = preparedStatement.getResultSet();
//            while (rs.next()) {
//                Skill skill = new Skill(rs.getString("name"), new ArrayList<>());
//                skills.add(skill);
//            }
//
//            preparedStatement.close();
//            rs.close();
//
//            preparedStatement = connection.prepareStatement("select * from product");
//            preparedStatement.execute();
//            rs = preparedStatement.getResultSet();
//            Collection<Product> products = new ArrayList<>();
//            while (rs.next()) {
//                Product product = new Product(rs.getString("name"));
//                products.add(product);
//            }
//            for (Product p : products) {
//                System.out.println("Product: " + p.name);
//            }
//
//            preparedStatement.close();
//            rs.close();
//            connection.close();
//        }


        Scanner in = new Scanner(System.in);
        Player player = new Player();
        Board board = new Board(skills);
        GameLog.subscribe(board.log::add);
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
            player.tick();
            screen.clear();
            drawScene(player, board, screen, menus, selection, typingCommand ? currentCommand : null);
            screen.refresh();
            KeyStroke keyStroke = screen.pollInput();
            if (keyStroke == null) {
                Thread.sleep(60);
                continue;
            }
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
                    case Escape -> System.exit(0);
                    case ArrowLeft -> selection = Math.max(0, selection - 1);
                    case ArrowRight -> selection = Math.min(menus.size() - 1, selection + 1);
                    case ArrowDown, Enter -> menu.focus(player, board);
                    default -> typingCommand = handleGlobalKey(keyStroke, player, board);
                }
            }
            Thread.sleep(60);
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
