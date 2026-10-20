package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.model.Product;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;

import java.util.ArrayList;
import java.util.List;

import static net.shchoo.scafe.scafe.client.ui.Layout.*;

/**
 * The tab headers (Inventory over the inventory, the rest over the map, Settings last) and the right-column box
 * holding the selected tab's content. Left/Right switch tabs, Down/Enter focuses the tab, "i" jumps into the
 * inventory, "," into the settings (and "," again back to where you were), and while a tab is focused it gets
 * keys first.
 */
public class TabBar extends Widget {
    private final InventoryPanel inventory;
    private final Menu settings;
    /** The inventory first, then the menus, then the settings. */
    private final List<Menu> tabs = new ArrayList<>();
    private int selection;
    /** Where "," came from, so a second "," goes back there. */
    private int beforeSettings;

    /** {@code bounds} = the box the selected tab draws in. */
    public TabBar(Rect bounds, InventoryPanel inventory, List<Menu> menus, Menu settings) {
        super(bounds);
        this.inventory = inventory;
        this.settings = settings;
        tabs.add(inventory);
        tabs.addAll(menus);
        tabs.add(settings);
        tabs.forEach(tab -> tab.setBounds(bounds));
        selection = menus.isEmpty() ? 0 : 1;
        beforeSettings = selection;
    }

    /** Whether you're in the settings (focused on them). */
    public boolean settingsOpen() {
        return settings.isFocused();
    }

    public Menu selected() {
        return tabs.get(selection);
    }

    @Override
    public void draw(Screen screen, Player player, Board board) {
        int x = INVENTORY.x() + 1;
        for (int i = 0; i < tabs.size(); i++) {
            TextGraphics header = screen.newTextGraphics();
            if (i == selection) {
                header.setForegroundColor(TextColor.ANSI.DEFAULT);
                header.setBackgroundColor(TextColor.ANSI.BLACK_BRIGHT);
                header.enableModifiers(SGR.BOLD);
            } else {
                header.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
                header.setBackgroundColor(TextColor.ANSI.DEFAULT);
            }
            String title = tabs.get(i).title();
            header.putString(x, HEADER_ROW, title);
            // the inventory's header sits over the inventory, the rest over the map
            x = i == 0 ? MAP.right() + 2 : x + title.length() + HEADER_GAP;
        }
        inventory.drawGrid(screen, player);
        drawFrame(screen);
        TextGraphics title = screen.newTextGraphics();
        title.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        title.putString(textX(), bounds().y(), selected().title());
        selected().draw(screen, player, board);
    }

    @Override
    public boolean onKey(KeyStroke key, Player player, Board board) {
        Menu menu = selected();
        if (key.getKeyType() == KeyType.Character && key.getCharacter() == ',') {
            menu.unfocus();
            if (menu == settings) {
                selection = beforeSettings;
            } else {
                beforeSettings = selection;
                selection = tabs.indexOf(settings);
                settings.focus(player, board);
            }
            return true;
        }
        if (menu.isFocused()) {
            Menu.Result result = menu.handleKey(key, player, board);
            if (result == Menu.Result.RELEASE) {
                menu.unfocus();
            }
            Product item = menu == inventory ? inventory.takeItemToUse() : null;
            if (item != null) {
                // picked an item to use: over to the map to aim it at a node
                tabs.stream().filter(MapMenu.class::isInstance).map(MapMenu.class::cast).findFirst().ifPresent(map -> {
                    selection = tabs.indexOf(map);
                    map.startUsing(item, player, board);
                });
            }
            if (result != Menu.Result.IGNORED) {
                return true;
            }
        }
        if (key.getKeyType() == KeyType.Character && key.getCharacter() == 'i') {
            menu.unfocus();
            selection = 0;
            inventory.focus(player, board);
            return true;
        }
        if (menu.isFocused()) {
            return false;
        }
        switch (key.getKeyType()) {
            case ArrowLeft -> selection = Math.max(0, selection - 1);
            case ArrowRight -> selection = Math.min(tabs.size() - 1, selection + 1);
            case ArrowDown, Enter -> menu.focus(player, board);
            default -> {
                return false;
            }
        }
        return true;
    }
}
