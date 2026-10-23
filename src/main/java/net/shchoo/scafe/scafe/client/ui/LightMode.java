package net.shchoo.scafe.scafe.client.ui;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.screen.Screen;

/**
 * Light mode: every colour on screen inverted (255 - r, g, b), applied to the finished frame just before it's shown.
 * With the 16 terminal colours that's black <-> bright white, dark grey <-> white, and each colour <-> its
 * complement with dark and bright swapped (red <-> bright cyan, bright red <-> cyan, green <-> bright magenta...).
 * The terminal's default colours count as light grey on black.
 */
public final class LightMode {
    private LightMode() {}

    public static void invert(Screen screen) {
        TerminalSize size = screen.getTerminalSize();
        for (int y = 0; y < size.getRows(); y++) {
            for (int x = 0; x < size.getColumns(); x++) {
                TextCharacter c = screen.getBackCharacter(x, y);
                if (c != null) {
                    screen.setCharacter(x, y, c.withForegroundColor(invert(c.getForegroundColor(), true))
                            .withBackgroundColor(invert(c.getBackgroundColor(), false)));
                }
            }
        }
    }

    /** {@code foreground}: which default the terminal's DEFAULT stands for (light grey text, black paper). */
    static TextColor invert(TextColor color, boolean foreground) {
        if (color instanceof TextColor.ANSI ansi) {
            return switch (ansi) {
                case DEFAULT -> foreground ? TextColor.ANSI.BLACK : TextColor.ANSI.WHITE_BRIGHT;
                case BLACK -> TextColor.ANSI.WHITE_BRIGHT;
                case WHITE_BRIGHT -> TextColor.ANSI.BLACK;
                case BLACK_BRIGHT -> TextColor.ANSI.WHITE;
                case WHITE -> TextColor.ANSI.BLACK_BRIGHT;
                case RED -> TextColor.ANSI.CYAN_BRIGHT;
                case CYAN_BRIGHT -> TextColor.ANSI.RED;
                case RED_BRIGHT -> TextColor.ANSI.CYAN;
                case CYAN -> TextColor.ANSI.RED_BRIGHT;
                case GREEN -> TextColor.ANSI.MAGENTA_BRIGHT;
                case MAGENTA_BRIGHT -> TextColor.ANSI.GREEN;
                case GREEN_BRIGHT -> TextColor.ANSI.MAGENTA;
                case MAGENTA -> TextColor.ANSI.GREEN_BRIGHT;
                case BLUE -> TextColor.ANSI.YELLOW_BRIGHT;
                case YELLOW_BRIGHT -> TextColor.ANSI.BLUE;
                case BLUE_BRIGHT -> TextColor.ANSI.YELLOW;
                case YELLOW -> TextColor.ANSI.BLUE_BRIGHT;
            };
        }
        if (color instanceof TextColor.RGB rgb) {
            return new TextColor.RGB(255 - rgb.getRed(), 255 - rgb.getGreen(), 255 - rgb.getBlue());
        }
        return color;
    }
}
