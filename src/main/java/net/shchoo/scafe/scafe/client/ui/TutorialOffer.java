package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.protocol.GameClock;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import com.googlecode.lanterna.screen.Screen;

import java.io.IOException;

/**
 * Asked on an account's first login: "Would you like a tutorial?" &lt;Yes&gt; &lt;No&gt;. Left/Right (or Tab) pick,
 * Enter answers, y / n answer straight away, Esc is no; or click one.
 */
public class TutorialOffer {
    static final String QUESTION = "Would you like a tutorial?";
    static final String YES = "<Yes>";
    static final String NO = "<No>";

    private boolean yes = true;

    public boolean yesPicked() {
        return yes;
    }

    /** The answer, or null for no answer yet. */
    Boolean onKey(KeyStroke key) {
        switch (key.getKeyType()) {
            case ArrowLeft, ArrowRight, Tab -> yes = !yes;
            case Enter -> {
                return yes;
            }
            case Escape, EOF -> {
                return false;
            }
            case Character -> {
                char c = Character.toLowerCase(key.getCharacter());
                if (c == 'y') return true;
                if (c == 'n') return false;
            }
            default -> {}
        }
        return null;
    }

    private static int row() {
        return Layout.TERMINAL_SIZE.getRows() / 2;
    }

    private static int yesX() {
        int width = YES.length() + 3 + NO.length();
        return (Layout.TERMINAL_SIZE.getColumns() - width) / 2;
    }

    private static int noX() {
        return yesX() + YES.length() + 3;
    }

    /** A click's answer, or null if it missed both. */
    Boolean click(int x, int y) {
        if (y != row() + 2) {
            return null;
        }
        if (x >= yesX() && x < yesX() + YES.length()) {
            return true;
        }
        if (x >= noX() && x < noX() + NO.length()) {
            return false;
        }
        return null;
    }

    void draw(Screen screen) {
        TextGraphics g = screen.newTextGraphics();
        g.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT);
        g.enableModifiers(SGR.BOLD);
        g.putString((Layout.TERMINAL_SIZE.getColumns() - QUESTION.length()) / 2, row(), QUESTION);
        g.disableModifiers(SGR.BOLD);
        button(g, yesX(), YES, yes);
        button(g, noX(), NO, !yes);
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        String hint = "A short walk through the game in a world of its own; your character here stays as it is.";
        g.putString(Math.max(0, (Layout.TERMINAL_SIZE.getColumns() - hint.length()) / 2), row() + 4, hint);
    }

    private static void button(TextGraphics g, int x, String label, boolean picked) {
        g.setForegroundColor(picked ? TextColor.ANSI.BLACK : TextColor.ANSI.DEFAULT);
        g.setBackgroundColor(picked ? TextColor.ANSI.WHITE_BRIGHT : TextColor.ANSI.DEFAULT);
        g.putString(x, row() + 2, label);
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
    }

    /** Asks until answered: true = yes. */
    public boolean run(Screen screen) throws IOException, InterruptedException {
        while (true) {
            long frameStart = System.currentTimeMillis();
            screen.clear();
            draw(screen);
            screen.refresh();
            KeyStroke key;
            while ((key = screen.pollInput()) != null) {
                Boolean answer;
                if (key instanceof MouseAction mouse) {
                    answer = mouse.getActionType() == MouseActionType.CLICK_DOWN && mouse.getButton() == 1
                            ? click(mouse.getPosition().getColumn(), mouse.getPosition().getRow()) : null;
                } else {
                    answer = onKey(key);
                }
                if (answer != null) {
                    return answer;
                }
            }
            Thread.sleep(Math.max(0, GameClock.FRAME_MILLIS - (System.currentTimeMillis() - frameStart)));
        }
    }
}
