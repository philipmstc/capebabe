package net.shchoo.scafe.scafe.client.ui;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.screen.Screen;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the mouse can click this frame. Widgets add an area for each thing they draw that does something when
 * clicked (cleared at the start of every frame, so it always matches the screen). A click goes to the area on the
 * highest {@link #layer} under the pointer -- popups over the map, the bank's window over everything -- and among
 * those to the one added last (drawn on top). Options ({@link #option}) light up while the mouse is over them.
 * A popup's click-off area ({@link #addClickOff}) doesn't stop the click: it closes the popup and the click goes on
 * to what's visible there (the map: walk there, as in RuneScape).
 */
public final class Clicks {
    public enum Button { LEFT, RIGHT }

    /** What a click on an area does; {@code x}, {@code y} is the cell clicked. */
    public interface Handler {
        void click(Button button, int x, int y);
    }

    /** Ordinary widgets. */
    public static final int BASE = 0;
    /** Popups (drawn on top). */
    public static final int POPUP = 1;
    /** Windows that take every click while open (bank, station menu, the log-out question). */
    public static final int MODAL = 2;

    /** The background of the option under the mouse. */
    public static final TextColor HOVER = TextColor.ANSI.BLACK_BRIGHT;

    private record Area(int layer, int x, int y, int width, int height, Handler handler, boolean highlight, boolean clickOff) {
        boolean contains(int cx, int cy) {
            return cx >= x && cy >= y && cx < x + width && cy < y + height;
        }
    }

    private final List<Area> areas = new ArrayList<>();

    public void clear() {
        areas.clear();
    }

    public void add(int layer, Rect rect, Handler handler) {
        add(layer, rect.x(), rect.y(), rect.width(), rect.height(), handler);
    }

    public void add(int layer, int x, int y, int width, int height, Handler handler) {
        areas.add(new Area(layer, x, y, width, height, handler, false, false));
    }

    /** Like {@link #add}, for a menu option, button or row: it's highlighted while hovered. */
    public void option(int layer, int x, int y, int width, int height, Handler handler) {
        areas.add(new Area(layer, x, y, width, height, handler, true, false));
    }

    /** Anywhere on screen (under everything else on this layer): e.g. a popup closing when you click off it. */
    public void addEverywhere(int layer, Handler handler) {
        add(layer, 0, 0, Layout.TERMINAL_SIZE.getColumns(), Layout.TERMINAL_SIZE.getRows(), handler);
    }

    /** Everywhere on screen outside what's drawn over it: {@code closed} runs, then the click goes on to what's under it. */
    public void addClickOff(int layer, Runnable closed) {
        areas.add(new Area(layer, 0, 0, Layout.TERMINAL_SIZE.getColumns(), Layout.TERMINAL_SIZE.getRows(),
                (button, x, y) -> closed.run(), false, true));
    }

    /** Handles a click at {@code x}, {@code y}; false if nothing's there. */
    public boolean click(Button button, int x, int y) {
        Area best = at(x, y);
        boolean any = false;
        for (Area area : List.copyOf(areas)) {
            if (area.clickOff && above(area, best)) {
                area.handler.click(button, x, y);
                any = true;
            }
        }
        if (best == null) {
            return any;
        }
        best.handler.click(button, x, y);
        return true;
    }

    /** The layer of what's visible at {@code x}, {@code y} (-1: nothing): BASE = nothing's drawn over it. */
    public int layerAt(int x, int y) {
        Area area = at(x, y);
        return area == null ? -1 : area.layer;
    }

    private boolean above(Area area, Area other) {
        return other == null || area.layer > other.layer || area.layer == other.layer && areas.indexOf(area) > areas.indexOf(other);
    }

    /** Gives the option under the mouse (if what's on top there is one) the {@link #HOVER} background. */
    public void highlight(Screen screen, int mouseX, int mouseY) {
        Area area = at(mouseX, mouseY);
        if (area == null || !area.highlight) {
            return;
        }
        for (int y = area.y; y < area.y + area.height; y++) {
            for (int x = area.x; x < area.x + area.width; x++) {
                TextCharacter cell = screen.getBackCharacter(x, y);
                if (cell != null) {
                    // grey text would vanish on it
                    TextCharacter lit = cell.getForegroundColor() == HOVER ? cell.withForegroundColor(TextColor.ANSI.WHITE) : cell;
                    screen.setCharacter(new TerminalPosition(x, y), lit.withBackgroundColor(HOVER));
                }
            }
        }
    }

    private Area at(int x, int y) {
        Area best = null;
        for (Area area : areas) {
            if (!area.clickOff && area.contains(x, y) && (best == null || area.layer >= best.layer)) {
                best = area;
            }
        }
        return best;
    }
}
