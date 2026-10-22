package net.shchoo.scafe.scafe.client;

import net.shchoo.scafe.scafe.model.MonsterType;
import net.shchoo.scafe.scafe.model.Skill;

import com.googlecode.lanterna.TextColor;
import java.util.List;

import static com.googlecode.lanterna.TextColor.ANSI.*;

public class SkillStyle {
    private static final List<TextColor.ANSI> colorOrder = List.of(
            TextColor.ANSI.RED_BRIGHT,
            TextColor.ANSI.YELLOW_BRIGHT,
            TextColor.ANSI.GREEN_BRIGHT,
            TextColor.ANSI.CYAN_BRIGHT,
            TextColor.ANSI.BLUE_BRIGHT,
            TextColor.ANSI.MAGENTA_BRIGHT);

    private static final List<TextColor.ANSI> monsterColors = List.of(
            TextColor.ANSI.RED_BRIGHT,
            TextColor.ANSI.MAGENTA,
            TextColor.ANSI.YELLOW,
            TextColor.ANSI.RED,
            CYAN,
            GREEN);

    /** Players pick one of these in settings (index = {@code colorIndex}, {@code Intent.PLAYER_COLORS} of them). */
    public static final List<TextColor.ANSI> PLAYER_COLORS = List.of(
            TextColor.ANSI.CYAN_BRIGHT,
            TextColor.ANSI.GREEN_BRIGHT,
            TextColor.ANSI.YELLOW_BRIGHT,
            TextColor.ANSI.MAGENTA_BRIGHT,
            TextColor.ANSI.RED_BRIGHT,
            TextColor.ANSI.BLUE_BRIGHT,
            TextColor.ANSI.WHITE_BRIGHT,
            CYAN,
            GREEN,
            TextColor.ANSI.YELLOW,
            TextColor.ANSI.MAGENTA,
            TextColor.ANSI.RED);
    public static final List<String> PLAYER_COLOR_NAMES = List.of(
            "cyan", "lime", "yellow", "magenta", "red", "blue", "white", "teal", "green", "brown", "purple", "maroon");

    public static TextColor.ANSI playerColor(int index) {
        return PLAYER_COLORS.get(Math.floorMod(index, PLAYER_COLORS.size()));
    }

    public static TextColor.ANSI getColorForMonster(MonsterType type) {
        return monsterColors.get(type.colorIndex % monsterColors.size());
    }

    public static char getSymbolForSkill(Skill skill) {
        return skill.symbol;
    }

    public static TextColor.ANSI getColorForSkill(Skill skill) {
        return colorOrder.get(skill.colorIndex % colorOrder.size());
    }

    public static TextColor.ANSI darkOf(Skill skill) {
        return switch (colorOrder.get(skill.colorIndex % colorOrder.size())) {
            case CYAN_BRIGHT -> CYAN;
            case GREEN_BRIGHT -> GREEN;
            case YELLOW_BRIGHT -> YELLOW;
            case MAGENTA_BRIGHT -> MAGENTA;
            case RED_BRIGHT -> RED;
            case BLUE_BRIGHT -> BLUE;
            default -> BLACK_BRIGHT;
        };
    }

    /** The non-_BRIGHT version of a color. */
    public static TextColor.ANSI dim(TextColor.ANSI color) {
        return TextColor.ANSI.valueOf(color.name().replace("_BRIGHT", ""));
    }
}
