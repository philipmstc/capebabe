package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.SkillStyle;
import net.shchoo.scafe.scafe.model.Building;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.Recipe;

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
 * The Merch Ant. Once, on your first prestige ({@link Player#merchantCalling}), a popup over the map: "The Merch Ant
 * wants to speak with you..." -- Walk to the Merch Ant / Cancel. From then on, q at its stall opens its window
 * ({@link Player#merchantMenuOpen}): your prestige items, then how many; each is traded for 1 + its prestige of the
 * plain item (skill0{1}-2+3: 4 x skill0{1}-2). Up/Down pick, Enter chooses, Esc goes back a step (closes from the
 * first); a click off it or w/a/s/d closes it, like the church's.
 */
public class MerchantView extends Widget {
    private static final int WIDTH = 50;
    static final String CALLING = "The Merch Ant wants to speak with you...";
    /** How many a trade can be, as long as you have more (and All). */
    static final int[] AMOUNTS = {1, 10, 100};

    public enum Step { ITEM, AMOUNT }

    private record Row(String label, TextColor color, Runnable action) {}

    private Step step = Step.ITEM;
    private int selected;
    private Product trading;
    private boolean open;
    private PopupMenu calling;

    public MerchantView() {
        super(new Rect(MAP.x() + (MAP.width() - WIDTH) / 2, MAP.y() + 3, WIDTH, MAP.height() - 6));
    }

    public Step step() {
        return step;
    }

    @Override
    public boolean onKey(KeyStroke key, Player player, Board board) {
        if (calling(player) != null) {
            calling.key(key);
            return true;
        }
        if (!opened(player)) {
            return false;
        }
        if (isMoveKey(key)) {
            // walking off closes it (the key goes on to move you)
            player.merchantMenuOpen = false;
            return false;
        }
        List<Row> rows = rows(player);
        switch (key.getKeyType()) {
            case ArrowUp -> selected = Math.max(0, selected - 1);
            case ArrowDown -> selected = Math.min(rows.size() - 1, selected + 1);
            case Escape, ArrowLeft -> {
                if (step == Step.AMOUNT) {
                    go(Step.ITEM);
                } else {
                    player.merchantMenuOpen = false;
                }
            }
            case Enter -> choose(rows, selected);
            default -> {}
        }
        return true;
    }

    /** The "wants to speak with you" popup while it's up (made the first time it's needed), else null. */
    private PopupMenu calling(Player player) {
        if (!player.merchantCalling) {
            calling = null;
            return null;
        }
        if (calling == null) {
            calling = new PopupMenu(CALLING, TextColor.ANSI.YELLOW_BRIGHT, List.of(
                    PopupMenu.Option.of("Walk to the Merch Ant", () -> walkThere(player))))
                    .onClose(() -> player.merchantCalling = false);
        }
        return calling;
    }

    private static void walkThere(Player player) {
        player.content.buildings().stream().filter(b -> b.kind() == Building.Kind.MERCHANT).findFirst()
                .ifPresent(player::useBuilding);
    }

    private boolean opened(Player player) {
        if (!player.merchantMenuOpen) {
            open = false;
            return false;
        }
        if (!open) {
            open = true;
            go(Step.ITEM);
        }
        return true;
    }

    private void go(Step to) {
        step = to;
        selected = 0;
    }

    private void choose(List<Row> rows, int index) {
        if (index >= 0 && index < rows.size() && rows.get(index).action() != null) {
            rows.get(index).action().run();
        }
    }

    private List<Row> rows(Player player) {
        List<Row> rows = new ArrayList<>();
        if (step == Step.ITEM) {
            player.inventory.forEach((item, count) -> {
                if (count > 0 && item.prestige > 0) {
                    // "skill0{0}-2+3 x1.2k -> 4 x skill0{0}-2": each one's worth
                    rows.add(new Row(item.name + " x" + ShortNumbers.of(count) + " -> " + (1 + item.prestige) + " x "
                            + item.root().name, colorOf(player, item), () -> {
                        trading = item;
                        go(Step.AMOUNT);
                    }));
                }
            });
        } else {
            int have = player.inventory.getOrDefault(trading, 0);
            for (int amount : AMOUNTS) {
                if (amount < have) {
                    rows.add(amountRow(player, amount, String.valueOf(amount)));
                }
            }
            if (have > 0) {
                rows.add(amountRow(player, have, "All (" + have + ")"));
            }
        }
        return rows;
    }

    private Row amountRow(Player player, int amount, String label) {
        return new Row(label + " -> " + amount * (1 + trading.prestige) + " x " + trading.root().name,
                TextColor.ANSI.DEFAULT, () -> {
            player.trade(trading, amount);
            go(Step.ITEM);
        });
    }

    private static TextColor colorOf(Player player, Product item) {
        Recipe madeBy = player.content.madeBy(item.root());
        return madeBy == null ? TextColor.ANSI.DEFAULT : SkillStyle.getColorForSkill(madeBy.skill);
    }

    @Override
    public void draw(Screen screen, Player player, Board board) {}

    @Override
    public void drawOnTop(Screen screen, Player player, Board board) {
        PopupMenu popup = calling(player);
        if (popup != null) {
            popup.draw(screen, MAP.x() + (MAP.width() - CALLING.length() - 4) / 2, MAP.y() + MAP.height() / 3, board.clicks);
            return;
        }
        if (!opened(player)) {
            return;
        }
        List<Row> rows = rows(player);
        selected = Math.max(0, Math.min(selected, rows.size() - 1));
        Rect box = bounds();
        // a click off it closes it and goes on to what's there (the map: walk off)
        board.clicks.addClickOff(Clicks.MODAL, () -> player.merchantMenuOpen = false);
        List<String> notes = rows.isEmpty() && step == Step.ITEM
                ? ChurchView.wrap("Bring the ant prestige items (+N): it gives 1 + N of the plain one for each.", box.width() - 4)
                : List.of();
        int height = Math.min(box.height(), rows.size() + notes.size() + 5);
        board.clicks.add(Clicks.MODAL, box.x(), box.y(), box.width(), height, (button, cx, cy) -> {});
        TextGraphics g = screen.newTextGraphics();
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        g.fillRectangle(new TerminalPosition(box.x(), box.y()), new TerminalSize(box.width(), height), ' ');
        drawFrame(screen, new Rect(box.x(), box.y(), box.width(), height));
        g.setForegroundColor(TextColor.ANSI.YELLOW_BRIGHT);
        g.enableModifiers(SGR.BOLD);
        g.putString(box.x() + 2, box.y() + 1, fit(step == Step.ITEM ? "Merch Ant: trade which prestige item?"
                : "Trade " + trading.name + ": how many?", box.width() - 4));
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
        int y = box.y() + 2 + Math.min(visible, rows.size());
        for (String note : notes) {
            g.putString(box.x() + 2, y++, fit(note, box.width() - 4));
        }
        g.putString(box.x() + 2, box.y() + height - 2, fit("[Up/Down] pick  [Enter] choose  [Esc] "
                + (step == Step.ITEM ? "close" : "back"), box.width() - 4));
    }
}
