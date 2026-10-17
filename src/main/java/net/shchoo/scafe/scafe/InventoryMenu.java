package net.shchoo.scafe.scafe;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.screen.Screen;

import static net.shchoo.scafe.scafe.Main.LIST_ITEM_POS_X;
import static net.shchoo.scafe.scafe.Main.LIST_ITEM_POS_Y;

public class InventoryMenu extends Menu {

    public InventoryMenu() {
        super("Inventory");
    }

    @Override
    protected boolean canFocus(Player player, Board board) {
        return false;
    }

    @Override
    public Result handleKey(KeyStroke key, Player player, Board board) {
        return Result.RELEASE;
    }

    @Override
    public void draw(Screen screen, Player player, Board board) {
        TextGraphics textGraphics = screen.newTextGraphics();
        textGraphics.setForegroundColor(TextColor.ANSI.DEFAULT);
        textGraphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
        int i = 0;
        for (Product product : player.inventory.keySet()) {
            textGraphics.putString(LIST_ITEM_POS_X, LIST_ITEM_POS_Y + ((i++ + 1) * 2),
                    product.name + " x " + player.inventory.get(product));
        }
    }
}
