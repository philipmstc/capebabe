package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.ConsoleAction;
import net.shchoo.scafe.scafe.client.Player;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;

/** Last in line for keys: single-key commands (wasd, q, g, ...) and Esc to quit. Draws nothing. */
public class KeyCommands extends Widget {
    private final Runnable quit;

    public KeyCommands(Runnable quit) {
        this.quit = quit;
    }

    @Override
    public void draw(Screen screen, Player player, Board board) {}

    @Override
    public boolean onKey(KeyStroke key, Player player, Board board) {
        if (key.getKeyType() == KeyType.Escape) {
            quit.run();
            return true;
        }
        if (key.getKeyType() == KeyType.Character) {
            ConsoleAction.submit(key.getCharacter().toString(), player, board);
            return true;
        }
        return false;
    }
}
