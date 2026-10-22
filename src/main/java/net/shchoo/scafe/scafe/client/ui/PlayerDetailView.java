package net.shchoo.scafe.scafe.client.ui;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.SkillStyle;

import java.util.function.BooleanSupplier;

/**
 * Top right: your name, total level and a red Log Out. Esc (with no tab focused) selects Log Out, Enter asks
 * "Log out?" in a popup (Left/Right or y/n, Enter), and yes logs out. It sits early in the widget list so that while
 * Log Out is selected or asking, it gets keys before the tabs (which use Left/Right).
 */
public class PlayerDetailView extends Widget {
    private static final String LOG_OUT = "Log Out";

    private enum State { IDLE, SELECTED, ASKING }

    /** True when no tab has the keys, so Esc isn't needed for backing out of one. */
    private final BooleanSupplier menusIdle;
    private final Runnable logOut;
    private State state = State.IDLE;
    private boolean yes;

    public PlayerDetailView(Rect bounds, BooleanSupplier menusIdle, Runnable logOut) {
        super(bounds);
        this.menusIdle = menusIdle;
        this.logOut = logOut;
    }

    @Override
    public void draw(Screen screen, Player player, Board board) {
        drawFrame(screen, bounds());
        TextGraphics g = screen.newTextGraphics();
        g.setForegroundColor(SkillStyle.playerColor(player.colorIndex));
        g.putString(Layout.TERMINAL_SIZE.getColumns() - 40, 0, String.format("%24s", player.name));
        g.setForegroundColor(TextColor.ANSI.YELLOW_BRIGHT);
        g.putString(Layout.TERMINAL_SIZE.getColumns() - 16, 0, String.format("%-16s", " [Total Lv. " + player.totalLevel() + "]"));
        if (state == State.IDLE) {
            g.setForegroundColor(TextColor.ANSI.RED);
        } else {
            g.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT);
            g.setBackgroundColor(TextColor.ANSI.RED);
            g.enableModifiers(SGR.BOLD);
        }
        g.putString(Layout.TERMINAL_SIZE.getColumns() - 9, 1, LOG_OUT);
    }

    /** The "Log out?" popup, over everything else. */
    @Override
    public void drawOnTop(Screen screen, Player player, Board board) {
        if (state != State.ASKING) {
            return;
        }
        int width = 28, height = 6;
        int left = (Layout.TERMINAL_SIZE.getColumns() - width) / 2;
        int top = (Layout.TERMINAL_SIZE.getRows() - height) / 2;
        TextGraphics g = screen.newTextGraphics();
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        for (int y = top; y < top + height; y++) {
            g.putString(left, y, " ".repeat(width));
        }
        drawFrame(screen, left, top, left + width - 1, top + height - 1);
        g.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT);
        g.putString(left + (width - 8) / 2, top + 2, "Log out?");
        String yesText = (yes ? "> " : "  ") + "Yes";
        String noText = (!yes ? "> " : "  ") + "No";
        int x = left + (width - (yesText.length() + 4 + noText.length())) / 2;
        button(g, x, top + 3, yesText, yes);
        button(g, x + yesText.length() + 4, top + 3, noText, !yes);
    }

    private static void button(TextGraphics g, int x, int y, String text, boolean selected) {
        g.setForegroundColor(selected ? TextColor.ANSI.WHITE_BRIGHT : TextColor.ANSI.DEFAULT);
        if (selected) {
            g.enableModifiers(SGR.BOLD);
        }
        g.putString(x, y, text);
        g.disableModifiers(SGR.BOLD);
    }

    @Override
    public boolean onKey(KeyStroke key, Player player, Board board) {
        switch (state) {
            case IDLE -> {
                if (key.getKeyType() == KeyType.Escape && menusIdle.getAsBoolean()) {
                    state = State.SELECTED;
                    return true;
                }
                return false;
            }
            case SELECTED -> {
                if (key.getKeyType() == KeyType.Enter) {
                    state = State.ASKING;
                    yes = false;
                    return true;
                }
                // Esc puts it down again; anything else does too, and carries on as normal (walking, tabs...)
                state = State.IDLE;
                return key.getKeyType() == KeyType.Escape;
            }
            default -> {
                switch (key.getKeyType()) {
                    case ArrowLeft, ArrowRight, Tab -> yes = !yes;
                    case Escape -> state = State.IDLE;
                    case Enter -> answer(yes);
                    case Character -> {
                        char c = Character.toLowerCase(key.getCharacter());
                        if (c == 'y' || c == 'n') {
                            answer(c == 'y');
                        }
                    }
                    default -> {}
                }
                return true;
            }
        }
    }

    private void answer(boolean logOutNow) {
        state = State.IDLE;
        if (logOutNow) {
            logOut.run();
        }
    }

    /** Log Out is selected or asking (for tests). */
    boolean active() {
        return state != State.IDLE;
    }
}
