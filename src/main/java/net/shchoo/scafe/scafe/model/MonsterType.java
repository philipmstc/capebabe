package net.shchoo.scafe.scafe.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A kind of monster in a world (content, like a skill). Its drops come in order, each a limited run: drop k
 * (from 0) drops k + 1 times in all ({@link #copiesOf}), to anyone, then never again in this world; each kill has a
 * 1 in 2^(k+1) chance of dropping the next copy of the drop that's due. {@link #drops} is every find, in order.
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

    /** 1 in this many kills drops the next copy of the drop that's due (2, 4, 8, ...). */
    public int nextDropOneIn() {
        return oneInForDrop(nextDropIndex());
    }

    /** How many times drop {@code index} drops in all: the first once, the second twice, ... */
    public static int copiesOf(int index) {
        return index + 1;
    }

    /** The different drops found so far, in order. */
    public List<Product> dropProducts() {
        return drops.stream().map(Drop::product).distinct().toList();
    }

    /** How many copies of {@code product} have been found. */
    public int found(Product product) {
        return (int) drops.stream().filter(d -> d.product().equals(product)).count();
    }

    /** The drop that's due: the latest while it has copies left, else the next new one. */
    public int nextDropIndex() {
        List<Product> products = dropProducts();
        int last = products.size() - 1;
        return last >= 0 && found(products.get(last)) < copiesOf(last) ? last : products.size();
    }

    /** The item the next find is: the latest drop's, if it has copies left, else null (a new one). */
    public Product nextDropProduct() {
        List<Product> products = dropProducts();
        int next = nextDropIndex();
        return next < products.size() ? products.get(next) : null;
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
