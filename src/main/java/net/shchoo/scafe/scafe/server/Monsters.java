package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.model.MonsterType;
import net.shchoo.scafe.scafe.model.Position;

import java.util.*;
import java.util.function.Predicate;

/** All monsters currently out in a world: spawning, wandering, despawning. */
class Monsters {
    /** Chance per tick (in %) that an idle monster sets off somewhere (within its kind's roam radius). */
    static final int WANDER_PERCENT = 25;
    /** With no player within this many tiles for {@link #DESPAWN_TICKS} ticks, a monster goes away. */
    static final int DESPAWN_RANGE = 24;
    static final int DESPAWN_TICKS = 100;

    private final Map<Integer, Monster> byId = new LinkedHashMap<>();
    private final Predicate<Position> passable;
    private int nextId = -1;

    Monsters() {
        this(position -> true);
    }

    Monsters(Predicate<Position> passable) {
        this.passable = passable;
    }

    Monster spawn(MonsterType type, Position at) {
        Monster monster = new Monster(nextId--, type, at);
        // the leash is part of what's passable for it, so no path can ever take it outside its roam box
        monster.walker.setPassable(p -> passable.test(p) && distance(p, monster.home) <= type.roamRadius);
        byId.put(monster.id, monster);
        return monster;
    }

    void remove(Monster monster) {
        byId.remove(monster.id);
    }

    boolean contains(Monster monster) {
        return byId.get(monster.id) == monster;
    }

    Collection<Monster> all() {
        return byId.values();
    }

    Monster get(int id) {
        return byId.get(id);
    }

    static int distance(Position a, Position b) {
        return Math.max(Math.abs(a.x() - b.x()), Math.abs(a.y() - b.y()));
    }

    /** The closest monster on or next to {@code at} (diagonals count), or null. */
    Monster nextTo(Position at) {
        return byId.values().stream()
                .filter(m -> distance(m.position(), at) <= 1)
                .min(Comparator.comparingInt((Monster m) -> distance(m.position(), at)).thenComparingInt(m -> -m.id))
                .orElse(null);
    }

    int near(Position at, int radius) {
        return (int) byId.values().stream().filter(m -> distance(m.position(), at) <= radius).count();
    }

    boolean occupied(Position at) {
        return byId.values().stream().anyMatch(m -> m.position().equals(at));
    }

    /** Wanders idle monsters and despawns lonely ones. Returns the monsters that moved. */
    List<Monster> tick(Random random, Collection<GameCharacter> players) {
        List<Monster> moved = new ArrayList<>();
        for (Monster monster : List.copyOf(byId.values())) {
            boolean anyoneNear = players.stream().anyMatch(p -> distance(p.position(), monster.position()) <= DESPAWN_RANGE);
            monster.lonelyTicks = anyoneNear ? 0 : monster.lonelyTicks + 1;
            if (monster.fighters.isEmpty() && monster.lonelyTicks > DESPAWN_TICKS) {
                remove(monster);
                continue;
            }
            if (!monster.fighters.isEmpty()) {
                continue;
            }
            if (!monster.walker.isWalking()) {
                if (random.nextInt(100) >= WANDER_PERCENT) {
                    continue;
                }
                int radius = monster.type.roamRadius;
                monster.walker.walkTo(new Position(
                        monster.home.x() + random.nextInt(2 * radius + 1) - radius,
                        monster.home.y() + random.nextInt(2 * radius + 1) - radius));
                monster.stepCooldown = monster.type.moveTicks;
            }
            if (--monster.stepCooldown > 0) {
                continue;
            }
            monster.stepCooldown = monster.type.moveTicks;
            Position before = monster.position();
            if (monster.walker.tick() == Walker.Result.BLOCKED) {
                continue;
            }
            if (!monster.position().equals(before)) {
                moved.add(monster);
            }
        }
        return moved;
    }
}
