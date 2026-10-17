package net.shchoo.scafe.scafe;

import com.googlecode.lanterna.TextColor;

import java.util.function.Supplier;

/** Short-lived text that floats up from a world tile, e.g. "+200xp". */
public class Popup {
    public static final int LIFETIME_TICKS = 18;
    public static final int TICKS_PER_ROW = 3;

    private final Supplier<Board.Position> anchor;
    public final String text;
    public final TextColor color;
    public final TextColor background;
    private final int lifetime;
    private final int ticksPerRow;
    private int age = 0;

    public Popup(Board.Position at, String text, TextColor color) {
        this(() -> at, text, color, TextColor.ANSI.DEFAULT, LIFETIME_TICKS, TICKS_PER_ROW);
    }

    /** ticksPerRow 0 = stays put above its anchor. */
    public Popup(Supplier<Board.Position> anchor, String text, TextColor color, TextColor background, int lifetime, int ticksPerRow) {
        this.anchor = anchor;
        this.text = text;
        this.color = color;
        this.background = background;
        this.lifetime = lifetime;
        this.ticksPerRow = ticksPerRow;
    }

    public static Popup following(Walker.Mobile mobile, String text, TextColor color, TextColor background, int lifetime) {
        return new Popup(mobile::position, text, color, background, lifetime, 0);
    }

    public Board.Position at() {
        return anchor.get();
    }

    /** Returns true when expired. */
    public boolean tick() {
        return ++age >= lifetime;
    }

    public int rise() {
        return ticksPerRow == 0 ? 2 : 1 + age / ticksPerRow;
    }
}
