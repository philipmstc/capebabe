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

/** The last tab (or ","): your settings (Up/Down to pick, Left/Right to change) and the controls. */
public class SettingsMenu extends Menu {
    private static final int COLOR_ROW = 0;
    private static final int RUN_ROW = 1;
    private static final int INVESTIGATE_ROW = 2;
    private static final int SHOW_PRODUCT_TIER_ROW = 3;
    private static final int ROWS = 4;
    private static final int VALUE_X = 19;

    private static final List<String[]> CONTROLS = List.of(
            new String[]{"w a s d", "move / turn (nodes block you)"},
            new String[]{"r", "run on / off (2 tiles a tick)"},
            new String[]{"q", "use the node ahead (again: stop)"},
            new String[]{"g", "locked node ahead -> goal"},
            new String[]{"f", "fight the monster next to you"},
            new String[]{"Left/Right", "switch tabs; Down/Enter uses one"},
            new String[]{"i", "inventory (Enter: use on a node)"},
            new String[]{"Map: Down", "hover; Enter walks there"},
            new String[]{"/", "command (/1-/5 debug, k lists)"},
            new String[]{"k", "list every command in the log"},
            new String[]{",", "settings (again: back)"},
            new String[]{"Esc", "back out; quit from the top"});

    private int row = COLOR_ROW;

    public SettingsMenu() {
        super("Settings");
    }

    @Override
    protected void onFocus(Player player, Board board) {
        row = COLOR_ROW;
    }

    @Override
    public Result handleKey(KeyStroke key, Player player, Board board) {
        switch (key.getKeyType()) {
            case ArrowUp -> {
                if (row == 0) {
                    return Result.RELEASE;
                }
                row--;
            }
            case ArrowDown -> row = Math.min(ROWS - 1, row + 1);
            case ArrowLeft, ArrowRight, Enter -> {
                int delta = key.getKeyType() == KeyType.ArrowLeft ? -1 : 1;
                switch (row) {
                    case COLOR_ROW -> player.setColor(player.colorIndex + delta);
                    case RUN_ROW -> player.toggleRunning();
                    case INVESTIGATE_ROW -> player.toggleAutoInvestigate();
                    case SHOW_PRODUCT_TIER_ROW -> player.toggleShowProductTiers();
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
        int y = firstRow();
        settingRow(g, y++, isFocused() && row == COLOR_ROW, "Color",
                SkillStyle.PLAYER_COLOR_NAMES.get(Math.floorMod(player.colorIndex, SkillStyle.PLAYER_COLOR_NAMES.size())));
        g.setForegroundColor(SkillStyle.playerColor(player.colorIndex));
        g.setCharacter(textX() + VALUE_X + 15, y - 1, player.facing.arrow);
        settingRow(g, y++, isFocused() && row == RUN_ROW, "Running", player.running ? "on" : "off");
        settingRow(g, y++, isFocused() && row == INVESTIGATE_ROW, "Auto-investigate", player.autoInvestigate ? "on" : "off");
        settingRow(g, y++, isFocused() && row == SHOW_PRODUCT_TIER_ROW, "Contrast tiers", player.showProductTiers ? "on" : "off");

        y++;
        g.setForegroundColor(TextColor.ANSI.DEFAULT);
        g.enableModifiers(SGR.BOLD);
        g.putString(textX(), y++, "Controls");
        g.disableModifiers(SGR.BOLD);
        for (String[] control : CONTROLS) {
            if (y >= hintRow()) {
                break;
            }
            g.setForegroundColor(TextColor.ANSI.YELLOW);
            g.putString(textX(), y, control[0]);
            g.setForegroundColor(TextColor.ANSI.DEFAULT);
            g.putString(textX() + 12, y++, fit(control[1], textWidth() - 12));
        }
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        g.putString(textX(), hintRow(), isFocused() ? "[Up/Down] pick [Left/Right] change [Esc] close" : "[Down] change settings");
    }

    private void settingRow(TextGraphics g, int y, boolean selected, String label, String value) {
        g.setForegroundColor(selected ? TextColor.ANSI.WHITE_BRIGHT : TextColor.ANSI.DEFAULT);
        g.setCharacter(textX() - 1, y, selected ? '>' : ' ');
        g.putString(textX() + 1, y, label);
        g.putString(textX() + VALUE_X, y, "< " + value + " >");
    }
}
