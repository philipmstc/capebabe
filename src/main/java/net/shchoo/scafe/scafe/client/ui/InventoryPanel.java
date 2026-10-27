package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.SkillStyle;
import net.shchoo.scafe.scafe.model.Stacks;
import net.shchoo.scafe.scafe.model.EquipSlot;
import net.shchoo.scafe.scafe.model.MonsterType;
import net.shchoo.scafe.scafe.model.Prayer;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.Recipe;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.screen.Screen;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.shchoo.scafe.scafe.client.ui.Layout.*;

/**
 * The inventory: always shown on the left as cells (3 across, 5 visible, scrolling), one stack per cell (as many
 * as your level in the skill making it, {@link Player#stackSize}). As
 * a tab (the leftmost header, or "i"), arrows move the selection like the map's hover cursor and the right column
 * shows the selected item; Enter opens a popup next to it: Equip weapon / armor / both (used up from every stack of it),
 * Use (on a node), Unequip (when it's equipped), Drop stack (asks first: the stack's gone for good), Cancel.
 */
public class InventoryPanel extends Menu {
    private static final Pattern SKILL_PRODUCT = Pattern.compile("skill(\\d+)");
    private static final Pattern MONSTER_DROP = Pattern.compile("monster(\\d+)-drop(\\d+)");

    /** One cell: up to a stack of one item. */
    public record Slot(Product product, int count) {}

    enum Option {
        WEAPON("Equip weapon"), ARMOR("Equip armor"), BOTH("Equip both"), USE("Use"), BLESS("Use blessing"),
        UNEQUIP("Unequip"), DROP("Drop stack"), DROP_FOR_GOOD("Drop it? It's gone for good"), CANCEL("Cancel");

        final String label;

        Option(String label) {
            this.label = label;
        }
    }

    /** Where the cells go (always on screen); the tab's own box, for item details, is set by the tab bar. */
    private final Rect grid;
    private int selected = 0;
    private int topRow = 0;
    /** Enter on an item: picked to be used on a node; the tab bar hands it to the map. */
    private Product toUse;

    /** Using an item: off to the map to aim it (the tab bar sets this). */
    public interface ItemUser {
        void use(Product item, Player player, Board board);
    }

    private ItemUser itemUser;

    public void setItemUser(ItemUser itemUser) {
        this.itemUser = itemUser;
    }
    /** The Enter popup's options while it's open, else null. */
    private List<Option> popup;
    private int popupIndex;
    /** Drop stack picked: the stack it's asking about. */
    private Slot dropping;

    public InventoryPanel(Rect grid) {
        super("Inventory");
        this.grid = grid;
    }

    /** Every item you hold in stacks of {@link Player#stackSize} (the last one of each may be short), in the order you got them. */
    public static List<Slot> slots(Player player) {
        return slots(player, player.inventory);
    }

    /** {@code items} (your inventory or bank) in stacks of {@link Player#stackSize}, in {@link Player#itemSort}'s order. */
    public static List<Slot> slots(Player player, Map<Product, Integer> items) {
        List<Slot> slots = new ArrayList<>();
        for (Map.Entry<Product, Integer> entry : player.sorted(items)) {
            int stack = player.stackSize(entry.getKey());
            for (int left = entry.getValue(); left > 0; left -= stack) {
                slots.add(new Slot(entry.getKey(), Math.min(stack, left)));
            }
        }
        return slots;
    }

    /** An item's short name (the equipment box): skill N's tier t -> "SN" / "Tt"; monsterN-dropK -> "MN-DK". */
    public static String[] label(Product product) {
        if (product.base != null) {
            String[] plain = label(product.base);
            return new String[]{plain[0] + "+" + product.prestige, plain[1]};
        }
        Matcher skill = SKILL_PRODUCT.matcher(product.lineName());
        if (product.hasTier() && skill.matches()) {
            return new String[]{"S" + skill.group(1), "T" + product.tier};
        }
        Matcher drop = MONSTER_DROP.matcher(product.name);
        if (drop.matches()) {
            return new String[]{"M" + drop.group(1) + "-D" + drop.group(2), ""};
        }
        String name = product.name;
        return new String[]{name.substring(0, Math.min(CELL_WIDTH, name.length())),
                name.length() > CELL_WIDTH ? name.substring(CELL_WIDTH, Math.min(2 * CELL_WIDTH, name.length())) : ""};
    }

