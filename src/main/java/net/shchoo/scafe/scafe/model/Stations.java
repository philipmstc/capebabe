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

    /**
     * A station's roof (the top row of its picture, 4 tiles and the gaps between) with its number on it, so you can
     * tell them apart: "\u250c\u2500 3 \u2500\u2500\u2510", "\u250c\u2500 12 \u2500\u2510".
     */
    public static String roof(int index) {
        String label = " " + index + " ";
        if (label.length() > 6) {
            label = label.substring(0, 6);
        }
        int left = (6 - label.length()) / 2, right = 6 - label.length() - left;
        return "\u250c" + "\u2500".repeat(left) + label + "\u2500".repeat(right) + "\u2510";
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

    /** How far (either axis) a town's station can be from its bank. */
    public static final int TOWN_RADIUS = 40;
    /** New towns are only found at least this far (straight-line) from every town's station. */
    public static final int TOWN_DISTANCE = 1000;

    /**
     * The towns' stations, in line order: a town is a station with a bank, church, gym and Merch Ant round it (the
     * first by the origin, the rest found far out). Found from the buildings alone, so client and server agree: each
     * bank's nearest station, if it's within {@link #TOWN_RADIUS}.
     */
    public static List<Building> towns(WorldContent content) {
        List<Building> line = all(content);
        java.util.Set<Building> towns = new java.util.HashSet<>();
        for (Building bank : content.buildings()) {
            if (bank.kind() != Building.Kind.BANK) {
                continue;
            }
            line.stream().min(Comparator.comparingLong((Building b) -> distanceSquared(b.platform(), bank.corner()))
                            .thenComparingInt(line::indexOf))
                    .filter(b -> Math.max(Math.abs(b.platform().x() - bank.corner().x()),
                            Math.abs(b.platform().y() - bank.corner().y())) <= TOWN_RADIUS)
                    .ifPresent(towns::add);
        }
        return line.stream().filter(towns::contains).toList();
    }

    public static boolean isTown(WorldContent content, Building station) {
        return towns(content).contains(station);
    }

    /** Town to town: free, a line whatever the distance, and a longer ride. */
    public static boolean betweenTowns(WorldContent content, Building a, Building b) {
        List<Building> towns = towns(content);
        return !a.equals(b) && towns.contains(a) && towns.contains(b);
    }

    /** At least {@link #TOWN_DISTANCE} from every town, where a new one could be found (with a new station). */
    public static boolean farFromTowns(WorldContent content, Position at) {
        List<Building> towns = towns(content);
        long far = (long) TOWN_DISTANCE * TOWN_DISTANCE;
        return !towns.isEmpty() && towns.stream().allMatch(t -> distanceSquared(t.platform(), at) >= far);
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
     * A line between the two, which runs both ways: either is one of the other's {@link #neighbours}, or both are
     * towns ({@link #betweenTowns}). So every
     * station has lines to its 2 nearest, and one that's somebody else's nearest has that line too -- nobody
     * gets stranded at a station they can't ride back from. Stations that only have each other get a line to the
     * rest too ({@link #bridges}).
     */
    public static boolean connected(WorldContent content, Building a, Building b) {
        return !a.equals(b) && (neighbours(content, a).contains(b) || neighbours(content, b).contains(a)
                || betweenTowns(content, a, b) || bridges(content).stream().anyMatch(pair -> pair.contains(a) && pair.contains(b)));
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

    /**
     * Strictly inside the line's outline: the smallest convex shape around every station's platform (a rubber band
     * round them). New stations are only found outside it, so the line grows outwards. With fewer than 3 stations,
     * or all in a row, the outline has no inside and nothing is.
     */
    public static boolean insideLine(WorldContent content, Position at) {
        List<Position> hull = outline(all(content).stream().map(Building::platform).toList());
        if (hull.size() < 3) {
            return false;
        }
        for (int i = 0; i < hull.size(); i++) {
            if (cross(hull.get(i), hull.get((i + 1) % hull.size()), at) <= 0) {
                return false;
            }
        }
        return true;
    }

    /** The convex hull, anticlockwise (x right, y up; on screen, with y down, it's clockwise), no points in a row. */
    public static List<Position> outline(List<Position> points) {
        List<Position> sorted = points.stream().distinct()
                .sorted(Comparator.comparingInt(Position::x).thenComparingInt(Position::y)).toList();
        if (sorted.size() < 3) {
            return sorted;
        }
        List<Position> backwards = new java.util.ArrayList<>(sorted);
        java.util.Collections.reverse(backwards);
        List<Position> hull = new java.util.ArrayList<>();
        for (List<Position> pass : List.of(sorted, backwards)) {   // one side, then back along the other
            List<Position> chain = new java.util.ArrayList<>();
            for (Position p : pass) {
                while (chain.size() >= 2 && cross(chain.get(chain.size() - 2), chain.get(chain.size() - 1), p) <= 0) {
                    chain.remove(chain.size() - 1);
                }
                chain.add(p);
            }
            hull.addAll(chain.subList(0, chain.size() - 1));
        }
        return hull;
    }

    /** Positive if o -> a -> b turns anticlockwise (x right, y up), 0 if they're in a row. */
    private static long cross(Position o, Position a, Position b) {
        return (long) (a.x() - o.x()) * (b.y() - o.y()) - (long) (a.y() - o.y()) * (b.x() - o.x());
    }

    /** Where you can ride from {@code from}: every station it has a line to, in line order. */
    public static List<Building> destinations(WorldContent content, Building from) {
        return all(content).stream().filter(b -> connected(content, from, b)).toList();
    }

    // ---- trips: several stops, a fare each ----

    /** Ticks on the train a stop... */
    public static final int TRAIN_TICKS = 5;
    /** ...and a stop that's town to town (free, however far: {@link #betweenTowns}). */
    public static final int TOWN_TRAIN_TICKS = 3 * TRAIN_TICKS;

    /**
     * A trip from one station to another along the lines: the stations it stops at after the first (the last is
     * where you're going), the fares due (a skill's item for each stop that isn't free, by skill), and how long it
     * takes.
     */
    public record Route(List<Building> stops, Map<Skill, Integer> fares, int ticks) {
        public int fareCount() {
            return fares.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    /**
     * The trip from {@code from} to {@code to}: the fewest stops, and of those the fewest fares (ties to stations
     * found first). Each stop costs the fare to that station ({@link #fareSkill}) unless it's town to town, and
     * takes {@link #TRAIN_TICKS} ({@link #TOWN_TRAIN_TICKS} town to town). Null if there's no way (or it's here).
     */
    public static Route route(WorldContent content, Building from, Building to) {
        List<Building> line = all(content);
        int n = line.size(), start = line.indexOf(from), end = line.indexOf(to);
        if (start < 0 || end < 0 || start == end) {
            return null;
        }
        java.util.Set<Building> towns = new java.util.HashSet<>(towns(content));
        boolean[][] linked = new boolean[n][n];
        for (int a = 0; a < n; a++) {
            for (int b = a + 1; b < n; b++) {
                linked[a][b] = linked[b][a] = connected(content, line.get(a), line.get(b));
            }
        }
        // fewest stops, then fewest fares: costs as stops * n + fares, n being more fares than any trip has
        long[] cost = new long[n];
        int[] previous = new int[n];
        java.util.Arrays.fill(cost, Long.MAX_VALUE);
        java.util.Arrays.fill(previous, -1);
        boolean[] done = new boolean[n];
        cost[start] = 0;
        for (int round = 0; round < n; round++) {
            int at = -1;
            for (int i = 0; i < n; i++) {
                if (!done[i] && cost[i] != Long.MAX_VALUE && (at < 0 || cost[i] < cost[at])) {
                    at = i;
                }
            }
            if (at < 0) {
                break;
            }
            done[at] = true;
            for (int next = 0; next < n; next++) {
                if (!linked[at][next] || done[next]) {
                    continue;
                }
                boolean free = towns.contains(line.get(at)) && towns.contains(line.get(next));
                long step = (long) (n + 1) + (free || fareSkill(content, next) == null ? 0 : 1);
                if (cost[at] + step < cost[next]) {
                    cost[next] = cost[at] + step;
                    previous[next] = at;
                }
            }
        }
        if (previous[end] < 0) {
            return null;
        }
        List<Building> stops = new java.util.ArrayList<>();
        Map<Skill, Integer> fares = new java.util.LinkedHashMap<>();
        int ticks = 0;
        for (int at = end; at != start; at = previous[at]) {
            stops.add(0, line.get(at));
        }
        Building last = from;
        for (Building stop : stops) {
            boolean free = towns.contains(last) && towns.contains(stop);
            Skill fare = free ? null : fareSkill(content, line.indexOf(stop));
            if (fare != null) {
                fares.merge(fare, 1, Integer::sum);
            }
            ticks += free ? TOWN_TRAIN_TICKS : TRAIN_TICKS;
            last = stop;
        }
        return new Route(List.copyOf(stops), fares, ticks);
    }

    /** What a ticket to anywhere is called: one pays any one fare. */
    public static final String TICKET = "ticket to anywhere";

    /** The ticket item, or null until someone first buys one. */
    public static Product ticket(WorldContent content) {
        return content.products().stream().filter(p -> TICKET.equals(p.name) && p.base == null).findFirst().orElse(null);
    }

    /**
     * How a trip's fares get paid: {@code items} from the inventory (each fare skill's cheapest first), {@code
     * tickets} to make up the rest; {@code missing} what can't be paid for, by skill (empty: it's all paid).
     */
    public record Payment(Map<Product, Integer> items, int tickets, Map<Skill, Integer> missing) {
        public boolean paid() {
            return missing.isEmpty();
        }
    }

    /** Paying {@code route}'s fares with what's in {@code inventory}, then up to {@code tickets} tickets. */
    public static Payment payment(WorldContent content, Route route, Map<Product, Integer> inventory, int tickets) {
        Map<Product, Integer> items = new java.util.LinkedHashMap<>();
        Map<Skill, Integer> missing = new java.util.LinkedHashMap<>();
        int ticketsLeft = tickets, used = 0;
        for (Map.Entry<Skill, Integer> fare : route.fares().entrySet()) {
            int due = fare.getValue();
            for (Product item : cheapestFirst(content, fare.getKey(), inventory)) {
                int take = Math.min(due, inventory.get(item));
                if (take > 0) {
                    items.merge(item, take, Integer::sum);
                    due -= take;
                }
            }
            int byTicket = Math.min(due, ticketsLeft);
            ticketsLeft -= byTicket;
            used += byTicket;
            if (due > byTicket) {
                missing.put(fare.getKey(), due - byTicket);
            }
        }
        return new Payment(items, used, missing);
    }

    /** {@code skill}'s items among {@code items} (any held), cheapest first: lowest tier, then plain before prestige. */
    public static List<Product> cheapestFirst(WorldContent content, Skill skill, Map<Product, Integer> items) {
        return items.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(Map.Entry::getKey)
                .filter(item -> {
                    Recipe recipe = content.madeBy(item);
                    return recipe != null && recipe.skill.equals(skill);
                })
                .sorted(Comparator.comparingInt((Product item) -> item.root().tier).thenComparingInt(item -> item.prestige))
                .toList();
    }

    /** What a ticket costs: one item of each of these (every skill that makes things). */
    public static List<Skill> ticketSkills(WorldContent content) {
        return content.craftingSkills().stream()
                .filter(s -> !s.productLines.isEmpty() && !s.productLines.get(0).recipes.isEmpty()).toList();
    }

    /** How many tickets what's in {@code bank} would buy (none while there are no skills). */
    public static int ticketsAffordable(WorldContent content, Map<Product, Integer> bank) {
        List<Skill> skills = ticketSkills(content);
        return skills.isEmpty() ? 0 : skills.stream().mapToInt(skill -> cheapestFirst(content, skill, bank).stream()
                .mapToInt(bank::get).sum()).min().orElse(0);
    }

    /** The bank items {@code count} tickets take (each skill's cheapest first), or null if there aren't enough. */
    public static Map<Product, Integer> ticketPrice(WorldContent content, Map<Product, Integer> bank, int count) {
        if (count <= 0 || ticketsAffordable(content, bank) < count) {
            return null;
        }
        Map<Product, Integer> take = new java.util.LinkedHashMap<>();
        for (Skill skill : ticketSkills(content)) {
            int due = count;
            for (Product item : cheapestFirst(content, skill, bank)) {
                int n = Math.min(due, bank.get(item));
                take.put(item, n);
                due -= n;
                if (due == 0) {
                    break;
                }
            }
        }
        return take;
    }

    private static long distanceSquared(Building a, Building b) {
        return distanceSquared(a.platform(), b.platform());
    }

    private static long distanceSquared(Position a, Position b) {
        long dx = a.x() - b.x(), dy = a.y() - b.y();
        return dx * dx + dy * dy;
    }
}
