package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.SkillStyle;
import net.shchoo.scafe.scafe.client.XpTarget;
import net.shchoo.scafe.scafe.model.Skill;

import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.screen.Screen;
import java.util.Locale;

import static net.shchoo.scafe.scafe.client.ui.Layout.*;

/**
 * One line in the header row, over the map, for the tracked skill: "# skill0 Lv. 5 [ 203/1.2k ] 600k/h ~42m" heading
 * for the next level, or "# skill0 Lv. 54\u219270 [ 150k/737k ] 600k/h ~3h 05m" for your nearest goal in it ({@link XpTarget}).
 */
public class XpTrackerView extends Widget {
    private static final int BAR_WIDTH = 16;
    private static final int MIN_NAME = 8;

    public XpTrackerView(Rect bounds) {
        super(bounds);
    }

    @Override
    public void draw(Screen screen, Player player, Board board) {
        int x = bounds().x() + 1, row = bounds().y(), width = bounds().width() - 1;
        Skill skill = player.xpTracker.skill();
        if (skill == null) {
            TextGraphics idle = screen.newTextGraphics();
            idle.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            idle.putString(x, row, fit("xp tracker: do something", width));
            return;
        }
        XpTarget target = XpTarget.of(player, skill);
        Long rate = player.xpTracker.xpPerHour();
        String levelText = target.goal()
                ? "Lv. " + target.level() + "\u2192" + target.targetLevel() + " "
                : "Lv. " + target.level() + " ";
        String barText = target.maxed() ? "MAX"
                : compact(target.xp() - target.fromXp()) + "/" + compact(target.toXp() - target.fromXp());
        String rateText = " " + (rate == null ? "--" : compact(rate)) + "/h";
        String etaText = rate == null || rate == 0 || target.maxed() ? ""
                : " ~" + duration(target.remaining() * MILLIS_PER_HOUR / rate);
        int fixed = 2 + levelText.length() + BAR_WIDTH + rateText.length();
        if (width - fixed - etaText.length() < MIN_NAME) {
            // a long skill name gets cut first; the time estimate goes only if there'd be next to no name left
            etaText = "";
        }
        String name = fit(skill.name + " ", Math.max(0, width - fixed - etaText.length()));

        TextGraphics graphics = screen.newTextGraphics();
        graphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
        graphics.setForegroundColor(SkillStyle.getColorForSkill(skill));
        graphics.setCharacter(x, row, SkillStyle.getSymbolForSkill(skill));
        graphics.setForegroundColor(TextColor.ANSI.DEFAULT);
        graphics.putString(x + 2, row, name);
        // heading for a goal: the level part in yellow, like goals elsewhere
        graphics.setForegroundColor(target.goal() ? TextColor.ANSI.YELLOW_BRIGHT : TextColor.ANSI.DEFAULT);
        graphics.putString(x + 2 + name.length(), row, levelText);

        int barX = x + 2 + name.length() + levelText.length();
        int filled = (int) (BAR_WIDTH * target.fraction());
        int textStart = (BAR_WIDTH - barText.length()) / 2;
        for (int i = 0; i < BAR_WIDTH; i++) {
            int t = i - textStart;
            char c = t >= 0 && t < barText.length() ? barText.charAt(t) : ' ';
            TextColor bg = i < filled ? TextColor.ANSI.GREEN : TextColor.ANSI.BLACK_BRIGHT;
            screen.setCharacter(barX + i, row, TextCharacter.fromCharacter(c, TextColor.ANSI.WHITE_BRIGHT, bg)[0]);
        }

        graphics.setForegroundColor(rate == null ? TextColor.ANSI.BLACK_BRIGHT : TextColor.ANSI.DEFAULT);
        graphics.putString(barX + BAR_WIDTH, row, rateText);

        if (player.showTimeEstimate) {
            graphics.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            graphics.putString(barX + BAR_WIDTH + rateText.length(), row, etaText);
        }
    }

    private static final long MILLIS_PER_HOUR = 60 * 60 * 1000;

    /** Roughly how long: "<1m", "42m", "3h 05m", "2d 4h". */
    static String duration(long millis) {
        long minutes = millis / 60_000;
        if (minutes < 1) return "<1m";
        if (minutes < 60) return minutes + "m";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h " + String.format(Locale.ROOT, "%02d", minutes % 60) + "m";
        return hours / 24 + "d " + hours % 24 + "h";
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
