package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.Stacks;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static net.shchoo.scafe.scafe.client.ui.Layout.*;

/**
 * The bank's window, over the map while {@link Player#bankOpen} (q at a bank, opened on the spot by
 * {@link Player#perform}): your inventory on the left, the
 * bank on the right, both in stacks. Arrows move (across into the other side at the edge), Tab switches side,
 * Enter on a stack asks how many to move across (1, the stack, all, or a number you type), Esc closes. It takes
 * every key while open.
 */
public class BankView extends Widget {
    private static final int COLUMNS = 3;
    private static final int CELL_STEP_Y = 3;
    static final Rect BOX = new Rect(MAP.x() + 2, MAP.y() + 1, MAP.width() - 4, MAP.height() - 2);

    private enum Side { INVENTORY, BANK }

    /** {@code count} 0 = type a number, -1 = cancel. */
    record Option(String label, int count) {}

    private Side side = Side.INVENTORY;
    private final int[] selected = new int[2];
    private final int[] topRow = new int[2];
    private List<Option> options;
    private int optionIndex;
    /** The number being typed after "Amount...", else null. */
    private String typing;

    public BankView() {
        super(BOX);
    }

    private static int cellRows() {
        return (BOX.height() - 6) / CELL_STEP_Y;
    }

    private Map<Product, Integer> items(Player player, Side of) {
        return of == Side.INVENTORY ? player.inventory : player.bank;
    }

    private List<InventoryPanel.Slot> slots(Player player, Side of) {
        return InventoryPanel.slots(player, items(player, of));
    }

    public InventoryPanel.Slot selectedSlot(Player player) {
        List<InventoryPanel.Slot> slots = slots(player, side);
        return slots.isEmpty() ? null : slots.get(Math.min(selected[side.ordinal()], slots.size() - 1));
    }

    public boolean onBankSide() {
        return side == Side.BANK;
    }

    public boolean asking() {
        return options != null || typing != null;
    }

    @Override
    public boolean onKey(KeyStroke key, Player player, Board board) {
        if (!player.bankOpen) {
            return false;
        }
        if (typing != null) {
            typeKey(key, player);
        } else if (options != null) {
            optionKey(key, player);
        } else {
            gridKey(key, player);
        }
        return true;
    }

    private void gridKey(KeyStroke key, Player player) {
        int s = side.ordinal();
        int count = slots(player, side).size();
        switch (key.getKeyType()) {
            case ArrowLeft -> {
                if (selected[s] % COLUMNS == 0 && side == Side.BANK) {
                    switchTo(Side.INVENTORY, player, selected[s] + COLUMNS - 1);
                    return;
                }
                selected[s]--;
            }
            case ArrowRight -> {
                if (selected[s] % COLUMNS == COLUMNS - 1 || selected[s] >= count - 1) {
                    if (side == Side.INVENTORY) {
                        switchTo(Side.BANK, player, selected[s] - selected[s] % COLUMNS);
                    }
                    return;
                }
                selected[s]++;
            }
            case ArrowUp -> selected[s] -= selected[s] >= COLUMNS ? COLUMNS : 0;
            case ArrowDown -> selected[s] += selected[s] + COLUMNS < count ? COLUMNS : 0;
            case Tab, ReverseTab -> switchTo(side == Side.INVENTORY ? Side.BANK : Side.INVENTORY, player,
                    selected[1 - s]);
            case Enter -> openOptions(player);
            case Escape -> {
                player.bankOpen = false;
                side = Side.INVENTORY;
            }
            default -> {}
        }
        selected[s] = Math.max(0, Math.min(Math.max(0, count - 1), selected[s]));
    }

    private void switchTo(Side to, Player player, int index) {
        side = to;
        int count = slots(player, to).size();
        selected[to.ordinal()] = Math.max(0, Math.min(Math.max(0, count - 1), index));
    }

