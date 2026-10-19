package net.shchoo.scafe.scafe.client.ui;

/** A box on screen: top-left corner (x, y) and size in cells, borders included. */
public record Rect(int x, int y, int width, int height) {

    public int right() {
        return x + width - 1;
    }

    public int bottom() {
        return y + height - 1;
    }
}
