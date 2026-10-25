package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.SkillStyle;
import net.shchoo.scafe.scafe.model.Prayer;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.ProductLine;
import net.shchoo.scafe.scafe.model.Recipe;
import net.shchoo.scafe.scafe.model.Skill;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.screen.Screen;

import java.util.ArrayList;
import java.util.List;

import static net.shchoo.scafe.scafe.client.ui.Layout.*;

/**
 * The church window, over the map while {@link Player#churchMenuOpen} (q at a church): Heal, or Pray for a node --
 * then which skill, which of its recipes (any tier, locked or not), and what to pay with ({@link Prayer}: 100 x the
 * tier of the tier below's item, or 1000 of any one item you carry). Up/Down pick, Enter chooses, Esc goes back a
 * step (closes from the first). It takes every key while open.
 * <pre>
 * Church
 * > Heal (3/10 hp)
 *   Pray for a node
 *
 * Blessed: skill0{1}0-3, 87 steps left
 * </pre>
 */
public class ChurchView extends Widget {
    private static final int WIDTH = 50;

    public enum Step { MAIN, SKILL, RECIPE, PAY }

    /** One row of the list: grey and unpickable without an action. */
    private record Row(String label, TextColor color, Runnable action) {}

    private Step step = Step.MAIN;
    private int selected;
    private Skill skill;
    private Recipe recipe;
    private boolean open;

    public ChurchView() {
        super(new Rect(MAP.x() + (MAP.width() - WIDTH) / 2, MAP.y() + 3, WIDTH, MAP.height() - 6));
    }

    public Step step() {
        return step;
    }

    @Override
    public boolean onKey(KeyStroke key, Player player, Board board) {
        if (!opened(player)) {
            return false;
        }
        List<Row> rows = rows(player);
        switch (key.getKeyType()) {
            case ArrowUp -> selected = Math.max(0, selected - 1);
            case ArrowDown -> selected = Math.min(rows.size() - 1, selected + 1);
            case Escape, ArrowLeft -> back(player);
            case Enter -> choose(rows, selected);
            default -> {}
        }
        return true;
    }

    /** Whether it's up; just opened, it starts on the first step. */
    private boolean opened(Player player) {
        if (!player.churchMenuOpen) {
            open = false;
            return false;
        }
        if (!open) {
            open = true;
            go(Step.MAIN);
        }
        return true;
    }

    private void go(Step to) {
        step = to;
        selected = 0;
    }

    private void back(Player player) {
        switch (step) {
            case MAIN -> player.churchMenuOpen = false;
            case SKILL -> go(Step.MAIN);
            case RECIPE -> go(Step.SKILL);
            case PAY -> go(Step.RECIPE);
        }
    }

    private void choose(List<Row> rows, int index) {
        if (index >= 0 && index < rows.size() && rows.get(index).action() != null) {
            rows.get(index).action().run();
        }
    }

    private String title() {
        return switch (step) {
            case MAIN -> "Church";
            case SKILL -> "Pray for a node: which skill?";
            case RECIPE -> skill.name + ": which node?";
            case PAY -> "Pray for " + recipe.product.name + ", paying:";
        };
    }

    private List<Row> rows(Player player) {
        List<Row> rows = new ArrayList<>();
        switch (step) {
            case MAIN -> {
                rows.add(new Row("Heal (" + player.hp + "/" + player.maxHp + " hp)", TextColor.ANSI.DEFAULT, () -> {
                    player.heal();
                    player.churchMenuOpen = false;
                }));
                rows.add(new Row("Pray for a node", TextColor.ANSI.DEFAULT, () -> go(Step.SKILL)));
            }
            case SKILL -> {
                for (Skill s : player.content.craftingSkills()) {
                    rows.add(new Row(s.name, SkillStyle.getColorForSkill(s), () -> {
                        skill = s;
                        go(Step.RECIPE);
                    }));
                }
            }
            case RECIPE -> {
                for (ProductLine line : skill.productLines) {
                    for (Recipe r : line.recipes) {
                        boolean locked = !r.levelMet(player.getLevel(skill));
                        TextColor color = SkillStyle.getColorForSkill(skill);
                        rows.add(new Row(String.format("%-16s Lv. %d", r.product.name, r.levelNeeded),
                                locked && color instanceof TextColor.ANSI ansi ? SkillStyle.dim(ansi) : color, () -> {
                            recipe = r;
                            go(Step.PAY);
                        }));
                    }
                }
            }
            case PAY -> {
                Product below = Prayer.tierBelow(recipe);
                if (below != null) {
                    rows.add(payment(player, below));
                }
                player.inventory.forEach((item, count) -> {
                    if (count >= Prayer.ANY_ITEM && !item.equals(below)) {
                        rows.add(payment(player, item));
                    }
                });
            }
        }
        return rows;
    }

