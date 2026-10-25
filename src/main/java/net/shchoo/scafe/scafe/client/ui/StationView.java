package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.SkillStyle;
import net.shchoo.scafe.scafe.model.Building;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.Recipe;
import net.shchoo.scafe.scafe.model.Stations;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.screen.Screen;

import java.util.List;

import static net.shchoo.scafe.scafe.client.ui.Layout.*;

/**
 * The station menu, over the map while {@link Player#stationMenuOpen} (q at a station): every station on the line,
 * "tationN (x, y)" and its fare (1 x skillN{0}-0, in that skill's colour). Only the stations this one has a line
 * to ({@link Stations#connected}: each station's 2 nearest, both ways) can be picked; the rest say "no direct line". Up/Down pick, Enter rides, Esc closes.
 * It takes every key while open. Meanwhile the right-hand box shows the line map ({@link #drawLineMap}).
 * <pre>
 * Choose destination
 * > tation0 (x1, y1)   here
 *   tation1 (x2, y2)   1 x skill1{0}-0
 * </pre>
 */
public class StationView extends Widget {
    private static final int WIDTH = 46;
    private int selected;
    /** Whether the menu was up last time we looked, to pick a starting row when it opens. */
    private boolean open;

    public StationView() {
        super(new Rect(MAP.x() + (MAP.width() - WIDTH) / 2, MAP.y() + 3, WIDTH, MAP.height() - 6));
    }

    public int selectedIndex() {
        return selected;
    }

    @Override
    public boolean onKey(KeyStroke key, Player player, Board board) {
        if (!player.stationMenuOpen) {
            open = false;
            return false;
        }
        opened(player);
        List<Building> line = Stations.all(player.content);
        switch (key.getKeyType()) {
            case ArrowUp -> selected = step(player, line, -1);
            case ArrowDown -> selected = step(player, line, 1);
            case Escape -> player.stationMenuOpen = false;
            case Enter -> {
                if (selected < line.size() && reachable(player, line.get(selected))) {
                    player.rideTo(line.get(selected).id());
                    player.stationMenuOpen = false;
                }
            }
            default -> {}
        }
        return true;
    }

    /** Just opened: start on the first station that isn't this one. */
    private void opened(Player player) {
        if (open) {
            return;
        }
        open = true;
        List<Building> line = Stations.all(player.content);
        selected = 0;
        while (selected < line.size() - 1 && !reachable(player, line.get(selected))) {
            selected++;
        }
    }

    /** The next station you can ride to, up (-1) or down (1) the list; stays put if there's none. */
    private int step(Player player, List<Building> line, int direction) {
        for (int i = selected + direction; i >= 0 && i < line.size(); i += direction) {
            if (reachable(player, line.get(i))) {
                return i;
            }
        }
        return selected;
    }

    /** One of this station's neighbours: you can ride there from here. */
    private static boolean reachable(Player player, Building station) {
        Building here = here(player);
        return here != null && Stations.connected(player.content, here, station);
    }

    private static Building here(Player player) {
        return Stations.near(player.content, player.position());
    }

    @Override
    public void draw(Screen screen, Player player, Board board) {}

