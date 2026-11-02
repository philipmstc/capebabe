package net.shchoo.scafe.scafe.model;

/**
 * What colour a hit is, and what colour its target is, as a hue on the colour wheel in degrees (red 0, yellow 60,
 * green 120, cyan 180, blue 240, magenta 300), or colourless: {@link #WHITE_HUE} / {@link #BLACK_HUE}. A hue beats
 * the one 120 degrees on (x2) and loses to the one 120 degrees back (x1/2), smoothly in between ({@link #exponent});
 * white beats every hue and loses to the colourless, black the other way round.
 * <p>
 * Every skill has a hue of its own ({@link #skillHue}); monsters have one of the six (0-5, 60 degrees apart) or
 * {@link #WHITE} / {@link #BLACK} ({@link #hueOf}).
 */
public final class DamageColor {
    public static final int HUES = 6;
    public static final int WHITE = 6;
    public static final int BLACK = 7;
    /** Bare hands. */
    public static final int FISTS = BLACK;
    /** Colourless, as hues. */
    public static final double WHITE_HUE = -1, BLACK_HUE = -2;

    /**
     * Skill colour index {@code i}'s hue: the first six 60 degrees apart from red (0, 60 ... 300), then each six after
     * in the middle of the biggest gaps left -- 30, 90 ... 330; then 15, 75 ...; 45, 105 ...; then 7.5 ..., 37.5 ...,
     * 22.5 ..., 52.5 ... (each six turned on by 60 x the six's number with its binary digits reversed after the point).
     */
    public static double skillHue(int i) {
        return (60 * reversed(Math.floorDiv(i, HUES)) + 60.0 * Math.floorMod(i, HUES)) % 360;
    }

    /** {@code n}'s binary digits reversed after the point: 1 -> .1 (0.5), 2 -> .01, 3 -> .11, 4 -> .001 ... */
    static double reversed(int n) {
        double value = 0, place = 0.5;
        for (; n > 0; n >>= 1, place /= 2) {
            value += (n & 1) * place;
        }
        return value;
    }

    /** A monster's colour (0-5, {@link #WHITE}, {@link #BLACK}) as a hue, or colourless. */
    public static double hueOf(int color) {
        return color == WHITE ? WHITE_HUE : color == BLACK ? BLACK_HUE : Math.floorMod(color, HUES) * 60.0;
    }

    public static boolean colorless(double hue) {
        return hue < 0;
    }

    private DamageColor() {}

    /** An item's hue: its making skill's, a drop's monster's, else colourless black. */
    public static double hue(WorldContent content, Product product) {
        Recipe recipe = content.madeBy(product);
        if (recipe != null) {
            return skillHue(recipe.skill.colorIndex);
        }
        MonsterType monster = content.droppedBy(product);
        return monster != null ? hueOf(monster.colorIndex) : BLACK_HUE;
    }

    /** How much {@code attacker}-coloured damage a {@code defender}-coloured target takes (monster colours). */
    public static double multiplier(int attacker, int defender) {
        return hueMultiplier(hueOf(attacker), hueOf(defender));
    }

    /** How much damage of hue {@code attacker} a target of hue {@code defender} takes. */
    public static double hueMultiplier(double attacker, double defender) {
        if (attacker == WHITE_HUE) {
            return colorless(defender) ? 0.5 : 2;
        }
        if (attacker == BLACK_HUE) {
            return colorless(defender) ? 2 : 0.5;
        }
        if (colorless(defender)) {
            return 1;
        }
        double degrees = ((defender - attacker) % 360 + 360) % 360;
        return Math.pow(2, exponent((degrees > 180 ? degrees - 360 : degrees) / 180.0));
    }

    /** theta = hue difference / 180 degrees, in (-1, 1]: +-1 at +-2/3 (120 degrees), 0 at 0 and 1. */
    static double exponent(double theta) {
        double t = Math.abs(theta);
        double f = t <= 2.0 / 3 ? Math.sin(t * 0.75 * Math.PI) : Math.sin((1 - t) * 1.5 * Math.PI);
        return Math.signum(theta) * f;
    }
}
