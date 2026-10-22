package net.shchoo.scafe.scafe.model;

/**
 * What colour a hit is, and what colour its target is: 0-5 are hues 60 degrees apart (red, yellow, green, cyan,
 * blue, magenta -- the same order as skills' colours, bright and dark alike), {@link #WHITE} and {@link #BLACK}
 * are colourless. Each hue beats the one 120 degrees on (x2) and loses to the one 120 degrees back (x1/2); white
 * beats every hue and loses to the colourless, black the other way round.
 */
public final class DamageColor {
    public static final int HUES = 6;
    public static final int WHITE = 6;
    public static final int BLACK = 7;
    /** Bare hands. */
    public static final int FISTS = BLACK;

    private DamageColor() {}

    public static boolean colorless(int color) {
        return color == WHITE || color == BLACK;
    }

    /** An item's colour: its making skill's, a drop's monster's, else colourless black. */
    public static int of(WorldContent content, Product product) {
        Recipe recipe = content.madeBy(product);
        if (recipe != null) {
            return Math.floorMod(recipe.skill.colorIndex, HUES);
        }
        MonsterType monster = content.droppedBy(product);
        return monster != null ? monster.colorIndex : BLACK;
    }

    /** How much {@code attacker}-coloured damage a {@code defender}-coloured target takes. */
    public static double multiplier(int attacker, int defender) {
        if (attacker == WHITE) {
            return colorless(defender) ? 0.5 : 2;
        }
        if (attacker == BLACK) {
            return colorless(defender) ? 2 : 0.5;
        }
        if (colorless(defender)) {
            return 1;
        }
        int degrees = Math.floorMod((defender - attacker) * 60, 360);
        return Math.pow(2, exponent((degrees > 180 ? degrees - 360 : degrees) / 180.0));
    }

    /** theta = hue difference / 180 degrees, in (-1, 1]: +-1 at +-2/3 (120 degrees), 0 at 0 and 1. */
    static double exponent(double theta) {
        double t = Math.abs(theta);
        double f = t <= 2.0 / 3 ? Math.sin(t * 0.75 * Math.PI) : Math.sin((1 - t) * 1.5 * Math.PI);
        return Math.signum(theta) * f;
    }
}
