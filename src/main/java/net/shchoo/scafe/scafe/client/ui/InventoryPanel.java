package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.SkillStyle;
import net.shchoo.scafe.scafe.model.Stacks;
import net.shchoo.scafe.scafe.model.EquipSlot;
import net.shchoo.scafe.scafe.model.MonsterType;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.Recipe;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.screen.Screen;
import java.util.ArrayList;
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
 * Use (on a node), Unequip (when it's equipped), Cancel.
 */
public class InventoryPanel extends Menu {
    private static final Pattern SKILL_PRODUCT = Pattern.compile("skill(\\d+)\\{(\\d+)}");
    private static final Pattern MONSTER_DROP = Pattern.compile("monster(\\d+)-drop(\\d+)");

    /** One cell: up to a stack of one item. */
    public record Slot(Product product, int count) {}

    enum Option {
        WEAPON("Equip weapon"), ARMOR("Equip armor"), BOTH("Equip both"), USE("Use"), UNEQUIP("Unequip"), CANCEL("Cancel");

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
    /** The Enter popup's options while it's open, else null. */
    private List<Option> popup;
    private int popupIndex;

    public InventoryPanel(Rect grid) {
        super("Inventory");
        this.grid = grid;
    }

    /** Every item you hold in stacks of {@link Player#stackSize} (the last one of each may be short), in the order you got them. */
    public static List<Slot> slots(Player player) {
        return slots(player, player.inventory);
    }

    /** {@code items} (your inventory or bank) in stacks of {@link Player#stackSize}. */
    public static List<Slot> slots(Player player, Map<Product, Integer> items) {
        List<Slot> slots = new ArrayList<>();
        for (Map.Entry<Product, Integer> entry : items.entrySet()) {
            int stack = player.stackSize(entry.getKey());
            for (int left = entry.getValue(); left > 0; left -= stack) {
                slots.add(new Slot(entry.getKey(), Math.min(stack, left)));
            }
        }
        return slots;
    }

    /** The two lines in an item's cell: skill N's line P, tier t -> "SN-P" / "Tt"; monsterN-dropK -> "MN-DK". */
    public static String[] label(Product product) {
        if (product.base != null) {
            String[] plain = label(product.base);
            return new String[]{plain[0] + "+" + product.prestige, plain[1]};
        }
        Matcher skill = SKILL_PRODUCT.matcher(product.lineName());
        if (product.hasTier() && skill.matches()) {
            return new String[]{"S" + skill.group(1) + "-" + skill.group(2), "T" + product.tier};
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
                Slot slot = selectedSlot(player);
                if (slot != null) {
                    popup = new ArrayList<>(List.of(Option.WEAPON, Option.ARMOR, Option.BOTH, Option.USE));
                    if (slot.product().equals(player.weapon) || slot.product().equals(player.armor)) {
                        popup.add(Option.UNEQUIP);
                    }
                    popup.add(Option.CANCEL);
                    popupIndex = 0;
                }
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

    private Result popupKey(KeyStroke key, Player player) {
        switch (key.getKeyType()) {
            case ArrowUp -> popupIndex = Math.max(0, popupIndex - 1);
            case ArrowDown -> popupIndex = Math.min(popup.size() - 1, popupIndex + 1);
            case Escape -> popup = null;
            case Enter -> {
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
                    case UNEQUIP -> {
                        for (EquipSlot equipSlot : EquipSlot.values()) {
                            if (slot.product().equals(player.equipped(equipSlot))) {
                                player.unequip(equipSlot);
                            }
                        }
                    }
                    case CANCEL -> {}
                }
            }
            case Character -> {
                // global keys (wasd...) stay off while the popup is up
            }
            default -> {}
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
        g.setForegroundColor(slots.size() >= Stacks.INVENTORY_SLOTS ? TextColor.ANSI.RED_BRIGHT : TextColor.ANSI.BLACK_BRIGHT);
        g.putString(left + 2, bottom, slots.size() + "/" + Stacks.INVENTORY_SLOTS);

        drawScrollbar(screen, right, top, bottom, topRow, INVENTORY_ROWS, totalRows);
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
                    : isSelected ? TextColor.ANSI.WHITE_BRIGHT : TextColor.ANSI.DEFAULT);
            if (isSelected) {
                g.enableModifiers(SGR.BOLD);
            }
            g.putString(box.x() + 1, box.y() + 1 + i, (isSelected ? "> " : "  ") + option.label);
            g.disableModifiers(SGR.BOLD);
        }
    }

    /** One cell: the label over the tier in the item's colour, the stack's count in white (background already set). */
    static void drawCell(TextGraphics g, int x, int y, Slot slot, Player player, boolean selected) {
        drawCell(g, x, y, slot.product(), String.valueOf(slot.count()), CELL_WIDTH, player, selected);
    }

    /** A cell {@code width} wide showing {@code count} (already formatted, e.g. "1.1k"). */
    static void drawCell(TextGraphics g, int x, int y, Product product, String count, int width, Player player, boolean selected) {
        String[] lines = label(product);
        g.setForegroundColor(colorOf(product, player));
        if (selected) {
            g.enableModifiers(SGR.BOLD);
        }
        g.putString(x, y, String.format("%-" + width + "s", fit(lines[0], width)));
        String line = countLine(lines[1], count, width);
        int digits = count.length();
        g.putString(x, y + 1, line.substring(0, width - digits));
        g.setForegroundColor(TextColor.ANSI.WHITE);
        g.putString(x + width - digits, y + 1, line.substring(width - digits));
        g.disableModifiers(SGR.BOLD);
    }

    /** An empty cell. */
    static void drawEmptyCell(TextGraphics g, int x, int y) {
        drawEmptyCell(g, x, y, CELL_WIDTH);
    }

    static void drawEmptyCell(TextGraphics g, int x, int y, int width) {
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        g.putString(x, y, String.format("%-" + width + "s", " ."));
        g.putString(x, y + 1, " ".repeat(width));
    }

    static TextColor colorOf(Product product, Player player) {
        Recipe recipe = player.content.madeBy(product);
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
        if (recipe != null) {
            g.putString(textX(), y++, fit("tier " + item.tier + ", made with " + recipe.skill.name + " Lv. " + recipe.levelNeeded, textWidth()));
        } else if (monster != null) {
            String finder = monster.drops.stream().filter(d -> d.product().equals(item)).findFirst()
                    .map(MonsterType.Drop::foundBy).orElse(null);
            g.setForegroundColor(TextColor.ANSI.YELLOW_BRIGHT);
            g.putString(textX(), y++, fit("one of a kind, from " + monster.name, textWidth()));
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
