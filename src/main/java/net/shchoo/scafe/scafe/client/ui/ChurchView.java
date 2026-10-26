package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.SkillStyle;
import net.shchoo.scafe.scafe.model.Prayer;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.ProductLine;
import net.shchoo.scafe.scafe.model.Recipe;
import net.shchoo.scafe.scafe.model.Skill;
import net.shchoo.scafe.scafe.protocol.Event;

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
 * The church window, over the map while {@link Player#churchMenuOpen} (q at a church): Heal, Pray for a node --
 * then which skill, which of its recipes (any tier, locked or not), and what to pay with ({@link Prayer}: 100 x the
 * tier of the tier below's item, or 1000 of any one item you carry) -- or Tithe: give it any of an item (a tiny
 * roll for a new skill). Under the list, your blessing and the oracle's guess at the next new skill. Up/Down pick, Enter chooses, Esc goes back a
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

    public enum Step { MAIN, SKILL, RECIPE, PAY, TITHE, TITHE_AMOUNT }

    /** What a tithe can be, as long as you have that many (and All). */
    static final int[] TITHE_AMOUNTS = {1, 10, 100, 1000};

    /** One row of the list: grey and unpickable without an action. */
    private record Row(String label, TextColor color, Runnable action) {}

    private Step step = Step.MAIN;
    private int selected;
    private Skill skill;
    private Recipe recipe;
    private Product tithing;
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
        if (isMoveKey(key)) {
            // walking off closes it (the key goes on to move you)
            player.churchMenuOpen = false;
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
            case TITHE -> go(Step.MAIN);
            case TITHE_AMOUNT -> go(Step.TITHE);
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
            case TITHE -> "Tithe: give what?";
            case TITHE_AMOUNT -> "Tithe " + tithing.name + ": how many?";
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
                rows.add(new Row("Tithe", TextColor.ANSI.DEFAULT, player.inventory.values().stream().anyMatch(n -> n > 0)
                        ? () -> go(Step.TITHE) : null));
            }
            case TITHE -> player.inventory.forEach((item, count) -> {
                if (count > 0) {
                    rows.add(new Row(item.name + " (you have " + ShortNumbers.of(count) + ")", colorOf(player, item), () -> {
                        tithing = item;
                        go(Step.TITHE_AMOUNT);
                    }));
                }
            });
            case TITHE_AMOUNT -> {
                int have = player.inventory.getOrDefault(tithing, 0);
                for (int amount : TITHE_AMOUNTS) {
                    if (amount < have) {
                        rows.add(new Row(String.valueOf(amount), TextColor.ANSI.DEFAULT, () -> tithe(player, amount)));
                    }
                }
                rows.add(new Row("All (" + have + ")", TextColor.ANSI.DEFAULT, have > 0 ? () -> tithe(player, have) : null));
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
                // the tier below (always offered, plain first) and its prestige versions you have, then a thousand of anything
                Product below = Prayer.tierBelow(recipe);
                if (below != null) {
                    rows.add(payment(player, below));
                    player.inventory.forEach((item, count) -> {
                        if (count > 0 && item.prestige > 0 && Prayer.isTierBelow(recipe, item)) {
                            rows.add(payment(player, item));
                        }
                    });
                }
                player.inventory.forEach((item, count) -> {
                    if (count >= Prayer.ANY_ITEM && !Prayer.isTierBelow(recipe, item)) {
                        rows.add(payment(player, item));
                    }
                });
            }
        }
        return rows;
    }

    /** Gives it, and back to the first page (where the oracle's new guess shows). */
    private void tithe(Player player, int count) {
        player.tithe(tithing, count);
        go(Step.MAIN);
    }

    private static TextColor colorOf(Player player, Product item) {
        Recipe madeBy = player.content.madeBy(item.root());
        return madeBy == null ? TextColor.ANSI.DEFAULT : SkillStyle.getColorForSkill(madeBy.skill);
    }

    private Row payment(Player player, Product item) {
        int cost = Prayer.cost(recipe, item);
        int have = player.inventory.getOrDefault(item, 0);
        TextColor color = colorOf(player, item);
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
        // a click off it closes it and goes on to what's there (the map: walk off)
        board.clicks.addClickOff(Clicks.MODAL, () -> player.churchMenuOpen = false);
        List<String> notes = notes(player, rows);
        // title, rows, a gap, the notes, a gap, the hint
        int height = Math.min(box.height(), rows.size() + notes.size() + (notes.isEmpty() ? 5 : 6));
        board.clicks.add(Clicks.MODAL, box.x(), box.y(), box.width(), height, (button, cx, cy) -> {});
        TextGraphics g = screen.newTextGraphics();
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        g.fillRectangle(new TerminalPosition(box.x(), box.y()), new TerminalSize(box.width(), height), ' ');
        drawFrame(screen, new Rect(box.x(), box.y(), box.width(), height));
        g.setForegroundColor(TextColor.ANSI.YELLOW_BRIGHT);
        g.enableModifiers(SGR.BOLD);
        g.putString(box.x() + 2, box.y() + 1, fit(title(), box.width() - 4));
        g.disableModifiers(SGR.BOLD);

        int visible = Math.max(1, height - (notes.isEmpty() ? 5 : 6) - notes.size());
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
            notes.addAll(wrap("Praying: the next node near you is it, twice as likely, for " + Prayer.STEPS + " steps",
                    bounds().width() - 4));
            notes.add("");
            Event.OracleSaid said = player.oracle();
            notes.addAll(wrap(said == null ? oracle(false, 0, 0) : oracle(said.known(), said.minutes(), said.fold()),
                    bounds().width() - 4));
        } else if (step == Step.PAY && rows.stream().allMatch(r -> r.action() == null)) {
            Product below = Prayer.tierBelow(recipe);
            notes.add("You need " + (below == null ? "" : Prayer.cost(recipe, below) + " x " + below.name + ", or ")
                    + Prayer.ANY_ITEM + " of any one item.");
        }
        return notes;
    }

    /**
     * The church's flavour line about the next new skill: due in so long, or (late) how long ago it should have come,
     * or -- twice or more over what it should have taken -- how many times over.
     */
    public static String oracle(boolean known, long minutes, int fold) {
        if (!known) {
            return "Our oracles are silent on when a new craft will dawn.";
        }
        if (fold >= 2) {
            return "The prophecy should have been fulfilled " + fold(fold) + " by now... the new craft is "
                    + duration(-minutes) + " late.";
        }
        if (minutes <= 0) {
            return "The prophecy foretold that the new craft should have arrived " + duration(-minutes) + " ago...";
        }
        return "Our oracles suggest that the dawn of a new craft is due in: " + duration(minutes);
    }

    private static final String[] FOLDS = {"", "", "twofold", "threefold", "fourfold", "fivefold", "sixfold",
            "sevenfold", "eightfold", "ninefold", "tenfold"};

    /** 2 "twofold" ... 10 "tenfold", then "11-fold". */
    static String fold(int n) {
        return n < FOLDS.length ? FOLDS[n] : n + "-fold";
    }

    /** "03 days 02 hours 05 minutes". */
    static String duration(long minutes) {
        long m = Math.max(0, minutes);
        return String.format(java.util.Locale.ROOT, "%02d days %02d hours %02d minutes", m / (24 * 60), m / 60 % 24, m % 60);
    }

    /** Word-wraps {@code text} to lines of at most {@code width}. */
    static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            line.append(line.length() > 0 ? " " : "").append(word);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }
}