    private void openOptions(Player player) {
        InventoryPanel.Slot slot = selectedSlot(player);
        if (slot == null) {
            return;
        }
        int all = items(player, side).getOrDefault(slot.product(), 0);
        options = new ArrayList<>();
        options.add(new Option("1", 1));
        if (slot.count() > 1) {
            options.add(new Option("Stack (" + slot.count() + ")", slot.count()));
        }
        if (all > slot.count()) {
            options.add(new Option("All (" + all + ")", all));
        }
        options.add(new Option("Amount...", 0));
        options.add(new Option("Cancel", -1));
        optionIndex = 0;
    }

    private void optionKey(KeyStroke key, Player player) {
        switch (key.getKeyType()) {
            case ArrowUp -> optionIndex = Math.max(0, optionIndex - 1);
            case ArrowDown -> optionIndex = Math.min(options.size() - 1, optionIndex + 1);
            case Escape -> options = null;
            case Enter -> {
                Option option = options.get(optionIndex);
                options = null;
                if (option.count() == 0) {
                    typing = "";
                } else if (option.count() > 0) {
                    move(player, option.count());
                }
            }
            default -> {}
        }
    }

    private void typeKey(KeyStroke key, Player player) {
        switch (key.getKeyType()) {
            case Character -> {
                if (Character.isDigit(key.getCharacter()) && typing.length() < 9) {
                    typing += key.getCharacter();
                }
            }
            case Backspace -> typing = typing.isEmpty() ? typing : typing.substring(0, typing.length() - 1);
            case Escape -> typing = null;
            case Enter -> {
                int count = typing.isEmpty() ? 0 : Integer.parseInt(typing);
                typing = null;
                if (count > 0) {
                    move(player, count);
                }
            }
            default -> {}
        }
    }

    /** Across to the other side: the server checks you have it and (withdrawing) that it fits. */
    private void move(Player player, int count) {
        InventoryPanel.Slot slot = selectedSlot(player);
        if (slot == null) {
            return;
        }
        if (side == Side.INVENTORY) {
            player.deposit(slot.product(), count);
        } else {
            player.withdraw(slot.product(), count);
        }
    }

    @Override
    public void draw(Screen screen, Player player, Board board) {}

    @Override
    public void drawOnTop(Screen screen, Player player, Board board) {
        if (!player.bankOpen) {
            options = null;
            typing = null;
            return;
        }
        TextGraphics g = screen.newTextGraphics();
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        g.fillRectangle(new TerminalPosition(BOX.x(), BOX.y()), new TerminalSize(BOX.width(), BOX.height()), ' ');
        drawFrame(screen, BOX);
        g.setForegroundColor(TextColor.ANSI.YELLOW_BRIGHT);
        g.enableModifiers(SGR.BOLD);
        g.putString(BOX.x() + 2, BOX.y(), "Bank");
        g.disableModifiers(SGR.BOLD);

        int half = BOX.width() / 2;
        for (int y = BOX.y() + 1; y < BOX.bottom() - 1; y++) {
            g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            g.setCharacter(BOX.x() + half, y, '\u2502');
        }
        List<InventoryPanel.Slot> inventory = slots(player, Side.INVENTORY);
        drawSide(g, player, Side.INVENTORY, BOX.x() + 2, "Inventory " + inventory.size() + "/" + Stacks.INVENTORY_SLOTS);
        drawSide(g, player, Side.BANK, BOX.x() + half + 2, "Bank");
        if (player.bankBusy()) {
            g.setBackgroundColor(TextColor.ANSI.DEFAULT);
            g.setForegroundColor(TextColor.ANSI.YELLOW);
            g.putString(BOX.right() - 11, BOX.y(), " moving... ");
        }

        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        String hint = typing != null ? "[0-9] how many  [Enter] move  [Esc] back"
                : side == Side.INVENTORY ? "[arrows/Tab] pick  [Enter] deposit  [Esc] close"
                : "[arrows/Tab] pick  [Enter] withdraw  [Esc] close";
        g.putString(BOX.x() + 2, BOX.bottom() - 1, fit(hint, BOX.width() - 4));
        drawOptions(screen, g, player);
    }

    private int cellX(int left, int column) {
        return left + column * CELL_STEP_X;
    }