    @Override
    public void drawOnTop(Screen screen, Player player, Board board) {
        if (!player.stationMenuOpen) {
            open = false;
            return;
        }
        opened(player);
        List<Building> line = Stations.all(player.content);
        Building here = here(player);
        selected = Math.max(0, Math.min(selected, Math.max(0, line.size() - 1)));
        Rect box = bounds();
        board.clicks.addEverywhere(Clicks.MODAL, (button, cx, cy) -> {});
        int rows = Math.min(box.height(), line.size() + 4 + (line.size() < 2 ? 2 : 0));
        TextGraphics g = screen.newTextGraphics();
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        g.fillRectangle(new TerminalPosition(box.x(), box.y()), new TerminalSize(box.width(), rows), ' ');
        drawFrame(screen, new Rect(box.x(), box.y(), box.width(), rows));
        g.setForegroundColor(TextColor.ANSI.YELLOW_BRIGHT);
        g.enableModifiers(SGR.BOLD);
        g.putString(box.x() + 2, box.y() + 1, "Choose destination");
        g.disableModifiers(SGR.BOLD);

        int visible = rows - 4;
        int first = Math.max(0, Math.min(selected - visible + 1, line.size() - visible));
        for (int i = first; i < Math.min(line.size(), first + visible); i++) {
            Building station = line.get(i);
            int y = box.y() + 2 + (i - first);
            boolean isSelected = i == selected && reachable(player, station);
            boolean isHere = station.equals(here);
            boolean onTheLine = reachable(player, station);
            Position at = station.platform();
            String name = (isSelected ? "> " : "  ") + Stations.name(i) + " (" + at.x() + ", " + at.y() + ")";
            g.setForegroundColor(isHere || !onTheLine ? TextColor.ANSI.BLACK_BRIGHT
                    : isSelected ? TextColor.ANSI.WHITE_BRIGHT : TextColor.ANSI.DEFAULT);
            if (isSelected) {
                g.enableModifiers(SGR.BOLD);
            }
            g.putString(box.x() + 2, y, fit(name, 24));
            if (reachable(player, station)) {
                int pick = i;
                board.clicks.option(Clicks.MODAL, box.x() + 1, y, box.width() - 2, 1, (button, cx, cy) -> {
                    selected = pick;
                    player.rideTo(station.id());
                    player.stationMenuOpen = false;
                });
            }
            g.disableModifiers(SGR.BOLD);
            int x = box.x() + 27;
            if (isHere) {
                g.putString(x, y, "you're here");
                continue;
            }
            if (!onTheLine) {
                g.putString(x, y, "no direct line");
                continue;
            }
            Product fare = Stations.fare(player.content, i);
            if (fare == null) {
                g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
                g.putString(x, y, "free");
                continue;
            }
            boolean affordable = player.inventory.getOrDefault(fare, 0) > 0;
            Recipe recipe = player.content.madeBy(fare);
            g.setForegroundColor(affordable && recipe != null ? SkillStyle.getColorForSkill(recipe.skill)
                    : recipe != null ? SkillStyle.dim(SkillStyle.getColorForSkill(recipe.skill)) : TextColor.ANSI.DEFAULT);
            g.putString(x, y, fit("1 x " + fare.name, box.right() - x - 1));
        }
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        if (line.size() < 2) {
            g.putString(box.x() + 2, box.y() + 3, fit("No other stations yet: wander far from the line", box.width() - 4));
            g.putString(box.x() + 2, box.y() + 4, fit("and you may come across one.", box.width() - 4));
        }
        g.putString(box.x() + 2, box.y() + rows - 2, fit("[Up/Down] pick  [Enter] ride  [Esc] close", box.width() - 4));
        drawLineMap(screen, player, line, here);
    }

    /**
     * The line map, in the right-hand box (over the tabs) while the menu's up: every station as its number, where
     * it is (scaled to fit, a world tile two columns wide like the map), with a line between each connected pair
     * ({@link Stations#connected}), metro-map style ({@link Tracks}). Here is bright yellow, the one picked bold white on grey, the ones you can ride
     * to white, the rest grey; lines from here are white too.
     */
    static void drawLineMap(Screen screen, Player player, List<Building> line, Building here) {
        Rect box = TABS;
        TextGraphics g = screen.newTextGraphics();
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        g.fillRectangle(new TerminalPosition(box.x(), box.y()), new TerminalSize(box.width(), box.height()), ' ');
        drawFrame(screen, box);
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        g.putString(box.x() + 2, box.y(), "Line map");
        if (line.isEmpty()) {
            return;
        }
        int left = box.x() + 3, top = box.y() + 2, width = box.width() - 6, height = box.height() - 5;
        int minX = line.stream().mapToInt(b -> b.platform().x()).min().orElse(0);
        int maxX = line.stream().mapToInt(b -> b.platform().x()).max().orElse(0);
        int minY = line.stream().mapToInt(b -> b.platform().y()).min().orElse(0);
        int maxY = line.stream().mapToInt(b -> b.platform().y()).max().orElse(0);
        double spanX = Math.max(1, maxX - minX), spanY = Math.max(1, maxY - minY);
        double scale = Math.min((width - 1) / (2 * spanX), (height - 1) / spanY);
        double padX = ((width - 1) - 2 * spanX * scale) / 2, padY = ((height - 1) - spanY * scale) / 2;
        int[] cols = new int[line.size()], rows = new int[line.size()];
        for (int i = 0; i < line.size(); i++) {
            Position at = line.get(i).platform();
            cols[i] = left + (int) Math.round(padX + (at.x() - minX) * 2 * scale);
            rows[i] = top + (int) Math.round(padY + (at.y() - minY) * scale);
        }
        Tracks tracks = new Tracks(box, cols, rows);
        for (int a = 0; a < line.size(); a++) {
            for (int b = a + 1; b < line.size(); b++) {
                if (Stations.connected(player.content, line.get(a), line.get(b))) {
                    tracks.lay(a, b, line.get(a).equals(here) || line.get(b).equals(here));
                }
            }
        }
        tracks.draw(g);
        List<Building> reachable = here == null ? List.of() : Stations.destinations(player.content, here);
        for (int i = 0; i < line.size(); i++) {
            Building station = line.get(i);
            boolean isHere = station.equals(here);
            g.setForegroundColor(isHere ? TextColor.ANSI.YELLOW_BRIGHT
                    : reachable.contains(station) ? TextColor.ANSI.WHITE_BRIGHT : TextColor.ANSI.BLACK_BRIGHT);
            g.enableModifiers(SGR.BOLD);
            g.putString(cols[i], rows[i], label(i));
            g.disableModifiers(SGR.BOLD);
        }
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        int you = here == null ? -1 : line.indexOf(here);
        g.putString(box.x() + 2, box.bottom() - 1, fit((you >= 0 ? "you're at " + label(you) + "  " : "")
                + "N = tationN", box.width() - 4));
    }

