package net.shchoo.scafe.scafe.model;

/** Something on the map the player can use. For now always a recipe; other kinds (bank, monster spawn...) later. */
public class Node {
    public static final int UNLIMITED = 0;
    /** In game ticks ({@link GameClock#TICK_MILLIS} each). */
    public static final int DEFAULT_DURATION_TICKS = 2;

    public final int id;
    public final Position position;
    public final Recipe recipe;
    /** Max concurrent users, {@link #UNLIMITED} for no limit. */
    public final int capacity;
    /** Uses before it runs out, {@link #UNLIMITED} for never. */
    public final int maxCharges;
    public final int respawnTicks;

    public Node(int id, Position position, Recipe recipe, int capacity, int maxCharges, int respawnTicks) {
        this.id = id;
        this.position = position;
        this.recipe = recipe;
        this.capacity = capacity;
        this.maxCharges = maxCharges;
        this.respawnTicks = respawnTicks;
    }

    public Skill skill() {
        return recipe.skill;
    }

    public int durationTicks() {
        return DEFAULT_DURATION_TICKS;
    }

    /**
     * How many more a node holds with {@code online} players in the world: one per power of 4 (1-3: 0, 4-15: 1,
     * 16-63: 2, ...).
     */
    public static int capacityBonus(int online) {
        int bonus = 0;
        for (int n = online; n >= 4; n /= 4) {
            bonus++;
        }
        return bonus;
    }

    /** How many can use it at once with {@code bonus} ({@link #capacityBonus}); {@link #UNLIMITED} stays so. */
    public int capacity(int bonus) {
        return capacity == UNLIMITED ? UNLIMITED : capacity + bonus;
    }

    public String label() {
        return recipe.product.name;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Node other && other.id == id;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(id);
    }
}
