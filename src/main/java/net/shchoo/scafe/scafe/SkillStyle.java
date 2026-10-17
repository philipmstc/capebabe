package net.shchoo.scafe.scafe;

import com.googlecode.lanterna.TextColor;

import java.util.*;

public class SkillStyle {
    private static final HashMap<Skill, TextColor.ANSI> map = new HashMap<>();
    private static final List<TextColor.ANSI> colorOrder = List.of(
            TextColor.ANSI.RED_BRIGHT,
            TextColor.ANSI.YELLOW_BRIGHT,
            TextColor.ANSI.GREEN_BRIGHT,
            TextColor.ANSI.CYAN_BRIGHT,
            TextColor.ANSI.BLUE_BRIGHT,
            TextColor.ANSI.MAGENTA_BRIGHT);

    private static final Map<SkillType, List<Character>> symbolsByType = Map.of(
            SkillType.PRIMARY, Arrays.asList('#', '%', '!'),
            SkillType.SECONDARY, Arrays.asList('$', '?', '='),
            SkillType.TERTIARY, Arrays.asList('@', '&', '+'));

    private static final Map<Skill, Character> actualSymbolForSkill = new HashMap<>();

    public static char getSymbolForSkill(Skill skill) {
        if (!actualSymbolForSkill.containsKey(skill)) {
            SkillType type = skill.getType();
            int sameType = (int) map.keySet().stream().map(Skill::getType).filter(type::equals).count();
            actualSymbolForSkill.put(skill, symbolsByType.get(type).get(sameType % 3));
        }
        return actualSymbolForSkill.get(skill);
    }

    public static TextColor.ANSI getColorForSkill(Skill skill) {
        if (!map.containsKey(skill)) {
            map.put(skill, colorOrder.get(map.size() % colorOrder.size()));
        }
        return map.get(skill);
    }

    /** The non-_BRIGHT version of a color. */
    public static TextColor.ANSI dim(TextColor.ANSI color) {
        return TextColor.ANSI.valueOf(color.name().replace("_BRIGHT", ""));
    }
}
