package net.shchoo.scafe.scafe;

import com.googlecode.lanterna.TextColor;

import java.util.List;

public class SkillStyle {
    private static final List<TextColor.ANSI> colorOrder = List.of(
            TextColor.ANSI.RED_BRIGHT,
            TextColor.ANSI.YELLOW_BRIGHT,
            TextColor.ANSI.GREEN_BRIGHT,
            TextColor.ANSI.CYAN_BRIGHT,
            TextColor.ANSI.BLUE_BRIGHT,
            TextColor.ANSI.MAGENTA_BRIGHT);

    public static char getSymbolForSkill(Skill skill) {
        return skill.symbol;
    }

    public static TextColor.ANSI getColorForSkill(Skill skill) {
        return colorOrder.get(skill.colorIndex % colorOrder.size());
    }

    /** The non-_BRIGHT version of a color. */
    public static TextColor.ANSI dim(TextColor.ANSI color) {
        return TextColor.ANSI.valueOf(color.name().replace("_BRIGHT", ""));
    }
}
