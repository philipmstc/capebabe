package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.SkillStyle;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;

import java.util.List;

import static net.shchoo.scafe.scafe.client.ui.Layout.*;

/** Opened with "," over the tab area: your settings (Up/Down to pick, Left/Right to change) and the controls. */
public class SettingsMenu extends Menu {
    private static final int COLOR_ROW = 0;
    private static final int RUN_ROW = 1;
    private static final int ROWS = 2;

    private static final List<String[]> CONTROLS = List.of(
            new String[]{"w a s d", "move / turn (nodes block you; walk into one to face it)"},
            new String[]{"r", "run on / off (2 tiles per tick)"},
            new String[]{"q", "use the node you're facing (again to stop)"},
            new String[]{"g", "make the locked node you're facing a goal"},
            new String[]{"f", "fight the monster next to you (again to stop)"},
            new String[]{"Left/Right", "switch tabs; Down/Enter to use a tab, Esc to back out"},
            new String[]{"Map: Down", "hover cursor; Enter walks there (around nodes, diagonals ok)"},
            new String[]{"/", "type a command (/1 /2 /3 /4 build the world)"},
            new String[]{"k", "list every command in the log"},
            new String[]{",", "this menu"},
            new String[]{"Esc", "close menus; quit from the top"});

    private int row = COLOR_ROW;

    public SettingsMenu() {
        super("Settings");
    }

    @Override
    public Result handleKey(KeyStroke key, Player player, Board board) {
        switch (key.getKeyType()) {
            case ArrowUp -> row = Math.max(0, row - 1);
            case ArrowDown -> row = Math.min(ROWS - 1, row + 1);
            case ArrowLeft, ArrowRight, Enter -> {
                int delta = key.getKeyType() == KeyType.ArrowLeft ? -1 : 1;
                if (row == COLOR_ROW) {
                    player.setColor(player.colorIndex + delta);
                } else {
                    player.toggleRunning();
                }
            }
            case Escape -> {
                return Result.RELEASE;
            }
            default -> {
                return Result.IGNORED;
            }
        }
        return Result.HANDLED;
    }

    @Override
    public void draw(Screen screen, Player player, Board board) {
        TextGraphics g = screen.newTextGraphics();
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        int y = LIST_ITEM_POS_Y + 1;
        g.setForegroundColor(TextColor.ANSI.DEFAULT);
        g.enableModifiers(SGR.BOLD);
        g.putString(LIST_ITEM_POS_X, y++, "Settings");
        g.disableModifiers(SGR.BOLD);

        settingRow(g, y++, row == COLOR_ROW, "Color",
                SkillStyle.PLAYER_COLOR_NAMES.get(Math.floorMod(player.colorIndex, SkillStyle.PLAYER_COLOR_NAMES.size())));
        g.setForegroundColor(SkillStyle.playerColor(player.colorIndex));
        g.setCharacter(LIST_ITEM_POS_X + 26, y - 1, player.facing.arrow);
        settingRow(g, y++, row == RUN_ROW, "Running", player.running ? "on" : "off");

        y++;
        g.setForegroundColor(TextColor.ANSI.DEFAULT);
        g.enableModifiers(SGR.BOLD);
        g.putString(LIST_ITEM_POS_X, y++, "Controls");
        g.disableModifiers(SGR.BOLD);
        for (String[] control : CONTROLS) {
            if (y >= LIST_ITEM_POS_Y + LIST_ROWS) {
                break;
            }
            g.setForegroundColor(TextColor.ANSI.YELLOW);
            g.putString(LIST_ITEM_POS_X, y, control[0]);
            g.setForegroundColor(TextColor.ANSI.DEFAULT);
            g.putString(LIST_ITEM_POS_X + 12, y++, control[1]);
        }
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        g.putString(LIST_ITEM_POS_X, LIST_ITEM_POS_Y + LIST_ROWS, "[Up/Down] pick  [Left/Right] change  [Esc] close");
    }

    private static void settingRow(TextGraphics g, int y, boolean selected, String label, String value) {
        g.setForegroundColor(selected ? TextColor.ANSI.WHITE_BRIGHT : TextColor.ANSI.DEFAULT);
        g.setCharacter(LIST_ITEM_POS_X - 1, y, selected ? '>' : ' ');
        g.putString(LIST_ITEM_POS_X + 1, y, label);
        g.putString(LIST_ITEM_POS_X + 11, y, "< " + value + " >");
    }
}
