package net.shchoo.scafe.scafe.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A kind of monster in a world (content, like a skill). Its drops are one of a kind: each kill has a 1 in
 * 2^(n+1) chance (n = drops found so far) of dropping the next one, and once it has dropped it never drops
 * again for anyone in this world.
 */
public class MonsterType {
    public final int id;
    public final String name;
    public final int level;
    public final int maxHp;
    public final int maxHit;
    public final int colorIndex;
    public final char symbol;
    /** How far (either axis) from where it appeared it wanders. */
    public final int roamRadius;
    /** Ticks per step when it moves (players take 1). */
    public final int moveTicks;
    /** Already dropped (so gone for good), in the order they were found. */
    public final List<Drop> drops = new ArrayList<>();

    /** A one-of-a-kind neutral product (made by no recipe), found at 1 in {@code oneIn} by {@code foundBy}. */
    public record Drop(Product product, int oneIn, String foundBy) {}

    public MonsterType(int id, String name, int level, int maxHp, int maxHit, int colorIndex, char symbol,
                       int roamRadius, int moveTicks) {
        this.id = id;
        this.name = name;
        this.level = level;
        this.maxHp = maxHp;
        this.maxHit = maxHit;
        this.colorIndex = colorIndex;
        this.symbol = symbol;
        this.roamRadius = roamRadius;
        this.moveTicks = Math.max(1, moveTicks);
    }

    /** 1 in this many kills drops the next one-of-a-kind item (2, 4, 8, ...). */
    public int nextDropOneIn() {
        return oneInForDrop(drops.size());
    }

    public static int oneInForDrop(int index) {
        return 1 << Math.min(index + 1, 30);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MonsterType other && other.id == id;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(id);
    }
}