    /** A cell's second line: the label's, with the stack's count on the right. */
    static String countLine(String labelLine, int count) {
        return countLine(labelLine, String.valueOf(count), CELL_WIDTH);
    }

    /** {@code width} wide: the label line on the left (cut if it has to be), the count on the right. */
    static String countLine(String labelLine, String number, int width) {
        int room = width - number.length();
        String left = labelLine.length() > room - 1 ? labelLine.substring(0, Math.max(0, room - 1)) : labelLine;
        return left + " ".repeat(Math.max(0, width - left.length() - number.length())) + number;
    }

    /** The item picked with Enter to use on a node (once; null if none). */
    public Product takeItemToUse() {
        Product item = toUse;
        toUse = null;
        return item;
    }

    /** The selected stack, or null if you have nothing. */
    public Slot selectedSlot(Player player) {
        List<Slot> slots = slots(player);
        return slots.isEmpty() ? null : slots.get(Math.min(selected, slots.size() - 1));
    }

    public Product selectedItem(Player player) {
        Slot slot = selectedSlot(player);
        return slot == null ? null : slot.product();
    }

    public int selectedIndex() {
        return selected;
    }

    public boolean popupOpen() {
        return popup != null;
    }

    @Override
    protected boolean canFocus(Player player, Board board) {
        return !slots(player).isEmpty();
    }

    @Override
    public void unfocus() {
        super.unfocus();
        popup = null;
    }

    @Override
    public Result handleKey(KeyStroke key, Player player, Board board) {
        if (popup != null) {
            return popupKey(key, player);
        }
        int count = slots(player).size();
        switch (key.getKeyType()) {
            case ArrowLeft -> selected--;
            case ArrowRight -> selected++;
            case ArrowUp -> {
                if (selected < INVENTORY_COLUMNS) {
                    return Result.RELEASE;
                }
                selected -= INVENTORY_COLUMNS;
            }
            case ArrowDown -> selected += INVENTORY_COLUMNS;
            case Enter -> {
                openPopup(player);
                return Result.HANDLED;
            }
            case Escape -> {
                return Result.RELEASE;
            }
            default -> {
                return Result.IGNORED;
            }
        }
        selected = Math.max(0, Math.min(count - 1, selected));
        return Result.HANDLED;
    }

    /** Enter / right-click on the selected item: Equip weapon / armor / both, Use, Unequip (if equipped), Drop stack, Cancel. */
    public void openPopup(Player player) {
        Slot slot = selectedSlot(player);
        if (slot == null) {
            return;
        }
        // a blessing: only to be used (it blesses you there and then)
        // a drop: a trophy, not to fight with
        popup = new ArrayList<>(slot.product().blesses != null ? List.of(Option.BLESS)
                : player.content.droppedBy(slot.product()) != null ? List.of(Option.USE)
                : List.of(Option.WEAPON, Option.ARMOR, Option.BOTH, Option.USE));
        if (slot.product().equals(player.weapon) || slot.product().equals(player.armor)) {
            popup.add(Option.UNEQUIP);
        }
        popup.add(Option.DROP);
        popup.add(Option.CANCEL);
        popupIndex = 0;
    }

    private Result popupKey(KeyStroke key, Player player) {
        switch (key.getKeyType()) {
            case ArrowUp -> popupIndex = Math.max(0, popupIndex - 1);
            case ArrowDown -> popupIndex = Math.min(popup.size() - 1, popupIndex + 1);
            case Escape -> popup = null;
            case Enter -> {
                return choose(player);
            }
            case Character -> {
                // global keys (wasd...) stay off while the popup is up
            }
            default -> {}
        }
        return Result.HANDLED;
    }

