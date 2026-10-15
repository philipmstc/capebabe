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

    public static final int BOARD_HEIGHT = 5;
    public static final int BOARD_WIDTH = 8;

    // POSITIONS
    public static final int COMMAND_POS_X = 0;
    public static final int COMMAND_POS_Y = 12;
    public static final int HEADER_0_POS_X = 6;
    public static final int HEADER_1_POS_X = 19;
    public static final int HEADER_POS_Y = 0;
    public static final int HEADER_2_POS_X = 30;
    public static final int LIST_ITEM_POS_X = 20;
    public static final int LIST_ITEM_POS_Y = 0;
    public static final int BOARD_POS_X = 0;
    public static final int BOARD_POS_Y = 1;


    static int skillCount = 0;
    static int plCount = 0;
    static int frame = 0;

    public static void main(String[] args) throws Exception {
        String url = "jdbc:sqlite:C:\\Users\\phili\\AppData\\Roaming\\DBeaverData\\workspace6\\.metadata\\sample-database-sqlite-1\\Chinook.db";
        Connection connection = DriverManager.getConnection(url);
        List<Skill> skills = new ArrayList<>();
        if (connection != null) {
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
        }


        Scanner in = new Scanner(System.in);
        Player player = new Player();
        Board board = new Board(skills);
        AWTTerminalFontConfiguration fontConfig = AWTTerminalFontConfiguration.newInstance(
               new Font("Monospaced", Font.PLAIN, 24)
        );

        DefaultTerminalFactory factory = new DefaultTerminalFactory();
        factory.setTerminalEmulatorFontConfiguration(fontConfig);

//                factory.setForceTextTerminal(true);


//        Terminal terminal = new CygwinTerminal(System.in, System.out, Charset.defaultCharset());
        Terminal terminal = factory.createTerminal();
        Screen screen = new TerminalScreen(terminal);
        screen.startScreen();
        screen.setCursorPosition(null);
        Mode currentMode = Mode.NORMAL;
        String currentCommand = "";
        int selection = 0;

//        DEBUG_CREATE_NEW_RANDOM_SKILL.onSelect(player, board, Arrays.asList("30"));
//        DEBUG_CREATE_RANDOM_PRODUCT_LINE.onSelect(player, board, Arrays.asList("10"));
//        LIST.onSelect(player, board, new ArrayList<>());
        while (true) {
            screen.clear();
            drawScene(player, board, screen, currentMode, currentCommand, selection);
            screen.refresh();
            // System.out.print("Choose an action: ");
            KeyStroke keyStroke = screen.pollInput();
            if (keyStroke != null) {
                // Handle modal change
                if (keyStroke.getKeyType().equals(KeyType.Escape)) {
                    if (currentMode != Mode.NORMAL) {
                        currentMode = Mode.NORMAL;
                    } else {
                        System.exit(0);
                    }
                    continue;
                }
                if (currentMode == Mode.NORMAL) {
                    if (Character.valueOf('/').equals(keyStroke.getCharacter())) {
                        currentMode = Mode.COMMAND;
                    }
                    if (Character.valueOf('L').equals(keyStroke.getCharacter())) {
                        currentMode = Mode.LIST;
                    } else if (keyStroke.getKeyType() == KeyType.Character) {
                        String s = keyStroke.getCharacter().toString();

                        if (!s.isEmpty()) {
                            submitCommand(s, player, board);
                        }
                    }
                }
                else if (currentMode == Mode.COMMAND) {
                    if (keyStroke.getKeyType().equals(KeyType.Character)) {
                        currentCommand = currentCommand + keyStroke.getCharacter();
                    }
                    if (keyStroke.getKeyType().equals(KeyType.Enter)) {
                        System.out.println(currentCommand);
                        submitCommand(currentCommand, player, board);
                        currentCommand = "";
                        currentMode = Mode.NORMAL;
                    }
                    if (keyStroke.getKeyType().equals(KeyType.Backspace)) {
                        if (!currentCommand.isEmpty()) {
                            currentCommand = currentCommand.substring(0, currentCommand.length() - 1);
                        }
                    }
                }
                else if (currentMode == Mode.LIST) {
                    if (keyStroke.getKeyType().equals(KeyType.ArrowLeft)) {
                        selection = Math.max(0, selection - 1);
                    }
                    if (keyStroke.getKeyType().equals(KeyType.ArrowRight)) {
                        selection = Math.min(1, selection + 1);
                    }
                }

            }
            frame = (frame + 1) % (2* BOARD_WIDTH - 1);
            Thread.sleep(40);
        }
    }

    private static void submitCommand(String s, Player player, Board board) {
        boolean found = false;
        for (ConsoleAction ca : ConsoleAction.values()) {
            if (ca.input == s.charAt(0)) {
                String[] parts = s.trim().split("\\s+");
                List<String> additionalInputs = List.of(parts).subList(1, parts.length);
                ca.onSelect(player, board, additionalInputs);
                found = true;
                break;
            }
        }
        if (!found) {
            System.out.println("Unrecognized command [" + s.charAt(0) + "]");
        }
    }

    enum Mode {
        NORMAL,
        COMMAND,
        LIST
    }

    private static void drawScene(Player player,
                                  Board board,
                                  Screen screen,
                                  Mode currentMode,
                                  String currentCommand,
                                  int selection) {
        drawBoard(player, board, screen);
        if (currentMode == Mode.COMMAND) {
            drawCommand(screen, currentCommand);
        }
        drawList(player, board, screen, currentMode, selection);

    }

    private static void drawBoard(Player player, Board board, Screen screen) {
        // draw border
        drawBox(screen, BOARD_POS_X, BOARD_POS_Y, BOARD_WIDTH, BOARD_HEIGHT, 2);
        drawBox(screen, BOARD_POS_X, BOARD_POS_Y + 2*BOARD_HEIGHT + 1, BOARD_POS_X + 2*BOARD_WIDTH, 2, 1);
        TextGraphics line = screen.newTextGraphics();
        line.setBackgroundColor(TextColor.ANSI.GREEN_BRIGHT);
        line.drawLine(new TerminalPosition(BOARD_POS_X + 1, BOARD_POS_Y + 2*BOARD_HEIGHT + 2),
                      new TerminalPosition(BOARD_POS_X + 1 + frame, BOARD_POS_Y + 2*BOARD_HEIGHT + 2),
                '=');
        for (int i = 0; i < BOARD_HEIGHT; i++) {
            for (int j = 0; j < BOARD_WIDTH; j++) {
                boolean isPlayerPosition = player.x == j - 2 && player.y == i - 2;
                boolean hasAction = !board.availableActions(j, i).isEmpty();
                char result = isPlayerPosition
                        ? hasAction
                        ? '&' : '*'
                        : hasAction
                        ? '#' : Symbols.BULLET;

                TextColor fg = isPlayerPosition
                        ? hasAction
                        ? TextColor.ANSI.YELLOW : TextColor.ANSI.CYAN
                        : hasAction
                        ? TextColor.ANSI.RED : TextColor.ANSI.BLACK_BRIGHT;

                screen.setCharacter(BOARD_POS_X + 1 + (j * 2), BOARD_POS_Y + 1 + (i * 2),
                        TextCharacter.fromCharacter(
                                result,
                                fg,
                                TextColor.ANSI.DEFAULT
                        )[0]);
            }
        }
    }
    private static void drawBox(Screen screen,
                                int xStart,
                                int yStart,
                                int width,
                                int height,
                                int scale) {
        TextGraphics graphics = screen.newTextGraphics();
        TerminalPosition topLeft = new TerminalPosition(xStart, yStart);
        TerminalPosition topRight = new TerminalPosition(xStart + scale * width , yStart);
        TerminalPosition bottomLeft = new TerminalPosition(xStart, yStart + scale * height );
        TerminalPosition bottomRight = new TerminalPosition(xStart + scale * width , yStart + scale * height );
        graphics.setForegroundColor(TextColor.ANSI.DEFAULT);
        graphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
        graphics.drawLine(topLeft, topRight, Symbols.SINGLE_LINE_HORIZONTAL);
        graphics.drawLine(topLeft, bottomLeft, Symbols.SINGLE_LINE_VERTICAL);
        graphics.drawLine(topRight, bottomRight, Symbols.SINGLE_LINE_VERTICAL);
        graphics.drawLine(bottomLeft, bottomRight, Symbols.SINGLE_LINE_HORIZONTAL);
        graphics.setCharacter(topLeft, Symbols.SINGLE_LINE_TOP_LEFT_CORNER);
        graphics.setCharacter(topRight, Symbols.SINGLE_LINE_TOP_RIGHT_CORNER);
        graphics.setCharacter(bottomLeft, Symbols.SINGLE_LINE_BOTTOM_LEFT_CORNER);
        graphics.setCharacter(bottomRight, Symbols.SINGLE_LINE_BOTTOM_RIGHT_CORNER);
    }

    private static void drawList(Player player, Board board, Screen screen, Mode currentMode, int selection) {
        TextGraphics header0 = screen.newTextGraphics();
        if (currentMode != Mode.LIST) {
            header0.setForegroundColor(TextColor.ANSI.DEFAULT);
            header0.setBackgroundColor(TextColor.ANSI.BLACK_BRIGHT);
            header0.enableModifiers(SGR.BOLD);
        }
        else {
            header0.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            header0.setBackgroundColor(TextColor.ANSI.DEFAULT);
            header0.disableModifiers(SGR.BOLD);
        }
        header0.putString(HEADER_0_POS_X, HEADER_POS_Y, "Map");
        TextGraphics header = screen.newTextGraphics();
        if (selection == 0 && currentMode == Mode.LIST) {
            header.setBackgroundColor(TextColor.ANSI.BLACK_BRIGHT);
            header.setForegroundColor(TextColor.ANSI.DEFAULT);
            header.enableModifiers(SGR.BOLD);
        } else {
            header.setBackgroundColor(TextColor.ANSI.DEFAULT);
            header.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            header.disableModifiers(SGR.BOLD);
        }
        header.putString(HEADER_1_POS_X, HEADER_POS_Y, "Skills");

        TextGraphics header2 = screen.newTextGraphics();
        if (selection == 1 && currentMode == Mode.LIST) {
            header2.setBackgroundColor(TextColor.ANSI.BLACK_BRIGHT);
            header2.setForegroundColor(TextColor.ANSI.DEFAULT);
            header2.enableModifiers(SGR.BOLD);
        } else {
            header2.setBackgroundColor(TextColor.ANSI.DEFAULT);
            header2.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            header2.disableModifiers(SGR.BOLD);
        }

        header2.putString(HEADER_2_POS_X, HEADER_POS_Y, "Inventory");
        if (currentMode == Mode.LIST && selection == 0) {
            if (board.skills.isEmpty()) {
                return;
            }
            TextGraphics textGraphics = screen.newTextGraphics();
            textGraphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
            for (int i = 0; i < board.skills.size(); i++) {
                Skill skill = board.skills.get(i);
                switch(skill.type) {
                    case PRIMARY -> textGraphics.setForegroundColor(TextColor.ANSI.GREEN);
                    case SECONDARY -> textGraphics.setForegroundColor(TextColor.ANSI.BLUE_BRIGHT);
                    case TERTIARY -> textGraphics.setForegroundColor(TextColor.ANSI.YELLOW);
                }
                textGraphics.putString(LIST_ITEM_POS_X, LIST_ITEM_POS_Y + ((i + 1) * 2),
                        "[Lv. " + player.getLevel(skill) + "] " + skill.name + " : " + player.getExperience(skill) + "/" + player.getExperienceToNextLevel(skill));
            }
        }
        if (currentMode == Mode.LIST && selection == 1) {
            if (player.inventory.isEmpty()) {
                return;
            }
            TextGraphics textGraphics = screen.newTextGraphics();
            textGraphics.setForegroundColor(TextColor.ANSI.DEFAULT);
            textGraphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
            int i = 0;
            for (Product product : player.inventory.keySet()) {
                textGraphics.putString(LIST_ITEM_POS_X, LIST_ITEM_POS_Y + ((i+1)* 2),
                        product.name + " x " + player.inventory.get(product));

            }
        }
    }

    private static void drawCommand(Screen screen, String currentCommand) {
        screen.setCharacter(COMMAND_POS_X, COMMAND_POS_Y, TextCharacter.fromCharacter(
                '/',
                TextColor.ANSI.DEFAULT,
                TextColor.ANSI.DEFAULT
        )[0]);
        for (int i = 0; i < currentCommand.length(); i++) {
            char c = currentCommand.charAt(i);
            screen.setCharacter(COMMAND_POS_X + 1 + i, COMMAND_POS_Y, TextCharacter.fromCharacter(
                    c,
                    TextColor.ANSI.DEFAULT,
                    TextColor.ANSI.DEFAULT
            )[0]);
        }
    }

}