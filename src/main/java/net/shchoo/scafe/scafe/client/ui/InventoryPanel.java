package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.SkillStyle;
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
 * The inventory: always shown on the left as cells (2 across, 5 visible, scrolling), one item per cell. As a tab
 * (the leftmost header, or "i"), arrows move the selection like the map's hover cursor and the right column
 * shows the selected item.
 */
public class InventoryPanel extends Menu {
    private static final Pattern SKILL_PRODUCT = Pattern.compile("skill(\\d+)\\{(\\d+)}-(\\d+)");
    private static final Pattern MONSTER_DROP = Pattern.compile("monster(\\d+)-drop(\\d+)");

    /** Where the cells go (always on screen); the tab's own box, for item details, is set by the tab bar. */
    private final Rect grid;
    private int selected = 0;
    private int topRow = 0;

    public InventoryPanel(Rect grid) {
        super("Inventory");
        this.grid = grid;
    }

    /** Every item you hold, one per slot (3 of something = 3 slots), in the order you got them. */
    public static List<Product> slots(Player player) {
        List<Product> slots = new ArrayList<>();
        for (Map.Entry<Product, Integer> entry : player.inventory.entrySet()) {
            for (int i = 0; i < entry.getValue(); i++) {
                slots.add(entry.getKey());
            }
        }
        return slots;
    }

    /** The two lines in an item's cell: skillN{P}-t -> "SN-P" / "  Tt", monsterN-dropK -> "MN-DK". */
    public static String[] label(Product product) {
        Matcher skill = SKILL_PRODUCT.matcher(product.name);
        if (skill.matches()) {
            return new String[]{"S" + skill.group(1) + "-" + skill.group(2), "  T" + skill.group(3)};
        }
        Matcher drop = MONSTER_DROP.matcher(product.name);
        if (drop.matches()) {
            return new String[]{"M" + drop.group(1) + "-D" + drop.group(2), ""};
        }
        String name = product.name;
        return new String[]{name.substring(0, Math.min(CELL_WIDTH, name.length())),
                name.length() > CELL_WIDTH ? name.substring(CELL_WIDTH, Math.min(2 * CELL_WIDTH, name.length())) : ""};
    }

    /** The selected item, or null if you have nothing. */
    public Product selectedItem(Player player) {
        List<Product> slots = slots(player);
        return slots.isEmpty() ? null : slots.get(Math.min(selected, slots.size() - 1));
    }

    public int selectedIndex() {
        return selected;
    }

    @Override
    protected boolean canFocus(Player player, Board board) {
        return !slots(player).isEmpty();
    }

    @Override
    public Result handleKey(KeyStroke key, Player player, Board board) {
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

    /** The grid on the left; drawn every frame, whichever tab is selected. */
    public void drawGrid(Screen screen, Player player) {
        int left = grid.x(), top = grid.y(), right = grid.right(), bottom = grid.bottom();
        drawFrame(screen, grid);
        List<Product> slots = slots(player);
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
                int x = left + 2 + column * CELL_STEP_X;
                int y = top + 2 + row * CELL_STEP_Y;
                boolean isSelected = isFocused() && index == selected;
                TextColor bg = isSelected ? TextColor.ANSI.BLACK_BRIGHT : TextColor.ANSI.DEFAULT;
                g.setBackgroundColor(bg);
                if (index >= slots.size()) {
                    g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
                    g.putString(x, y, " .   ");
                    g.putString(x, y + 1, "     ");
                    continue;
                }
                String[] lines = label(slots.get(index));
                g.setForegroundColor(colorOf(slots.get(index), player));
                if (isSelected) {
                    g.enableModifiers(SGR.BOLD);
                }
                g.putString(x, y, pad(lines[0]));
                g.putString(x, y + 1, pad(lines[1]));
                g.disableModifiers(SGR.BOLD);
            }
        }
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        g.putString(left + 2, bottom, String.valueOf(slots.size()));

        drawScrollbar(screen, right, top, bottom, topRow, INVENTORY_ROWS, totalRows);
    }

    private static String pad(String text) {
        return String.format("%-" + CELL_WIDTH + "s", text.length() > CELL_WIDTH ? text.substring(0, CELL_WIDTH) : text);
    }

    private static TextColor colorOf(Product product, Player player) {
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
        g.putString(textX(), y++, "you have " + player.inventory.getOrDefault(item, 0));
        Recipe recipe = player.content.madeBy(item);
        MonsterType monster = player.content.droppedBy(item);
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        if (recipe != null) {
            g.putString(textX(), y++, fit("made with " + recipe.skill.name + " Lv. " + recipe.levelNeeded, textWidth()));
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
        g.putString(textX(), hintRow(),
                isFocused() ? "[arrows] pick  [Esc] back" : "[Down] browse items");
    }
}