    /** Does the popup's selected option (Use: the item goes to {@link #takeItemToUse}). */
    private Result choose(Player player) {
        Option option = popup.get(popupIndex);
        popup = null;
        Slot slot = selectedSlot(player);
        switch (option) {
            case WEAPON -> player.equip(EquipSlot.WEAPON, slot.product());
            case ARMOR -> player.equip(EquipSlot.ARMOR, slot.product());
            case BOTH -> {
                player.equip(EquipSlot.WEAPON, slot.product());
                player.equip(EquipSlot.ARMOR, slot.product());
            }
            case USE -> {
                toUse = slot.product();
                return Result.RELEASE;
            }
            case BLESS -> player.useBlessing(slot.product());
            case UNEQUIP -> {
                for (EquipSlot equipSlot : EquipSlot.values()) {
                    if (slot.product().equals(player.equipped(equipSlot))) {
                        player.unequip(equipSlot);
                    }
                }
            }
            case DROP -> {
                // asks first, with Cancel picked
                dropping = slot;
                popup = new ArrayList<>(List.of(Option.DROP_FOR_GOOD, Option.CANCEL));
                popupIndex = 1;
            }
            case DROP_FOR_GOOD -> {
                if (dropping != null) {
                    player.discard(dropping.product(), dropping.count());
                }
                dropping = null;
            }
            case CANCEL -> dropping = null;
        }
        return Result.HANDLED;
    }

    /** The grid on the left; drawn every frame, whichever tab is selected. */
    public void drawGrid(Screen screen, Player player) {
        int left = grid.x(), top = grid.y(), right = grid.right(), bottom = grid.bottom();
        drawFrame(screen, grid);
        List<Slot> slots = slots(player);
        selected = Math.max(0, Math.min(selected, slots.size() - 1));
        int selectedRow = selected / INVENTORY_COLUMNS;
        if (selectedRow < topRow) {
            topRow = selectedRow;
        } else if (selectedRow >= topRow + INVENTORY_ROWS) {
            topRow = selectedRow - INVENTORY_ROWS + 1;
        }
        int totalRows = (slots.size() + INVENTORY_COLUMNS - 1) / INVENTORY_COLUMNS;
        topRow = Math.max(0, Math.min(topRow, Math.max(0, totalRows - INVENTORY_ROWS)));

        int[] xs = new int[INVENTORY_COLUMNS], ys = new int[INVENTORY_ROWS];
        Arrays.setAll(xs, this::cellX);
        Arrays.setAll(ys, this::cellY);
        drawCellLines(screen, grid, xs, ys);
        TextGraphics g = screen.newTextGraphics();
        for (int row = 0; row < INVENTORY_ROWS; row++) {
            for (int column = 0; column < INVENTORY_COLUMNS; column++) {
                int index = (topRow + row) * INVENTORY_COLUMNS + column;
                int x = cellX(column);
                int y = cellY(row);
                boolean isSelected = isFocused() && index == selected;
                TextColor bg = isSelected ? TextColor.ANSI.BLACK_BRIGHT : TextColor.ANSI.DEFAULT;
                g.setBackgroundColor(bg);
                if (index >= slots.size()) {
                    drawEmptyCell(g, x, y);
                    continue;
                }
                drawCell(g, x, y, slots.get(index), player, isSelected);
            }
        }
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        g.setForegroundColor(slots.size() >= player.inventorySlots() ? TextColor.ANSI.RED_BRIGHT : TextColor.ANSI.BLACK_BRIGHT);
        g.putString(left + 2, bottom, slots.size() + "/" + player.inventorySlots());

        drawScrollbar(screen, right, top, bottom, topRow, INVENTORY_ROWS, totalRows);
    }

    /** The slot index of the cell drawn at screen cell {@code x}, {@code y} (as last drawn), or -1. */
    public int indexAt(int x, int y, Player player) {
        int column = x - grid.x() - 2, row = y - grid.y() - 2;
        if (column < 0 || row < 0 || column % CELL_STEP_X >= CELL_WIDTH || row % CELL_STEP_Y >= 2) {
            return -1;
        }
        int c = column / CELL_STEP_X, r = row / CELL_STEP_Y;
        if (c >= INVENTORY_COLUMNS || r >= INVENTORY_ROWS) {
            return -1;
        }
        int index = (topRow + r) * INVENTORY_COLUMNS + c;
        return index < slots(player).size() ? index : -1;
    }