    private Row payment(Player player, Product item) {
        int cost = Prayer.cost(recipe, item);
        int have = player.inventory.getOrDefault(item, 0);
        Recipe madeBy = player.content.madeBy(item);
        TextColor color = madeBy == null ? TextColor.ANSI.DEFAULT : SkillStyle.getColorForSkill(madeBy.skill);
        return new Row(cost + " x " + item.name + " (you have " + ShortNumbers.of(have) + ")", color, have < cost ? null : () -> {
            player.pray(recipe, item);
            player.churchMenuOpen = false;
        });
    }

    @Override
    public void draw(Screen screen, Player player, Board board) {}

    @Override
    public void drawOnTop(Screen screen, Player player, Board board) {
        if (!opened(player)) {
            return;
        }
        List<Row> rows = rows(player);
        selected = Math.max(0, Math.min(selected, rows.size() - 1));
        Rect box = bounds();
        board.clicks.addEverywhere(Clicks.MODAL, (button, cx, cy) -> {});
        List<String> notes = notes(player, rows);
        int height = Math.min(box.height(), rows.size() + notes.size() + 5);
        TextGraphics g = screen.newTextGraphics();
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        g.fillRectangle(new TerminalPosition(box.x(), box.y()), new TerminalSize(box.width(), height), ' ');
        drawFrame(screen, new Rect(box.x(), box.y(), box.width(), height));
        g.setForegroundColor(TextColor.ANSI.YELLOW_BRIGHT);
        g.enableModifiers(SGR.BOLD);
        g.putString(box.x() + 2, box.y() + 1, fit(title(), box.width() - 4));
        g.disableModifiers(SGR.BOLD);

        int visible = Math.max(1, height - 5 - notes.size());
        int first = Math.max(0, Math.min(selected - visible + 1, rows.size() - visible));
        for (int i = first; i < Math.min(rows.size(), first + visible); i++) {
            Row row = rows.get(i);
            int y = box.y() + 2 + (i - first);
            boolean isSelected = i == selected;
            g.setForegroundColor(row.action() == null ? TextColor.ANSI.BLACK_BRIGHT : row.color());
            if (isSelected) {
                g.enableModifiers(SGR.BOLD);
            }
            g.putString(box.x() + 2, y, fit((isSelected ? "> " : "  ") + row.label(), box.width() - 4));
            g.disableModifiers(SGR.BOLD);
            int pick = i;
            board.clicks.option(Clicks.MODAL, box.x() + 1, y, box.width() - 2, 1, (button, cx, cy) -> {
                selected = pick;
                choose(rows, pick);
            });
        }
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        int y = box.y() + 2 + Math.min(visible, rows.size()) + 1;
        for (String note : notes) {
            g.putString(box.x() + 2, y++, fit(note, box.width() - 4));
        }
        g.putString(box.x() + 2, box.y() + height - 2, fit("[Up/Down] pick  [Enter] choose  [Esc] "
                + (step == Step.MAIN ? "close" : "back"), box.width() - 4));
    }

    /** Under the list: your blessing, or what praying costs. */
    private List<String> notes(Player player, List<Row> rows) {
        List<String> notes = new ArrayList<>();
        if (step == Step.MAIN) {
            notes.add(player.blessing == null ? "No blessing."
                    : "Blessed: " + player.blessing.product.name + ", " + player.blessingLeft + " steps left");
            notes.add("Praying: next node near you is it, x2 as likely, " + Prayer.STEPS + " steps");
        } else if (step == Step.PAY && rows.stream().allMatch(r -> r.action() == null)) {
            Product below = Prayer.tierBelow(recipe);
            notes.add("You need " + (below == null ? "" : Prayer.cost(recipe, below) + " x " + below.name + ", or ")
                    + Prayer.ANY_ITEM + " of any one item.");
        }
        return notes;
    }
}
