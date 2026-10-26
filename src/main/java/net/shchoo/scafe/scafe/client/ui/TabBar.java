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
 * The tab headers and the right-column box holding the selected tab's content. The map is the default: nothing
 * focused means you're just playing on the map (wasd). Hotkeys focus a tab from anywhere: "i" the inventory,
 * "1", "2", "3"... the tabs after it (Skills, Goals, Stats, Settings), "v" the map's hover cursor, "," the settings
 * (again: back where you were). Esc at a tab's top level goes back to the map. With nothing focused, Left/Right
 * still pick a tab and Down/Enter focus it. While a tab (or the map) is focused it gets keys first.
 */
public class TabBar extends Widget {
    private final InventoryPanel inventory;
    private final MapMenu map;
    private final Menu settings;
    /** The inventory first, then the menus, then the settings; the map isn't a tab. */
    private final List<Menu> tabs = new ArrayList<>();
    private int selection;
    /** Where "," came from, so a second "," goes back there. */
    private int beforeSettings;

    /** {@code bounds} = the box the selected tab (or the map, while hovering) draws in. */
    public TabBar(Rect bounds, InventoryPanel inventory, MapMenu map, List<Menu> menus, Menu settings) {
        super(bounds);
        this.inventory = inventory;
        this.map = map;
        this.settings = settings;
        tabs.add(inventory);
        tabs.addAll(menus);
        tabs.add(settings);
        tabs.forEach(tab -> tab.setBounds(bounds));
        map.setBounds(bounds);
        // a click on a tab's contents takes the keys for it, like its hotkey
        for (Menu tab : tabs) {
            // a click inside a tab: it takes the keys where it is (a hotkey or its header starts it over: pick)
            tab.setClaimer((player, board) -> {
                if (!tab.isFocused()) {
                    unfocusAll();
                    selection = tabs.indexOf(tab);
                    tab.takeFocus(player, board);
                }
            });
        }
        map.setClaimer((player, board) -> {
            unfocusAll();
            map.focus(player, board);
        });
        inventory.setItemUser((item, player, board) -> {
            unfocusAll();
            map.startUsing(item, player, board);
        });
        selection = menus.isEmpty() ? 0 : 1;
        beforeSettings = selection;
    }

    /** Whether you're in the settings (focused on them). */
    public boolean settingsOpen() {
        return settings.isFocused();
    }

    /** A tab (or the map's hover cursor) has the keys, so Esc is for backing out of it. */
    public boolean menuFocused() {
        return selected().isFocused() || map.isFocused();
    }

    public Menu selected() {
        return tabs.get(selection);
    }

    public MapMenu map() {
        return map;
    }

    /** The hotkey shown before a tab's title: "i", then "1", "2"... */
    private static String hotkey(int index) {
        return index == 0 ? "i" : String.valueOf(index);
    }

    @Override
    public void draw(Screen screen, Player player, Board board) {
        header(screen, INVENTORY.x() + 1, hotkey(0), inventory.title(), selection == 0 && !map.isFocused());
        board.clicks.option(Clicks.BASE, INVENTORY.x() + 1, HEADER_ROW, 2 + inventory.title().length(), 1,
                (button, cx, cy) -> pick(0, player, board));
        header(screen, MAP.x() + 1, "v", "Map", map.isFocused());
        board.clicks.option(Clicks.BASE, MAP.x() + 1, HEADER_ROW, 5, 1, (button, cx, cy) -> {
            unfocusAll();
            map.focus(player, board);
        });
        int x = MAP.right() + 2;
        for (int i = 1; i < tabs.size(); i++) {
            String title = tabs.get(i).title();
            header(screen, x, hotkey(i), title, i == selection && !map.isFocused());
            int tab = i;
            board.clicks.option(Clicks.BASE, x, HEADER_ROW, hotkey(i).length() + 1 + title.length(), 1,
                    (button, cx, cy) -> pick(tab, player, board));
            x += hotkey(i).length() + 1 + title.length() + HEADER_GAP;
        }
        inventory.drawGrid(screen, player);
        Clicks.Handler onItem = (button, cx, cy) -> {
            int index = inventory.indexAt(cx, cy, player);
            if (index < 0) {
                return;
            }
            pick(0, player, board);
            inventory.select(index);
            if (button == Clicks.Button.LEFT) {
                inventory.useSelected(player, board);
            } else {
                inventory.openPopup(player);
            }
        };
        board.clicks.add(Clicks.BASE, INVENTORY, onItem);
        inventory.addCells(board.clicks, player, onItem);
        drawFrame(screen);
        Menu shown = map.isFocused() ? map : selected();
        TextGraphics title = screen.newTextGraphics();
        title.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        title.putString(textX(), bounds().y(), shown.title());
        shown.draw(screen, player, board);
    }

