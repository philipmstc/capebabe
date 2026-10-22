package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.SkillStyle;
import net.shchoo.scafe.scafe.model.EquipSlot;
import net.shchoo.scafe.scafe.model.Product;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.screen.Screen;

/**
 * Under the inventory: what you fight with and wear, and how many are left (every stack of it).
 * <pre>
 * Weapon [.:]S0-0 x123
 * Armor  [  ]S1-1 x321
 * </pre>
 */
public class EquipmentView extends Widget {
    private static final String[] TIER_MARKS = {"  ", " .", "..", ".:", "::"};

    public EquipmentView(Rect bounds) {
        super(bounds);
    }

    /** The two characters inside the tier brackets: tier 0 "  ", 1 " .", 2 "..", 3 ".:", 4 "::". */
    static String tierMark(Product product) {
        return product.hasTier() ? TIER_MARKS[Math.max(0, Math.min(TIER_MARKS.length - 1, product.tier))] : "  ";
    }

    @Override
    public void draw(Screen screen, Player player, Board board) {
        drawFrame(screen);
        row(screen.newTextGraphics(), firstRow(), "Weapon", player.weapon, "fists", player);
        row(screen.newTextGraphics(), firstRow() + 1, "Armor", player.armor, "none", player);
    }

    private void row(TextGraphics g, int y, String name, Product item, String nothing, Player player) {
        int x = textX();
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        g.setForegroundColor(TextColor.ANSI.YELLOW);
        g.putString(x, y, name);
        x += 7;
        if (item == null) {
            g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            g.putString(x, y, nothing);
            return;
        }
        TextColor bright = InventoryPanel.colorOf(item, player);
        TextColor dark = bright instanceof TextColor.ANSI ansi ? SkillStyle.dim(ansi) : bright;
        g.setForegroundColor(dark);
        g.putString(x, y, "[" + tierMark(item) + "]");
        x += 4;
        String label = InventoryPanel.label(item)[0];
        String count = String.valueOf(player.inventory.getOrDefault(item, 0));
        int room = bounds().right() - x - 2 - count.length();
        g.setForegroundColor(bright);
        g.putString(x, y, Layout.fit(label, Math.max(0, room)));
        x += Math.min(label.length(), Math.max(0, room)) + 1;
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        g.putString(x, y, "x");
        g.setForegroundColor(TextColor.ANSI.WHITE);
        g.putString(x + 1, y, count);
    }
}
