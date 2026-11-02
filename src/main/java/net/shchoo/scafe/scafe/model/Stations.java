package net.shchoo.scafe.scafe.model;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The train line: every station in the world, in the order they were found ("tation0" is the one near the origin).
 * Going to station N costs one item made by skill N -- any tier, plain or prestige -- round again if there are more
 * stations than skills; free while there are no skills.
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

    /** The skill whose items pay the fare to station {@code index} (one of any of them), or null for free. */
    public static Skill fareSkill(WorldContent content, int index) {
        List<Skill> skills = content.craftingSkills();
        for (int i = 0; i < skills.size(); i++) {
            Skill skill = skills.get((index + i) % skills.size());
            if (!skill.productLines.isEmpty() && !skill.productLines.get(0).recipes.isEmpty()) {
                return skill;
            }
        }
        return null;
    }

    /**
     * What of {@code inventory} pays the fare to station {@code index}: the cheapest of the fare skill's items held
     * (lowest tier, then plain before prestige), or null if none (or it's free: see {@link #fareSkill}).
     */
    public static Product fareFrom(WorldContent content, int index, Map<Product, Integer> inventory) {
        Skill skill = fareSkill(content, index);
        if (skill == null) {
            return null;
        }
        return inventory.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(Map.Entry::getKey)
                .filter(item -> {
                    Recipe recipe = content.madeBy(item);
                    return recipe != null && recipe.skill.equals(skill);
                })
                .min(Comparator.comparingInt((Product item) -> item.root().tier).thenComparingInt(item -> item.prestige))
                .orElse(null);
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
     * gets stranded at a station they can't ride back from. Stations that only have each other get a line to the
     * rest too ({@link #bridges}).
     */
    public static boolean connected(WorldContent content, Building a, Building b) {
        return !a.equals(b) && (neighbours(content, a).contains(b) || neighbours(content, b).contains(a)
                || bridges(content).stream().anyMatch(pair -> pair.contains(a) && pair.contains(b)));
    }

    /**
     * The extra lines that make the network one: while it's in separate pieces, the shortest line between any two of
     * them (ties to the stations found first). Found from the stations alone, so client and server agree.
     */
    static List<List<Building>> bridges(WorldContent content) {
        List<Building> line = all(content);
        int[] piece = new int[line.size()];
        for (int i = 0; i < piece.length; i++) {
            piece[i] = i;
        }
        for (int i = 0; i < piece.length; i++) {
            for (Building near : neighbours(content, line.get(i))) {
                join(piece, piece[i], piece[line.indexOf(near)]);
            }
        }
        List<List<Building>> bridges = new java.util.ArrayList<>();
        while (java.util.Arrays.stream(piece).distinct().count() > 1) {
            int from = -1, to = -1;
            long best = Long.MAX_VALUE;
            for (int i = 0; i < piece.length; i++) {
                for (int j = i + 1; j < piece.length; j++) {
                    long distance = distanceSquared(line.get(i), line.get(j));
                    if (piece[i] != piece[j] && distance < best) {
                        best = distance;
                        from = i;
                        to = j;
                    }
                }
            }
            bridges.add(List.of(line.get(from), line.get(to)));
            join(piece, piece[from], piece[to]);
        }
        return bridges;
    }

    private static void join(int[] piece, int a, int b) {
        for (int i = 0; i < piece.length; i++) {
            if (piece[i] == b) {
                piece[i] = a;
            }
        }
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
