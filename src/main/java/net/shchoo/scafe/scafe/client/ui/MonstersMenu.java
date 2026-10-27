package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.SkillStyle;
import net.shchoo.scafe.scafe.model.DamageColor;
import net.shchoo.scafe.scafe.model.MonsterType;
import net.shchoo.scafe.scafe.model.Product;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.screen.Screen;

import java.util.List;
import java.util.Locale;

import static net.shchoo.scafe.scafe.client.ui.Layout.*;

/**
 * Every kind of monster in the world, a row each:
 * <pre>
 * M monster3    Lv. 12  hp 40  hit 6   kills 3   !1/2
 * </pre>
 * (its symbol in its colour, level, hit points, max hit, how many you've killed, and of its drops found so far how
 * many are your trophies). Up/Down pick one; under the list, the picked one's colour and what beats it, how it
 * roams, its drops (each found / of how many, and by whom) and the odds of the next.
 */
public class MonstersMenu extends Menu {
    static final List<String> COLOR_NAMES = List.of("red", "yellow", "green", "cyan", "blue", "magenta", "white", "black");
    /** Rows under the list for the picked monster. */
    private static final int DETAIL_ROWS = 5;

    private int index;

    public MonstersMenu() {
        super("Monsters");
    }

    public int index() {
        return index;
    }

    @Override
    protected boolean canFocus(Player player, Board board) {
        return !player.content.monsters.isEmpty();
    }

    @Override
    protected void onFocus(Player player, Board board) {
        index = Math.min(index, player.content.monsters.size() - 1);
    }

    @Override
    public Result handleKey(KeyStroke key, Player player, Board board) {
        List<MonsterType> monsters = player.content.monsters;
        if (monsters.isEmpty()) {
            return Result.RELEASE;
        }
        switch (key.getKeyType()) {
            case ArrowUp -> {
                if (index == 0) return Result.RELEASE;
                index--;
            }
            case ArrowDown -> index = Math.min(monsters.size() - 1, index + 1);
            case Escape -> {
                return Result.RELEASE;
            }
            default -> {
                return Result.IGNORED;
            }
        }
        return Result.HANDLED;
    }

    /** Where the picked monster's rows start: above the hint, a blank row before it. */
    private int detailRow() {
        return hintRow() - DETAIL_ROWS - 1;
    }

    private int listRows() {
        return Math.max(1, detailRow() - 1 - firstRow());
    }

    @Override
    public void draw(Screen screen, Player player, Board board) {
        TextGraphics g = screen.newTextGraphics();
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        List<MonsterType> monsters = player.content.monsters;
        if (monsters.isEmpty()) {
            g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            g.putString(textX(), firstRow(), fit("No monsters yet. Keep exploring.", textWidth()));
            return;
        }
        index = Math.min(index, monsters.size() - 1);
        board.clicks.addScroll(Clicks.BASE, bounds(), step -> {
            claim(player, board);
            index = Math.max(0, Math.min(monsters.size() - 1, index + step));
        });
        int nameWidth = monsters.stream().mapToInt(m -> m.name.length()).max().orElse(8);
        int start = Math.max(0, Math.min(index - listRows() / 2, monsters.size() - listRows()));
        for (int i = start; i < Math.min(monsters.size(), start + listRows()); i++) {
            int y = firstRow() + (i - start);
            boolean selected = isFocused() && i == index;
            drawRow(g, player, monsters.get(i), y, nameWidth, selected);
            int pick = i;
            board.clicks.option(Clicks.BASE, textX(), y, textWidth(), 1, (button, cx, cy) -> {
                claim(player, board);
                index = pick;
            });
        }
        drawDetail(g, player, monsters.get(index), detailRow());
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        g.putString(textX(), hintRow(), fit(isFocused() ? "[Up/Down] pick  [Esc] back" : "[Down] browse monsters",
                textWidth()));
    }