    /** Each item cell on screen as an option (so it lights up under the mouse), clicked with {@code handler}. */
    public void addCells(Clicks clicks, Player player, Clicks.Handler handler) {
        for (int r = 0; r < INVENTORY_ROWS; r++) {
            for (int c = 0; c < INVENTORY_COLUMNS; c++) {
                if ((topRow + r) * INVENTORY_COLUMNS + c < slots(player).size()) {
                    clicks.option(Clicks.BASE, cellX(c), cellY(r), CELL_WIDTH, 2, handler);
                }
            }
        }
    }

    /** The wheel: the selection a row up or down (the grid follows it). */
    public void scrollRows(int step, Player player) {
        int count = slots(player).size();
        if (count > 0) {
            select(Math.max(0, Math.min(count - 1, selected + step * INVENTORY_COLUMNS)));
        }
    }

    public void select(int index) {
        selected = index;
        popup = null;
    }

    /** Left-click: use the selected item (off to the map to aim it). */
    public void useSelected(Player player, Board board) {
        Product item = selectedItem(player);
        if (item != null && item.blesses != null) {
            player.useBlessing(item);
        } else if (item != null && itemUser != null) {
            itemUser.use(item, player, board);
        }
    }

    private int cellX(int column) {
        return grid.x() + 2 + column * CELL_STEP_X;
    }

    private int cellY(int row) {
        return grid.y() + 2 + row * CELL_STEP_Y;
    }

    /** The popup, anchored just right of the selected cell (over the map if it has to be). */
    @Override
    public void drawOnTop(Screen screen, Player player, Board board) {
        if (popup == null) {
            return;
        }
        int row = selected / INVENTORY_COLUMNS - topRow;
        int x = cellX(selected % INVENTORY_COLUMNS) + CELL_WIDTH;
        int y = cellY(Math.max(0, row));
        int width = popup.stream().mapToInt(o -> o.label.length()).max().orElse(0) + 4;
        Rect box = new Rect(x, y - 1, width + 2, popup.size() + 2);
        TextGraphics g = screen.newTextGraphics();
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        g.setForegroundColor(TextColor.ANSI.DEFAULT);
        g.fillRectangle(new com.googlecode.lanterna.TerminalPosition(box.x(), box.y()),
                new com.googlecode.lanterna.TerminalSize(box.width(), box.height()), ' ');
        drawFrame(screen, box);
        for (int i = 0; i < popup.size(); i++) {
            Option option = popup.get(i);
            boolean isSelected = i == popupIndex;
            g.setForegroundColor(option == Option.CANCEL ? TextColor.ANSI.BLACK_BRIGHT
                    : option == Option.DROP_FOR_GOOD ? TextColor.ANSI.RED_BRIGHT
                    : isSelected ? TextColor.ANSI.WHITE_BRIGHT : TextColor.ANSI.DEFAULT);
            if (isSelected) {
                g.enableModifiers(SGR.BOLD);
            }
            g.putString(box.x() + 1, box.y() + 1 + i, (isSelected ? "> " : "  ") + option.label);
            g.disableModifiers(SGR.BOLD);
        }
        board.clicks.addClickOff(Clicks.POPUP, () -> popup = null);
        board.clicks.add(Clicks.POPUP, box, (button, cx, cy) -> {});
        for (int i = 0; i < popup.size(); i++) {
            int chosen = i;
            board.clicks.option(Clicks.POPUP, box.x() + 1, box.y() + 1 + i, box.width() - 2, 1, (button, cx, cy) -> {
                popupIndex = chosen;
                if (choose(player) == Result.RELEASE) {
                    unfocus();
                }
                Product item = takeItemToUse();
                if (item != null && itemUser != null) {
                    itemUser.use(item, player, board);
                }
            });
        }
    }

    /** One cell: the label over the tier in the item's colour, the stack's count in white (background already set). */
    static void drawCell(TextGraphics g, int x, int y, Slot slot, Player player, boolean selected) {
        drawCell(g, x, y, slot.product(), ShortNumbers.of(slot.count()), CELL_WIDTH, player, selected);
    }

