package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.model.Stat;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.screen.Screen;

import java.util.Locale;

/**
 * Your levels (worked out from your xp), then your lifetime counts in this world ({@link Stat}): steps, items
 * crafted, discoveries, kills, trophies (from the world's drops). View-only.
 */
public class StatsMenu extends Menu {
    private static final int VALUE_X = 24;

    public StatsMenu() {
        super("Stats");
    }

    @Override
    protected boolean canFocus(Player player, Board board) {
        return false;
    }

    @Override
    public Result handleKey(KeyStroke key, Player player, Board board) {
        return Result.RELEASE;
    }

    @Override
    public void draw(Screen screen, Player player, Board board) {
        TextGraphics g = screen.newTextGraphics();
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        int y = firstRow();
        y = row(g, y, "Total level", player.totalLevel());
        y = row(g, y, "Total xp gained", player.totalExperience());
        y = row(g, y, "Highest level", player.highestLevel());
        y++;
        for (Stat stat : Stat.values()) {
            y = row(g, y, stat.label, player.stats.getOrDefault(stat, 0L));
        }
        row(g, y, "Trophies collected", player.trophies());
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        g.putString(textX(), hintRow(), "in this world");
    }

    private int row(TextGraphics g, int y, String label, long value) {
        g.setForegroundColor(TextColor.ANSI.DEFAULT);
        g.putString(textX(), y, label);
        g.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT);
        g.putString(textX() + VALUE_X, y, String.format(Locale.ROOT, "%,d", value));
        return y + 1;
    }
}
