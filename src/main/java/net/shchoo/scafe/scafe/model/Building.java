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
                "\u2502RCH\u2502"),
        /** A station with 13 tiles of track running off to the right (the top row over the track is open). */
        STATION("Train station", "[q] choose a destination",
                // the track: rails brown, ties grey, and you can walk along it
                new String[]{"                                  ",
                        "        bbbbbbbbbbbbbbbbbbbbbbbbbb",
                        "        gggggggggggggggggggggggggg",
                        "        bbbbbbbbbbbbbbbbbbbbbbbbbb"},
                "\u250c\u2500\u2500\u2500\u2500\u2500\u2500\u2510. . . . . . . . . . . . . ",
                "\u2502TRAINS\u2502\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550",
                "\u2502TATION\u2502\u2502\u2502\u2502\u2502\u2502\u2502\u2502\u2502\u2502\u2502\u2502\u2502\u2502\u2502\u2502\u2502\u2502\u2502\u2502\u2502\u2502\u2502\u2502\u2502\u2502\u2502",
                "\u2514\u2500\u2500[ ]\u2500\u2518\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550"),
        /** A sign saying MERCH ANT over a stall, and the ant minding it. */
        MERCHANT("Merch Ant", "[q] the ant isn't open for business yet", '\u0436',
                "\u2502MERCH\u2502",
                "\u2502A N T\u2502",
                "\u2500\u2500\u2500\u2500\u2500\u2500\u2500",
                "\u2502  \u0436  \u2502");

        public final String label;
        /** What q does there, for the tile details. */
        public final String action;
        /** Who's minding it (drawn in their own colour, and they move), or 0 for no one. */
        public final char keeper;
        /**
         * Same shape as the picture, or null: per character, ' ' = the building's colour, a letter = another
         * ('b' brown, 'g' grey). A lowercase letter on a tile also means you can walk on that tile.
         */
        final String[] paint;
        /** Full resolution, top row first: tile, gap, tile, gap ... tile. */
        final String[] picture;

        Kind(String label, String action, String... picture) {
            this(label, action, (char) 0, null, picture);
        }

        Kind(String label, String action, char keeper, String... picture) {
            this(label, action, keeper, null, picture);
        }

        Kind(String label, String action, String[] paint, String... picture) {
            this(label, action, (char) 0, paint, picture);
        }

        Kind(String label, String action, char keeper, String[] paint, String... picture) {
            this.label = label;
            this.action = action;
            this.keeper = keeper;
            this.paint = paint;
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

    /** A tile you can walk on (the station's track), though it's part of the building. */
    public boolean walkable(Position p) {
        return covers(p) && Character.isLowerCase(paintAt(p, 0));
    }

    /** Its tiles you can't walk on (the station itself, not its track). */
    public List<Position> solidTiles() {
        return tiles().stream().filter(t -> !walkable(t)).toList();
    }

    /** The paint (see {@link Kind#paint}) on a tile ({@code offset} 0) or the gap left (-1) / right (1) of it. */
    public char paintAt(Position p, int offset) {
        if (kind.paint == null) {
            return ' ';
        }
        String row = kind.paint[p.y() - corner.y()];
        int column = 2 * (p.x() - corner.x()) + offset;
        return column < 0 || column >= row.length() ? ' ' : row.charAt(column);
    }

    /** Whether a character of its picture (on a tile or in a gap) is the one minding it (the merchant's ant). */
    public boolean isKeeper(char c) {
        return kind.keeper != 0 && c == kind.keeper;
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

    /** The open tile just below a station's door, where you get on and off. */
    public Position platform() {
        return new Position(corner.x() + 2, corner.y() + kind.height());
    }

    /** Within {@code distance} tiles (either axis) of any of its tiles. */
    public boolean within(Position p, int distance) {
        return tiles().stream().anyMatch(t -> Math.max(Math.abs(t.x() - p.x()), Math.abs(t.y() - p.y())) <= distance);
    }

    /** Within {@code distance} tiles of one you can't walk on: at the station itself, not just on its track. */
    public boolean withinSolid(Position p, int distance) {
        return solidTiles().stream().anyMatch(t -> Math.max(Math.abs(t.x() - p.x()), Math.abs(t.y() - p.y())) <= distance);
    }
}