    /** A tab clicked: shown and focused, like its hotkey. */
    private void pick(int index, Player player, Board board) {
        unfocusAll();
        selection = index;
        tabs.get(index).focus(player, board);
    }

    @Override
    public void drawOnTop(Screen screen, Player player, Board board) {
        inventory.drawOnTop(screen, player, board);
        map.drawOnTop(screen, player, board);
        if (selected() != inventory) {
            selected().drawOnTop(screen, player, board);
        }
    }

    private static void header(Screen screen, int x, String key, String title, boolean selected) {
        TextGraphics g = screen.newTextGraphics();
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        g.putString(x, HEADER_ROW, key);
        if (selected) {
            g.setForegroundColor(TextColor.ANSI.DEFAULT);
            g.setBackgroundColor(TextColor.ANSI.BLACK_BRIGHT);
            g.enableModifiers(SGR.BOLD);
        }
        g.putString(x + key.length() + 1, HEADER_ROW, title);
    }

    @Override
    public boolean onKey(KeyStroke key, Player player, Board board) {
        Menu menu = map.isFocused() ? map : selected();
        if (menu.isFocused()) {
            Menu.Result result = menu.handleKey(key, player, board);
            if (result == Menu.Result.RELEASE) {
                menu.unfocus();
            }
            Product item = menu == inventory ? inventory.takeItemToUse() : null;
            if (item != null) {
                // picked an item to use: over to the map to aim it at a node
                map.startUsing(item, player, board);
            }
            if (result != Menu.Result.IGNORED) {
                return true;
            }
        }
        if (key.getKeyType() == KeyType.Character && hotkeyPressed(key.getCharacter(), player, board)) {
            return true;
        }
        if (menu.isFocused()) {
            return false;
        }
        switch (key.getKeyType()) {
            case ArrowLeft -> selection = Math.max(0, selection - 1);
            case ArrowRight -> selection = Math.min(tabs.size() - 1, selection + 1);
            case ArrowDown, Enter -> selected().focus(player, board);
            default -> {
                return false;
            }
        }
        return true;
    }

    /** "i", "1".."n", "v" and ",": jump straight to a tab (or the map) and focus it. */
    private boolean hotkeyPressed(char c, Player player, Board board) {
        if (c == ',') {
            boolean inSettings = settings.isFocused();
            unfocusAll();
            if (inSettings) {
                selection = beforeSettings;
            } else {
                beforeSettings = selection;
                selection = tabs.indexOf(settings);
                settings.focus(player, board);
            }
            return true;
        }
        if (c == 'v') {
            unfocusAll();
            map.focus(player, board);
            return true;
        }
        int index = c == 'i' ? 0 : Character.isDigit(c) ? c - '0' : -1;
        if (index < 0 || index >= tabs.size()) {
            return false;
        }
        unfocusAll();
        selection = index;
        tabs.get(index).focus(player, board);
        return true;
    }

    private void unfocusAll() {
        tabs.forEach(Menu::unfocus);
        map.unfocus();
    }
}