    /**
     * A cell {@code width} wide showing {@code count} (already formatted, e.g. "1.1k"): its skill's symbol with its
     * tier marks either side as the map draws them (a drop: a trophy mark "\u03a8" and its monster's), a prestige
     * version's "+N" in yellow ("+*" when it doesn't fit) or a blessing's white cross after, then the count on the
     * right.
     * <pre>
     * .&amp;: +2
     *   x123
     * </pre>
     */
    static void drawCell(TextGraphics g, int x, int y, Product product, String count, int width, Player player, boolean selected) {
        if (selected) {
            g.enableModifiers(SGR.BOLD);
        }
        g.putString(x, y, " ".repeat(width));
        drawSymbol(g, x, y, product, player);
        if (product.blesses != null) {
            // a blessing: a white cross after its recipe's symbol
            g.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT);
            g.setCharacter(x + 4, y, '\u253c');
        } else if (product.prestige > 0) {
            String plus = "+" + product.prestige;
            g.setForegroundColor(TextColor.ANSI.YELLOW_BRIGHT);
            g.putString(x + 4, y, x + 4 + plus.length() <= x + width ? plus : "+*");
        }
        String number = "x" + count;
        g.putString(x, y + 1, " ".repeat(Math.max(0, width - number.length())));
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        g.putString(x + width - number.length(), y + 1, "x");
        g.setForegroundColor(TextColor.ANSI.WHITE);
        g.putString(x + width - number.length() + 1, y + 1, count);
        g.disableModifiers(SGR.BOLD);
    }

    /**
     * Three cells from {@code x}: the item's skill's symbol, bright, with its tier marks either side as the map draws
     * them, dim ({@code .#:}); a drop: a trophy mark and its monster's symbol ({@code \u03a8M}).
     */
    static void drawSymbol(TextGraphics g, int x, int y, Product product, Player player) {
        Recipe recipe = product.blesses != null ? product.blesses : player.content.madeBy(product);
        MonsterType monster = recipe == null ? player.content.droppedBy(product) : null;
        TextColor bright = colorOf(product, player);
        TextColor dark = bright instanceof TextColor.ANSI ansi ? SkillStyle.dim(ansi) : bright;
        char symbol = recipe != null ? SkillStyle.getSymbolForSkill(recipe.skill) : monster != null ? monster.symbol : '?';
        Product tiered = product.blesses != null ? recipe.product : product.root();
        String marks = monster != null ? "\u03a8 " : tiered.hasTier() ? MapView.tierMarks(tiered.tier) : "  ";
        g.setForegroundColor(dark);
        g.setCharacter(x, y, marks.charAt(0));
        g.setCharacter(x + 2, y, marks.charAt(1));
        g.setForegroundColor(bright);
        g.setCharacter(x + 1, y, symbol);
    }

    /** An empty cell: blank (the lines round it show it's there). */
    static void drawEmptyCell(TextGraphics g, int x, int y) {
        drawEmptyCell(g, x, y, CELL_WIDTH);
    }

    static void drawEmptyCell(TextGraphics g, int x, int y, int width) {
        g.putString(x, y, " ".repeat(width));
        g.putString(x, y + 1, " ".repeat(width));
    }

    static final TextColor CELL_LINE_COLOR = TextColor.ANSI.BLACK_BRIGHT;

    /**
     * Dark grey lines between cells (two rows tall) inside {@code frame}, at columns {@code xs} and rows {@code ys}:
     * down the gap just left of each column but the first, and across just under each row -- the last one only if
     * it isn't against the frame -- joined to the frame and each other where they meet.
     * <pre>
     * \u250c\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u252c\u2500\u2500\u2500\u2500\u2500\u2500\u252c
     * \u2502       \u2502      \u2502
     * \u2502 .%: +2\u2502.#.   \u2502
     * \u2502    x12\u2502 x1.1k\u2502
     * \u251c\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u253c\u2500\u2500\u2500\u2500\u2500\u2500\u253c
     * </pre>
     */
    static void drawCellLines(Screen screen, Rect frame, int[] xs, int[] ys) {
        int end = Math.min(frame.bottom(), ys[ys.length - 1] + 2);
        java.util.Set<Integer> across = new java.util.HashSet<>();
        for (int y : ys) {
            if (y + 2 <= end) {
                across.add(y + 2);
            }
        }
        for (int y : across) {
            boolean frameRow = y == frame.bottom();
            for (int x = frame.x() + 1; x < frame.right() && !frameRow; x++) {
                put(screen, x, y, '\u2500');
            }
            if (!frameRow) {
                put(screen, frame.x(), y, '\u251c');
                put(screen, frame.right(), y, '\u2524');
            }
        }
        for (int c = 1; c < xs.length; c++) {
            int x = xs[c] - 1;
            put(screen, x, frame.y(), '\u252c');
            for (int y = frame.y() + 1; y < end; y++) {
                put(screen, x, y, across.contains(y) ? '\u253c' : '\u2502');
            }
            put(screen, x, end, '\u2534');
        }
    }

    private static void put(Screen screen, int x, int y, char c) {
        screen.setCharacter(x, y, TextCharacter.fromCharacter(c, CELL_LINE_COLOR, TextColor.ANSI.DEFAULT)[0]);
    }

    static TextColor colorOf(Product product, Player player) {
        Recipe recipe = product.blesses != null ? product.blesses : player.content.madeBy(product);
        if (recipe != null) {
            return SkillStyle.getColorForSkill(recipe.skill);
        }
        MonsterType monster = player.content.droppedBy(product);
        return monster != null ? SkillStyle.getColorForMonster(monster) : TextColor.ANSI.DEFAULT;
    }

    /** As the selected tab: details of the selected item in the right column. */
    @Override
    public void draw(Screen screen, Player player, Board board) {
        TextGraphics g = screen.newTextGraphics();
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        int y = firstRow();
        Product item = selectedItem(player);
        if (item == null) {
            g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            g.putString(textX(), y, "Nothing in your inventory yet.");
            return;
        }
        g.setForegroundColor(colorOf(item, player));
        g.enableModifiers(SGR.BOLD);
        g.putString(textX(), y++, fit(item.name, textWidth()));
        g.disableModifiers(SGR.BOLD);
        g.setForegroundColor(TextColor.ANSI.DEFAULT);
        g.putString(textX(), y++, fit("you have " + player.inventory.getOrDefault(item, 0)
                + ", stacks of " + player.stackSize(item), textWidth()));
        Recipe recipe = player.content.madeBy(item);
        MonsterType monster = player.content.droppedBy(item);
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        if (item.blesses != null) {
            g.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT);
            g.putString(textX(), y++, fit("a church blessing: use it (Enter / click) and for " + Prayer.STEPS, textWidth()));
            g.putString(textX(), y++, fit("steps nodes are likelier near you, the next", textWidth()));
            g.putString(textX(), y++, fit("one " + item.blesses.product.name + ".", textWidth()));
        } else if (recipe != null) {
            g.putString(textX(), y++, fit("tier " + item.tier + ", made with " + recipe.skill.name + " Lv. " + recipe.levelNeeded, textWidth()));
        } else if (monster != null) {
            String finder = monster.drops.stream().filter(d -> d.product().equals(item)).findFirst()
                    .map(MonsterType.Drop::foundBy).orElse(null);
            g.setForegroundColor(TextColor.ANSI.YELLOW_BRIGHT);
            int copies = MonsterType.copiesOf(monster.dropProducts().indexOf(item));
            g.putString(textX(), y++, fit((copies == 1 ? "one of a kind" : "one of " + copies) + ", from " + monster.name, textWidth()));
            if (finder != null) {
                g.putString(textX(), y++, fit("first found by " + finder, textWidth()));
            }
        }
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        if (item.prestige > 0) {
            g.setForegroundColor(TextColor.ANSI.YELLOW_BRIGHT);
            g.putString(textX(), y++, fit("prestige " + item.prestige + ": +" + Product.PRESTIGE_DAMAGE * item.prestige
                    + " damage", textWidth()));
            g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        }
        g.putString(textX(), y + 1, fit("as a weapon: hits for "
                + (Product.PRESTIGE_DAMAGE * item.prestige + Math.max(0, item.tier) + 1) + " (fists: 1), one used per swing",
                textWidth()));
        g.putString(textX(), y + 2, fit("as armor: its colour takes the hits, one used per hit at you", textWidth()));
        g.putString(textX(), hintRow(),
                isFocused() ? "[arrows] pick  [Enter] equip / use  [Esc] back" : "[Down] browse items");
    }
}
