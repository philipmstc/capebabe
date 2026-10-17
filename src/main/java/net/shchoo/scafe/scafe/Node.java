package net.shchoo.scafe.scafe;

/** Something on the map the player can use. For now always a recipe; other kinds (bank, monster spawn...) later. */
public class Node {
    public static final int UNLIMITED = 0;
    /** In game ticks ({@link GameClock#TICK_MILLIS} each). */
    public static final int DEFAULT_DURATION_TICKS = 2;

    final int id;
    final Board.Position position;
    final Recipe recipe;
    /** Max concurrent users, {@link #UNLIMITED} for no limit. */
    final int capacity;
    /** Uses before it runs out, {@link #UNLIMITED} for never. */
    final int maxCharges;
    final int respawnTicks;

    public Node(int id, Board.Position position, Recipe recipe, int capacity, int maxCharges, int respawnTicks) {
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