    /** {@code M monster3    Lv. 12  hp 40  hit 6   kills 3   !1/2} */
    private void drawRow(TextGraphics g, Player player, MonsterType type, int y, int nameWidth, boolean selected) {
        if (selected) {
            g.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT);
            g.setCharacter(textX() - 1, y, '>');
            g.enableModifiers(SGR.BOLD);
        }
        g.setForegroundColor(SkillStyle.getColorForMonster(type));
        g.setCharacter(textX(), y, type.symbol);
        g.setForegroundColor(selected ? TextColor.ANSI.WHITE_BRIGHT : TextColor.ANSI.DEFAULT);
        String stats = String.format(Locale.ROOT, "%-" + nameWidth + "s  Lv. %-3d hp %-4d hit %-3d kills %-5s",
                type.name, type.level, type.maxHp, type.maxHit, ShortNumbers.of(player.kills.getOrDefault(type.id, 0L)));
        g.putString(textX() + 2, y, fit(stats, textWidth() - 2));
        long mine = type.drops.stream().filter(d -> player.name != null && player.name.equals(d.foundBy())).count();
        if (!type.drops.isEmpty() && textX() + 2 + stats.length() + 6 <= textX() + textWidth()) {
            g.setForegroundColor(mine > 0 ? TextColor.ANSI.YELLOW_BRIGHT : TextColor.ANSI.BLACK_BRIGHT);
            g.putString(textX() + 2 + stats.length(), y, "\u03a8" + mine + "/" + type.drops.size());
        }
        g.disableModifiers(SGR.BOLD);
    }

    /** The picked monster: its colour and what beats it, how it moves, its drops, and the next drop's odds. */
    private void drawDetail(TextGraphics g, Player player, MonsterType type, int y) {
        int color = type.colorIndex;
        int weapon = best(c -> DamageColor.multiplier(c, color), true);
        int armor = best(c -> DamageColor.multiplier(color, c), false);
        g.setForegroundColor(SkillStyle.getColorForMonster(type));
        g.putString(textX(), y++, fit(type.name + " is " + COLOR_NAMES.get(color), textWidth()));
        g.setForegroundColor(TextColor.ANSI.DEFAULT);
        g.putString(textX(), y++, fit(String.format(Locale.ROOT, "best weapon %s (x%s), armour %s (x%s)",
                COLOR_NAMES.get(weapon), times(DamageColor.multiplier(weapon, color)),
                COLOR_NAMES.get(armor), times(DamageColor.multiplier(color, armor))), textWidth()));
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        g.putString(textX(), y++, fit("roams " + type.roamRadius + " tiles, a step every " + type.moveTicks
                + (type.moveTicks == 1 ? " tick" : " ticks"), textWidth()));
        List<Product> drops = type.dropProducts();
        StringBuilder found = new StringBuilder(drops.isEmpty() ? "no drops found yet" : "drops:");
        for (int i = 0; i < drops.size(); i++) {
            Product drop = drops.get(i);
            found.append(i > 0 ? "," : "").append(' ').append(drop.name).append(' ')
                    .append(type.found(drop)).append('/').append(MonsterType.copiesOf(i));
        }
        g.putString(textX(), y++, fit(found.toString(), textWidth()));
        g.putString(textX(), y, fit("next drop: 1 in " + type.nextDropOneIn() + " kills", textWidth()));
    }

    /** Of the 8 colours, the one making {@code multiplier} biggest ({@code max}) or smallest. */
    private static int best(java.util.function.IntToDoubleFunction multiplier, boolean max) {
        int best = 0;
        for (int c = 1; c < COLOR_NAMES.size(); c++) {
            double m = multiplier.applyAsDouble(c), b = multiplier.applyAsDouble(best);
            if (max ? m > b : m < b) {
                best = c;
            }
        }
        return best;
    }

    private static String times(double multiplier) {
        return multiplier == Math.rint(multiplier) ? String.valueOf((long) multiplier)
                : String.format(Locale.ROOT, "%.2f", multiplier).replaceAll("0+$", "");
    }
}