    /** 0-9, then a-z, then * (the map has room for one character a station). */
    static String label(int index) {
        return index < 10 ? String.valueOf(index) : index < 36 ? String.valueOf((char) ('a' + index - 10)) : "*";
    }

    /**
     * The line map's lines, metro-map style: each one an L (along, then up or down), laid into a grid that
     * remembers which ways each cell's lines go, so where they meet or cross they join up (corners, tees,
     * crossings) instead of drawing over each other.
     */
    static final class Tracks {
        private static final int UP = 1, DOWN = 2, LEFT = 4, RIGHT = 8;
        private final Rect box;
        private final int[] cols;
        private final int[] rows;
        private final int[][] ways;
        private final boolean[][] bright;

        Tracks(Rect box, int[] cols, int[] rows) {
            this.box = box;
            this.cols = cols;
            this.rows = rows;
            ways = new int[box.height()][box.width()];
            bright = new boolean[box.height()][box.width()];
        }

        /** Station a to station b: the L whose elbow doesn't land on (or run through) another station, if either. */
        void lay(int a, int b, boolean fromHere) {
            int along = crossings(a, b, true), upFirst = crossings(a, b, false);
            List<int[]> path = path(a, b, along <= upFirst);
            for (int k = 1; k < path.size(); k++) {
                link(path.get(k - 1), path.get(k), fromHere);
            }
        }

        /** The cells from a to b: along to b's column then up/down to it ({@code alongFirst}), or the other way. */
        private List<int[]> path(int a, int b, boolean alongFirst) {
            List<int[]> cells = new java.util.ArrayList<>();
            int x = cols[a], y = rows[a];
            cells.add(new int[]{x, y});
            int elbowX = alongFirst ? cols[b] : cols[a], elbowY = alongFirst ? rows[a] : rows[b];
            while (x != elbowX || y != elbowY) {
                x += Integer.signum(elbowX - x);
                y += Integer.signum(elbowY - y);
                cells.add(new int[]{x, y});
            }
            while (x != cols[b] || y != rows[b]) {
                x += Integer.signum(cols[b] - x);
                y += Integer.signum(rows[b] - y);
                cells.add(new int[]{x, y});
            }
            return cells;
        }

        /** How many other stations that way would run through. */
        private int crossings(int a, int b, boolean alongFirst) {
            int count = 0;
            for (int[] cell : path(a, b, alongFirst)) {
                for (int i = 0; i < cols.length; i++) {
                    if (i != a && i != b && cols[i] == cell[0] && rows[i] == cell[1]) {
                        count++;
                    }
                }
            }
            return count;
        }

        private void link(int[] from, int[] to, boolean fromHere) {
            int dx = to[0] - from[0], dy = to[1] - from[1];
            mark(from, dx > 0 ? RIGHT : dx < 0 ? LEFT : dy > 0 ? DOWN : UP, fromHere);
            mark(to, dx > 0 ? LEFT : dx < 0 ? RIGHT : dy > 0 ? UP : DOWN, fromHere);
        }

        private void mark(int[] cell, int way, boolean fromHere) {
            int r = cell[1] - box.y(), c = cell[0] - box.x();
            if (r >= 0 && c >= 0 && r < ways.length && c < ways[0].length) {
                ways[r][c] |= way;
                bright[r][c] |= fromHere;
            }
        }

        /** Every cell a line goes through (stations are drawn over them after). */
        void draw(TextGraphics g) {
            for (int r = 0; r < ways.length; r++) {
                for (int c = 0; c < ways[r].length; c++) {
                    if (ways[r][c] != 0) {
                        g.setForegroundColor(bright[r][c] ? TextColor.ANSI.WHITE : TextColor.ANSI.BLACK_BRIGHT);
                        g.setCharacter(box.x() + c, box.y() + r, glyph(ways[r][c]));
                    }
                }
            }
        }

        /** The box-drawing character for the ways a cell's lines go. */
        static char glyph(int ways) {
            boolean up = (ways & UP) != 0, down = (ways & DOWN) != 0, left = (ways & LEFT) != 0, right = (ways & RIGHT) != 0;
            if (up && down && left && right) return '\u253c';
            if (up && down && right) return '\u251c';
            if (up && down && left) return '\u2524';
            if (left && right && down) return '\u252c';
            if (left && right && up) return '\u2534';
            if (down && right) return '\u250c';
            if (down && left) return '\u2510';
            if (up && right) return '\u2514';
            if (up && left) return '\u2518';
            if (up || down) return '\u2502';
            return '\u2500';
        }
    }
}
