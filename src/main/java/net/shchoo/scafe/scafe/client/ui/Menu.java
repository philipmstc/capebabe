package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;

import com.googlecode.lanterna.input.KeyStroke;

/**
 * A top-level tab (Map, Skills, ...). Main switches between menus with Left/Right;
 * Down/Enter focuses the current one, after which it receives keys until it releases focus.
 */
public abstract class Menu extends Widget {

    public enum Result {
        HANDLED,
        IGNORED,
        RELEASE
    }

    private final String title;
    private boolean focused;

    protected Menu(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }

    public boolean isFocused() {
        return focused;
    }

    public void focus(Player player, Board board) {
        focused = canFocus(player, board);
        if (focused) {
            onFocus(player, board);
        }
    }

    public void unfocus() {
        focused = false;
    }

    protected boolean canFocus(Player player, Board board) {
        return true;
    }

    protected void onFocus(Player player, Board board) {}

    /** How a click takes the keys for this menu; the tab bar sets it (so it can select the tab, unfocus the rest). */
    private java.util.function.BiConsumer<Player, Board> claimer = this::focus;

    public void setClaimer(java.util.function.BiConsumer<Player, Board> claimer) {
        this.claimer = claimer;
    }

    /** Clicked on: this menu's tab is shown and focused (if it can be). */
    protected void claim(Player player, Board board) {
        claimer.accept(player, board);
    }

    /** Only called while focused. IGNORED keys fall through to the global commands (wasd, q, /...). */
    public abstract Result handleKey(KeyStroke key, Player player, Board board);
}
