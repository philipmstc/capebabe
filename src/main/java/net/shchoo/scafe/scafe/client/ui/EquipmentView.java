package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.model.EquipSlot;
import net.shchoo.scafe.scafe.model.Product;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.screen.Screen;

/**
 * Under the inventory: what you fight with and wear, and how many are left (every stack of it).
 * <pre>
 * Weapon .#: +2 x123
 * Armor   %  x321
 * </pre>
 * Clicking a piece takes it off.
 */
public class EquipmentView extends Widget {
    public EquipmentView(Rect bounds) {
        super(bounds);
    }

    @Override
    public void draw(Screen screen, Player player, Board board) {
        drawFrame(screen);
        row(screen.newTextGraphics(), firstRow(), "Weapon", player.weapon, "fists", player);
        row(screen.newTextGraphics(), firstRow() + 1, "Armor", player.armor, "none", player);
        if (player.weapon != null) {
            board.clicks.option(Clicks.BASE, textX(), firstRow(), textWidth(), 1, (button, cx, cy) -> player.unequip(EquipSlot.WEAPON));
        }
        if (player.armor != null) {
            board.clicks.option(Clicks.BASE, textX(), firstRow() + 1, textWidth(), 1, (button, cx, cy) -> player.unequip(EquipSlot.ARMOR));
        }
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
        InventoryPanel.drawSymbol(g, x, y, item, player);
        x += 3;
        String count = ShortNumbers.of(player.inventory.getOrDefault(item, 0));
        String plus = item.prestige > 0 ? " +" + item.prestige : "";
        // " +P x123", dropping the space before the x if it doesn't fit
        boolean gap = x + plus.length() + 2 + count.length() <= textX() + textWidth();
        g.setForegroundColor(TextColor.ANSI.YELLOW_BRIGHT);
        g.putString(x, y, plus);
        x += plus.length() + (gap ? 1 : 0);
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        g.putString(x, y, "x");
        g.setForegroundColor(TextColor.ANSI.WHITE);
        g.putString(x + 1, y, count);
    }
}
