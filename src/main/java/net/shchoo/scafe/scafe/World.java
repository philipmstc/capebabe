package net.shchoo.scafe.scafe;

public class World {
    public final int id;
    public final String name;
    public final long seed;
    public final WorldContent content = new WorldContent();

    public World(int id, String name, long seed) {
        this.id = id;
        this.name = name;
        this.seed = seed;
    }
}