    private int cellY(int row) {
        return BOX.y() + 3 + row * CELL_STEP_Y;
    }

    private void drawSide(TextGraphics g, Player player, Side of, int left, String title) {
        int s = of.ordinal();
        boolean current = side == of;
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        g.setForegroundColor(current ? TextColor.ANSI.WHITE_BRIGHT : TextColor.ANSI.BLACK_BRIGHT);
        if (current) {
            g.enableModifiers(SGR.BOLD);
        }
        g.putString(left, BOX.y() + 1, title);
        g.disableModifiers(SGR.BOLD);

        List<InventoryPanel.Slot> slots = slots(player, of);
        selected[s] = Math.max(0, Math.min(selected[s], Math.max(0, slots.size() - 1)));
        int row = selected[s] / COLUMNS;
        if (row < topRow[s]) {
            topRow[s] = row;
        } else if (row >= topRow[s] + cellRows()) {
            topRow[s] = row - cellRows() + 1;
        }
        if (slots.isEmpty()) {
            g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            g.putString(left, cellY(0), of == Side.BANK ? "empty" : "nothing to deposit");
            return;
        }
        for (int r = 0; r < cellRows(); r++) {
            for (int c = 0; c < COLUMNS; c++) {
                int index = (topRow[s] + r) * COLUMNS + c;
                int x = cellX(left, c), y = cellY(r);
                boolean isSelected = current && index == selected[s];
                g.setBackgroundColor(isSelected ? TextColor.ANSI.BLACK_BRIGHT : TextColor.ANSI.DEFAULT);
                if (index < slots.size()) {
                    InventoryPanel.drawCell(g, x, y, slots.get(index), player, isSelected);
                } else {
                    InventoryPanel.drawEmptyCell(g, x, y);
                }
            }
        }
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        int totalRows = (slots.size() + COLUMNS - 1) / COLUMNS;
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        if (topRow[s] > 0) {
            g.putString(left + 3 * CELL_STEP_X - 1, cellY(0), "^");
        }
        if (topRow[s] + cellRows() < totalRows) {
            g.putString(left + 3 * CELL_STEP_X - 1, cellY(cellRows() - 1) + 1, "v");
        }
    }

    /** "How many?" next to the selected stack. */
    private void drawOptions(Screen screen, TextGraphics g, Player player) {
        if (options == null && typing == null) {
            return;
        }
        int s = side.ordinal();
        int left = side == Side.INVENTORY ? BOX.x() + 2 : BOX.x() + BOX.width() / 2 + 2;
        int x = cellX(left, selected[s] % COLUMNS) + CELL_WIDTH;
        int y = cellY(selected[s] / COLUMNS - topRow[s]);
        List<String> lines = new ArrayList<>();
        if (typing != null) {
            lines.add((side == Side.INVENTORY ? "Deposit " : "Withdraw ") + typing + "_");
        } else {
            options.forEach(o -> lines.add(o.label()));
        }
        int width = lines.stream().mapToInt(String::length).max().orElse(0) + 4;
        Rect box = new Rect(Math.min(x, BOX.right() - width - 1), y - 1, width + 2, lines.size() + 2);
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        g.fillRectangle(new TerminalPosition(box.x(), box.y()), new TerminalSize(box.width(), box.height()), ' ');
        drawFrame(screen, box);
        for (int i = 0; i < lines.size(); i++) {
            boolean isSelected = typing != null || i == optionIndex;
            boolean cancel = typing == null && options.get(i).count() < 0;
            g.setForegroundColor(cancel ? TextColor.ANSI.BLACK_BRIGHT : isSelected ? TextColor.ANSI.WHITE_BRIGHT : TextColor.ANSI.DEFAULT);
            if (isSelected) {
                g.enableModifiers(SGR.BOLD);
            }
            g.putString(box.x() + 1, box.y() + 1 + i, (isSelected && typing == null ? "> " : "  ") + lines.get(i));
            g.disableModifiers(SGR.BOLD);
        }
    }
}
