package net.shchoo.scafe.scafe;

import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.screen.Screen;

import java.util.Locale;

import static net.shchoo.scafe.scafe.Main.HEADER_POS_Y;

/** Top-right: "# skill0 Lv. 5 [  203/1.2k  ] 600k/h" for the tracked skill. */
public class XpTrackerView {
    private static final int BAR_WIDTH = 16;

    public static void draw(Player player, Screen screen, int minX) {
        Skill skill = player.xpTracker.skill();
        if (skill == null) {
            return;
        }
        int level = player.getLevel(skill);
        boolean maxed = level >= Skill.XP_PER_LEVEL.length;
        long levelStart = Skill.XP_PER_LEVEL[level - 1];
        long xp = player.getExperience(skill);
        long span = maxed ? 1 : Skill.XP_PER_LEVEL[level] - levelStart;
        long into = maxed ? 1 : xp - levelStart;

        String name = skill.name + " Lv. " + level + " ";
        Long rate = player.xpTracker.xpPerHour();
        String rateText = " " + (rate == null ? "--" : compact(rate)) + "/h";
        String barText = maxed ? "MAX" : compact(into) + "/" + compact(span);
        int width = 2 + name.length() + BAR_WIDTH + rateText.length();
        int x = Math.max(minX, screen.getTerminalSize().getColumns() - width);

        TextGraphics graphics = screen.newTextGraphics();
        graphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
        graphics.setForegroundColor(SkillStyle.getColorForSkill(skill));
        graphics.setCharacter(x, HEADER_POS_Y, SkillStyle.getSymbolForSkill(skill));
        graphics.setForegroundColor(TextColor.ANSI.DEFAULT);
        graphics.putString(x + 2, HEADER_POS_Y, name);

        int barX = x + 2 + name.length();
        int filled = (int) (BAR_WIDTH * into / span);
        int textStart = (BAR_WIDTH - barText.length()) / 2;
        for (int i = 0; i < BAR_WIDTH; i++) {
            int t = i - textStart;
            char c = t >= 0 && t < barText.length() ? barText.charAt(t) : ' ';
            TextColor bg = i < filled ? TextColor.ANSI.GREEN : TextColor.ANSI.BLACK_BRIGHT;
            screen.setCharacter(barX + i, HEADER_POS_Y, TextCharacter.fromCharacter(c, TextColor.ANSI.WHITE_BRIGHT, bg)[0]);
        }

        graphics.setForegroundColor(rate == null ? TextColor.ANSI.BLACK_BRIGHT : TextColor.ANSI.DEFAULT);
        graphics.putString(barX + BAR_WIDTH, HEADER_POS_Y, rateText);
    }

    static String compact(long n) {
        if (n >= 1_000_000_000) return trim(n / 1_000_000_000.0) + "B";
        if (n >= 1_000_000) return trim(n / 1_000_000.0) + "M";
        if (n >= 1_000) return trim(n / 1_000.0) + "k";
        return String.valueOf(n);
    }

    private static String trim(double d) {
        return d >= 100 ? String.valueOf((long) d) : String.format(Locale.ROOT, "%.1f", d).replaceAll("\\.0$", "");
    }
}
