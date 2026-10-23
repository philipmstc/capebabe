package net.shchoo.scafe.scafe.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Something built into the world, several tiles big, that blocks movement like a node. {@code corner} is the
 * top-left of its picture. Pictures are drawn at the map's own resolution: each row alternates a tile's character
 * and the gap between two tiles (tiles are two columns apart), so text can run through the gaps; a "." where a
 * tile goes means that tile isn't part of the building.
 * <pre>
 * bank (4 x 3)     church (3 x 4, the cross on top on its own)
 * \u2554\u2500\u2550\u2500\u2550\u2500\u2557          . \u253c .
 * \u2502 B A \u2502          / C \\
 * \u2502 N K \u2502          \u2502HUR\u2502
 *                  \u2502C H\u2502
 * </pre>
 */
public record Building(int id, Kind kind, Position corner) {
    public enum Kind {
        BANK("Bank", "[q] open: deposit / withdraw items",
                "\u2554\u2500\u2550\u2500\u2550\u2500\u2557",
                "\u2502 B A \u2502",
                "\u2502 N K \u2502"),
        CHURCH("Church", "[q] pray: restores all your hp",
                ". \u253c .",
                "\u2554\u2500\u2567\u2500\u2557",
                "\u2502CHU\u2502",
                "\u2502RCH\u2502");

        public final String label;
        /** What q does there, for the tile details. */
        public final String action;
        /** Full resolution, top row first: tile, gap, tile, gap ... tile. */
        final String[] picture;

        Kind(String label, String action, String... picture) {
            this.label = label;
            this.action = action;
            this.picture = picture;
        }

        /** In tiles. */
        public int width() {
            return (picture[0].length() + 1) / 2;
        }

        public int height() {
            return picture.length;
        }

        boolean hasTile(int dx, int dy) {
            return dx >= 0 && dy >= 0 && dx < width() && dy < height() && picture[dy].charAt(2 * dx) != '.';
        }
    }

    public boolean covers(Position p) {
        return kind.hasTile(p.x() - corner.x(), p.y() - corner.y());
    }

    public List<Position> tiles() {
        List<Position> tiles = new ArrayList<>();
        for (int dy = 0; dy < kind.height(); dy++) {
            for (int dx = 0; dx < kind.width(); dx++) {
                if (kind.hasTile(dx, dy)) {
                    tiles.add(new Position(corner.x() + dx, corner.y() + dy));
                }
            }
        }
        return tiles;
    }

    /** What's drawn on one of its tiles. */
    public char glyph(Position p) {
        return at(p, 0);
    }

    /** What's drawn in the gap left of one of its tiles (' ' = nothing). */
    public char leftOf(Position p) {
        return at(p, -1);
    }

    /** What's drawn in the gap right of one of its tiles (' ' = nothing). */
    public char rightOf(Position p) {
        return at(p, 1);
    }

    private char at(Position p, int offset) {
        String row = kind.picture[p.y() - corner.y()];
        int column = 2 * (p.x() - corner.x()) + offset;
        return column < 0 || column >= row.length() ? ' ' : row.charAt(column);
    }

    /** Within {@code distance} tiles (either axis) of any of its tiles. */
    public boolean within(Position p, int distance) {
        return tiles().stream().anyMatch(t -> Math.max(Math.abs(t.x() - p.x()), Math.abs(t.y() - p.y())) <= distance);
    }
}
