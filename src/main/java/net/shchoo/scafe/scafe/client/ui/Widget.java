package net.shchoo.scafe.scafe.client.ui;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.screen.Screen;
import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;

/**
 * A piece of the screen (map, tabs, xp tracker, command line, log...). Every frame the main loop draws each
 * widget in order, and offers each key to them in the same order until one uses it.
 */
public abstract class Widget {

    public abstract void draw(Screen screen, Player player, Board board);

    /** Returns true if this widget used the key, so later widgets don't see it. */
    public boolean onKey(KeyStroke key, Player player, Board board) {
        return false;
    }
}
