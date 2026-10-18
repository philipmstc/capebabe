package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;

import java.util.List;

import static net.shchoo.scafe.scafe.client.ui.Layout.*;

/**
 * The tab headers and whichever {@link Menu} is selected. Left/Right switch tabs, Down/Enter focuses the
 * menu, and while it's focused the menu gets keys first.
 */
public class TabBar extends Widget {
    private final List<Menu> menus;
    /** Opened with "," over the menu area (settings + controls); takes keys first while open. */
    private final Menu overlay;
    private boolean overlayOpen;
    private int selection = 0;
    /** Where a next header would go, for whoever wants the space to the right. */
    private int headersEnd = HEADER_POS_X;

    public TabBar(List<Menu> menus, Menu overlay) {
        this.menus = menus;
        this.overlay = overlay;
    }

    public boolean overlayOpen() {
        return overlayOpen;
    }

    public int headersEnd() {
        return headersEnd;
    }

    @Override
    public void draw(Screen screen, Player player, Board board) {
        int x = HEADER_POS_X;
        for (int i = 0; i < menus.size(); i++) {
            TextGraphics header = screen.newTextGraphics();
            if (i == selection) {
                header.setForegroundColor(TextColor.ANSI.DEFAULT);
                header.setBackgroundColor(TextColor.ANSI.BLACK_BRIGHT);
                header.enableModifiers(SGR.BOLD);
            } else {
                header.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
                header.setBackgroundColor(TextColor.ANSI.DEFAULT);
            }
            String title = menus.get(i).title();
            header.putString(x, HEADER_POS_Y, title);
            // the first header sits over the map, the rest over the menu area
            x = i == 0 ? LIST_ITEM_POS_X + 1 : x + title.length() + HEADER_GAP;
        }
        headersEnd = x;
        (overlayOpen ? overlay : menus.get(selection)).draw(screen, player, board);
    }

    @Override
    public boolean onKey(KeyStroke key, Player player, Board board) {
        if (key.getKeyType() == KeyType.Character && key.getCharacter() == ',') {
            overlayOpen = !overlayOpen;
            return true;
        }
        if (overlayOpen) {
            Menu.Result result = overlay.handleKey(key, player, board);
            if (result == Menu.Result.RELEASE) {
                overlayOpen = false;
            }
            return result != Menu.Result.IGNORED;
        }
        Menu menu = menus.get(selection);
        if (menu.isFocused()) {
            Menu.Result result = menu.handleKey(key, player, board);
            if (result == Menu.Result.RELEASE) {
                menu.unfocus();
            }
            return result != Menu.Result.IGNORED;
        }
        switch (key.getKeyType()) {
            case ArrowLeft -> selection = Math.max(0, selection - 1);
            case ArrowRight -> selection = Math.min(menus.size() - 1, selection + 1);
            case ArrowDown, Enter -> menu.focus(player, board);
            default -> {
                return false;
            }
        }
        return true;
    }
}
