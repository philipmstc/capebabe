package net.shchoo.scafe.scafe.model;

/**
 * Lifetime counts kept per character (in each world), shown in the Stats tab. Saved by name, so never rename one
 * once shipped; adding one is just a new constant.
 */
public enum Stat {
    /** Ticks spent moving (running counts once a tick, like walking). */
    STEPS("Steps"),
    ITEMS_CRAFTED("Items crafted"),
    SKILLS_DISCOVERED("Skills discovered"),
    MONSTERS_DISCOVERED("Monsters discovered"),
    MONSTERS_KILLED("Monsters killed"),
    /** In ticks, counted while you're online. */
    TIME_PLAYED("Time played");

    public final String label;

    Stat(String label) {
        this.label = label;
    }
}
