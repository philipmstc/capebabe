package net.shchoo.scafe.scafe.model;

/**
 * Lifetime counts kept per character (in each world), shown in the Stats tab. Saved by name, so never rename one
 * once shipped; adding one is just a new constant.
 */
public enum Stat {
    /** Ticks spent moving (running counts once a tick, like walking). */
    STEPS("Steps"),
    /** All of them, AFK too. */
    ITEMS_CRAFTED("Items crafted"),
    /** The ones of those made AFK. */
    ITEMS_CRAFTED_AFK("Items crafted AFK"),
    SKILLS_DISCOVERED("Skills discovered"),
    MONSTERS_DISCOVERED("Monsters discovered"),
    MONSTERS_KILLED("Monsters killed"),
    /** In fights and at the gym. */
    DAMAGE_DEALT("Damage dealt"),
    /** In ticks, at the gym. */
    TIME_TRAINED("Time trained", true),
    /** At a church: blessings (not heals). */
    TIMES_PRAYED("Times prayed"),
    ITEMS_TITHED("Items tithed"),
    /** In ticks, counted while you're online. */
    TIME_PLAYED("Time played", true);

    public final String label;
    /** Counted in ticks, shown as a duration ("3h 05m"). */
    public final boolean ticks;

    Stat(String label) {
        this(label, false);
    }

    Stat(String label, boolean ticks) {
        this.label = label;
        this.ticks = ticks;
    }
}
