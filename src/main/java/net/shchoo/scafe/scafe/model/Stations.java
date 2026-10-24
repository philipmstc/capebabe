package net.shchoo.scafe.scafe.model;

import java.util.List;

/**
 * The train line: every station in the world, in the order they were found ("tation0" is the one near the origin).
 * Going to station N costs one of skill N's first item (skillN{0}-0), round again if there are more stations
 * than skills; free while there are no skills.
 */
public final class Stations {
    private Stations() {}

    public static List<Building> all(WorldContent content) {
        return content.buildings().stream().filter(b -> b.kind() == Building.Kind.STATION).toList();
    }

    /** "tation0", "tation1"... (the S fell off the sign). */
    public static String name(int index) {
        return "tation" + index;
    }

    /** The fare to station {@code index}: one of this, or null for free. */
    public static Product fare(WorldContent content, int index) {
        List<Skill> skills = content.craftingSkills();
        for (int i = 0; i < skills.size(); i++) {
            Skill skill = skills.get((index + i) % skills.size());
            if (!skill.productLines.isEmpty() && !skill.productLines.get(0).recipes.isEmpty()) {
                return skill.productLines.get(0).recipes.get(0).product;
            }
        }
        return null;
    }

    /** The station you're at (within a tile of the station itself; its track doesn't count), or null. */
    public static Building near(WorldContent content, Position position) {
        return all(content).stream().filter(b -> b.withinSolid(position, 1)).findFirst().orElse(null);
    }

    /** How many stations a station has a line to: its nearest. */
    public static final int NEIGHBOURS = 2;

    /** Its {@link #NEIGHBOURS} nearest stations (by platform, straight-line; ties to the one found first). */
    public static List<Building> neighbours(WorldContent content, Building from) {
        List<Building> line = all(content);
        return line.stream().filter(b -> !b.equals(from))
                .sorted(java.util.Comparator.comparingLong((Building b) -> distanceSquared(b, from)).thenComparingInt(line::indexOf))
                .limit(NEIGHBOURS).toList();
    }

    /**
     * A line between the two, which runs both ways: either is one of the other's {@link #neighbours}. So every
     * station has lines to its 2 nearest, and one that's somebody else's nearest has that line too -- nobody
     * gets stranded at a station they can't ride back from.
     */
    public static boolean connected(WorldContent content, Building a, Building b) {
        return !a.equals(b) && (neighbours(content, a).contains(b) || neighbours(content, b).contains(a));
    }

    /** Where you can ride from {@code from}: every station it has a line to, in line order. */
    public static List<Building> destinations(WorldContent content, Building from) {
        return all(content).stream().filter(b -> connected(content, from, b)).toList();
    }

    private static long distanceSquared(Building a, Building b) {
        long dx = a.platform().x() - b.platform().x(), dy = a.platform().y() - b.platform().y();
        return dx * dx + dy * dy;
    }
}
