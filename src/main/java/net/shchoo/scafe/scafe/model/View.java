package net.shchoo.scafe.scafe.model;

/** How much of the world a player sees: the map view, in tiles, centred on them. */
public final class View {
    public static final int WIDTH = 29;
    public static final int HEIGHT = 23;
    /** Tiles visible either side of you. */
    public static final int HALF_WIDTH = WIDTH / 2;
    public static final int HALF_HEIGHT = HEIGHT / 2;

    private View() {}
}
