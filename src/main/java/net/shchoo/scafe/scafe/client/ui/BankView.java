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
 * {@link Player#perform}): your inventory where it always is (on the left, with the selection on it), and the
 * bank in a box opening out to its right over the map, with its own scrollbar; both in stacks, laid out like the
 * inventory. Arrows move (across into the other side at the edge), Tab switches side,
 * Enter on a stack asks how many to move across (1, the stack, all, or a number you type), Esc closes. It takes
 * every key while open.
 */
public class BankView extends Widget {
    /** The bank, opening out to the right of the inventory over the map: twice the inventory's width and a bit. */
    static final Rect BOX = new Rect(MAP.x(), INVENTORY.y(), 2 * (INVENTORY.width() + 3), MAP.height());
    /**
     * The bank's cells: one an item, however many (no stacks), so a column wider than the inventory's for counts
     * like "1.1k" beside the tier.
     */
    private static final int BANK_CELL_WIDTH = CELL_WIDTH + 1;
    private static final int BANK_CELL_STEP_X = BANK_CELL_WIDTH + 1;
    /** Across: the inventory's 3, and as many as the bank's box fits. */
    private static final int BANK_COLUMNS = (BOX.width() - 4) / BANK_CELL_STEP_X;

    private static int columns(Side of) {
        return of == Side.INVENTORY ? INVENTORY_COLUMNS : BANK_COLUMNS;
    }

    private static int cellWidth(Side of) {
        return of == Side.INVENTORY ? CELL_WIDTH : BANK_CELL_WIDTH;
    }

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

    /** Rows of cells either side shows: the inventory's. */
    private static int cellRows() {
        return INVENTORY_ROWS;
    }

    /** Where a side's grid starts: the inventory box, or the bank box. */
    private static Rect area(Side of) {
        return of == Side.INVENTORY ? INVENTORY : BOX;
    }

    private Map<Product, Integer> items(Player player, Side of) {
        return of == Side.INVENTORY ? player.inventory : player.bank;
    }

    /** The inventory in stacks, as ever; the bank one cell an item, all of it in one. */
    private List<InventoryPanel.Slot> slots(Player player, Side of) {
        if (of == Side.INVENTORY) {
            return InventoryPanel.slots(player, player.inventory);
        }
        return player.bank.entrySet().stream().filter(e -> e.getValue() > 0)
                .map(e -> new InventoryPanel.Slot(e.getKey(), e.getValue())).toList();
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
                if (selected[s] % columns(side) == 0 && side == Side.BANK) {
                    // across to the end of the same row of the inventory
                    switchTo(Side.INVENTORY, player, (selected[s] / BANK_COLUMNS) * INVENTORY_COLUMNS + INVENTORY_COLUMNS - 1);
                    return;
                }
                selected[s]--;
            }
            case ArrowRight -> {
                if (selected[s] % columns(side) == columns(side) - 1 || selected[s] >= count - 1) {
                    if (side == Side.INVENTORY) {
                        switchTo(Side.BANK, player, (selected[s] / INVENTORY_COLUMNS) * BANK_COLUMNS);
                    }
                    return;
                }
                selected[s]++;
            }
            case ArrowUp -> selected[s] -= selected[s] >= columns(side) ? columns(side) : 0;
            case ArrowDown -> selected[s] += selected[s] + columns(side) < count ? columns(side) : 0;
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
        // depositing: this stack; withdrawing: one inventory stack's worth
        int stack = side == Side.INVENTORY ? slot.count() : Math.min(all, player.stackSize(slot.product()));
        options = new ArrayList<>();
        options.add(new Option("1", 1));
        if (stack > 1) {
            options.add(new Option("Stack (" + stack + ")", stack));
        }
        if (all > stack) {
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
        for (Side of : Side.values()) {
            Rect area = area(of);
            g.fillRectangle(new TerminalPosition(area.x() + 1, area.y() + 1), new TerminalSize(area.width() - 2, area.height() - 2), ' ');
            drawFrame(screen, area);
            drawSide(screen, g, player, of);
        }
        g.setForegroundColor(TextColor.ANSI.YELLOW_BRIGHT);
        g.enableModifiers(SGR.BOLD);
        g.putString(BOX.x() + 2, BOX.y(), "Bank");
        g.disableModifiers(SGR.BOLD);
        if (player.bankBusy()) {
            g.setForegroundColor(TextColor.ANSI.YELLOW);
            g.putString(BOX.x() + 7, BOX.y(), " moving... ");
        }
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        g.putString(INVENTORY.x() + 2, INVENTORY.bottom(), slots(player, Side.INVENTORY).size() + "/" + Stacks.INVENTORY_SLOTS);
        g.putString(BOX.x() + 2, BOX.bottom(), slots(player, Side.BANK).size() + " items");

        String[] hint = typing != null ? new String[]{"[0-9] how many", "[Enter] move", "[Esc] back"}
                : new String[]{"[arrows/Tab] pick", side == Side.INVENTORY ? "[Enter] deposit" : "[Enter] withdraw", "[Esc] close"};
        for (int k = 0; k < hint.length; k++) {
            g.putString(BOX.x() + 2, BOX.bottom() - hint.length + k, fit(hint[k], BOX.width() - 4));
        }
        drawOptions(screen, g, player);
    }

    private static int cellX(Side of, int column) {
        return area(of).x() + 2 + column * (of == Side.INVENTORY ? CELL_STEP_X : BANK_CELL_STEP_X);
    }

    private static int cellY(Side of, int row) {
        return area(of).y() + 2 + row * Layout.CELL_STEP_Y;
    }

    private void drawSide(Screen screen, TextGraphics g, Player player, Side of) {
        int s = of.ordinal();
        boolean current = side == of;
        List<InventoryPanel.Slot> slots = slots(player, of);
        selected[s] = Math.max(0, Math.min(selected[s], Math.max(0, slots.size() - 1)));
        int columns = columns(of);
        int row = selected[s] / columns;
        if (row < topRow[s]) {
            topRow[s] = row;
        } else if (row >= topRow[s] + cellRows()) {
            topRow[s] = row - cellRows() + 1;
        }
        int totalRows = (slots.size() + columns - 1) / columns;
        topRow[s] = Math.max(0, Math.min(topRow[s], Math.max(0, totalRows - cellRows())));
        if (slots.isEmpty()) {
            g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            g.putString(cellX(of, 0), cellY(of, 0), of == Side.BANK ? "empty" : "nothing to deposit");
            return;
        }
        for (int r = 0; r < cellRows(); r++) {
            for (int c = 0; c < columns; c++) {
                int index = (topRow[s] + r) * columns + c;
                int x = cellX(of, c), y = cellY(of, r);
                boolean isSelected = current && index == selected[s];
                g.setBackgroundColor(isSelected ? TextColor.ANSI.BLACK_BRIGHT : TextColor.ANSI.DEFAULT);
                if (index < slots.size()) {
                    InventoryPanel.Slot slot = slots.get(index);
                    String count = of == Side.INVENTORY ? String.valueOf(slot.count()) : ShortNumbers.of(slot.count());
                    InventoryPanel.drawCell(g, x, y, slot.product(), count, cellWidth(of), player, isSelected);
                } else {
                    InventoryPanel.drawEmptyCell(g, x, y, cellWidth(of));
                }
            }
        }
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        Rect area = area(of);
        drawScrollbar(screen, area.right(), area.y(), area.bottom(), topRow[s], cellRows(), totalRows);
    }

    /** "How many?" next to the selected stack. */
    private void drawOptions(Screen screen, TextGraphics g, Player player) {
        if (options == null && typing == null) {
            return;
        }
        int s = side.ordinal();
        int x = cellX(side, selected[s] % columns(side)) + cellWidth(side);
        int y = cellY(side, selected[s] / columns(side) - topRow[s]);
        List<String> lines = new ArrayList<>();
        if (typing != null) {
            lines.add((side == Side.INVENTORY ? "Deposit " : "Withdraw ") + typing + "_");
        } else {
            options.forEach(o -> lines.add(o.label()));
        }
        int width = lines.stream().mapToInt(String::length).max().orElse(0) + 4;
        Rect box = new Rect(Math.min(x, TERMINAL_SIZE.getColumns() - width - 3), y - 1, width + 2, lines.size() + 2);
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
