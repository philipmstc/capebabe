package net.shchoo.scafe.scafe;

import com.googlecode.lanterna.TextColor;

import java.util.function.Supplier;

/** Short-lived text that floats up from a world tile, e.g. "+200xp". */
public class Popup {
    public static final int LIFETIME_FRAMES = 18;
    public static final int FRAMES_PER_ROW = 3;

    private final Supplier<Board.Position> anchor;
    public final String text;
    public final TextColor color;
    public final TextColor background;
    private final int lifetime;
    private final int framesPerRow;
    private int age = 0;

    public Popup(Board.Position at, String text, TextColor color) {
        this(() -> at, text, color, TextColor.ANSI.DEFAULT, LIFETIME_FRAMES, FRAMES_PER_ROW);
    }

    /** Lifetime and rise speed are in render frames. framesPerRow 0 = stays put above its anchor. */
    public Popup(Supplier<Board.Position> anchor, String text, TextColor color, TextColor background, int lifetime, int framesPerRow) {
        this.anchor = anchor;
        this.text = text;
        this.color = color;
        this.background = background;
        this.lifetime = lifetime;
        this.framesPerRow = framesPerRow;
    }

    public static Popup following(Supplier<Board.Position> anchor, String text, TextColor color, TextColor background, int lifetime) {
        return new Popup(anchor, text, color, background, lifetime, 0);
    }

    public Board.Position at() {
        return anchor.get();
    }

    /** Returns true when expired. */
    public boolean advanceFrame() {
        return ++age >= lifetime;
    }

    public int rise() {
        return framesPerRow == 0 ? 2 : 1 + age / framesPerRow;
    }
}
