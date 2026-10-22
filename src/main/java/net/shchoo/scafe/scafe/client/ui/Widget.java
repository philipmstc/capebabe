package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;

import com.googlecode.lanterna.Symbols;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.screen.Screen;

/**
 * A piece of the screen (map, tabs, xp tracker, command line, log...). Every frame the main loop draws each
 * widget in order, and offers each key to them in the same order until one uses it. A widget draws inside its
 * {@link #bounds()} (frame included), so moving or resizing one is just giving it another {@link Rect}.
 */
public abstract class Widget {
    private Rect bounds;

    protected Widget() {}

    protected Widget(Rect bounds) {
        this.bounds = bounds;
    }

    public Rect bounds() {
        return bounds;
    }

    public void setBounds(Rect bounds) {
        this.bounds = bounds;
    }

    public abstract void draw(Screen screen, Player player, Board board);

    /**
     * Drawn after every widget's {@link #draw}, for things that must be on top (popups) whatever this widget's
     * place in the list, which is also its place in line for keys.
     */
    public void drawOnTop(Screen screen, Player player, Board board) {}

    /** Returns true if this widget used the key, so later widgets don't see it. */
    public boolean onKey(KeyStroke key, Player player, Board board) {
        return false;
    }

    /** Where text starts inside the frame (one space of padding). */
    protected int textX() {
        return bounds.x() + 2;
    }

    /** Columns of text that fit from {@link #textX()} to the right border (with padding). */
    protected int textWidth() {
        return bounds.width() - 4;
    }

    /** The first row inside the frame. */
    protected int firstRow() {
        return bounds.y() + 1;
    }

    /** The last row inside the frame, used for key hints. */
    protected int hintRow() {
        return bounds.bottom() - 1;
    }

    /** Rows inside the frame, hint row included. */
    protected int rows() {
        return bounds.height() - 2;
    }

    protected void drawFrame(Screen screen) {
        drawFrame(screen, bounds);
    }

    public static void drawFrame(Screen screen, Rect r) {
        drawFrame(screen, r.x(), r.y(), r.right(), r.bottom());
    }

    /** A single-line box with corners at (left, top) and (right, bottom). */
    public static void drawFrame(Screen screen, int left, int top, int right, int bottom) {
        TextGraphics graphics = screen.newTextGraphics();
        graphics.setForegroundColor(TextColor.ANSI.DEFAULT);
        graphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
        graphics.drawLine(new TerminalPosition(left, top), new TerminalPosition(right, top), Symbols.SINGLE_LINE_HORIZONTAL);
        graphics.drawLine(new TerminalPosition(left, bottom), new TerminalPosition(right, bottom), Symbols.SINGLE_LINE_HORIZONTAL);
        graphics.drawLine(new TerminalPosition(left, top), new TerminalPosition(left, bottom), Symbols.SINGLE_LINE_VERTICAL);
        graphics.drawLine(new TerminalPosition(right, top), new TerminalPosition(right, bottom), Symbols.SINGLE_LINE_VERTICAL);
        graphics.setCharacter(left, top, Symbols.SINGLE_LINE_TOP_LEFT_CORNER);
        graphics.setCharacter(right, top, Symbols.SINGLE_LINE_TOP_RIGHT_CORNER);
        graphics.setCharacter(left, bottom, Symbols.SINGLE_LINE_BOTTOM_LEFT_CORNER);
        graphics.setCharacter(right, bottom, Symbols.SINGLE_LINE_BOTTOM_RIGHT_CORNER);
    }

    /**
     * A scrollbar thumb drawn over a frame's vertical border at column {@code x}, along the rows between
     * {@code top} and {@code bottom} (the border's corners): {@code visible} of {@code total} rows shown,
     * starting at row {@code first}. Draws nothing when everything fits.
     */
    public static void drawScrollbar(Screen screen, int x, int top, int bottom, int first, int visible, int total) {
        int track = bottom - top - 1;
        if (total <= visible || track <= 0) {
            return;
        }
        int thumb = Math.max(1, Math.round((float) track * visible / total));
        int start = top + 1 + Math.round((float) (track - thumb) * first / (total - visible));
        TextGraphics graphics = screen.newTextGraphics();
        graphics.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT);
        graphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
        graphics.drawLine(new TerminalPosition(x, start), new TerminalPosition(x, start + thumb - 1), Symbols.DOUBLE_LINE_VERTICAL);
    }
}
