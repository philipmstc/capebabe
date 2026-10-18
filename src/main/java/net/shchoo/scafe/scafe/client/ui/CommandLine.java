package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.ConsoleAction;
import net.shchoo.scafe.scafe.client.GameLog;
import net.shchoo.scafe.scafe.client.Player;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;

import static net.shchoo.scafe.scafe.client.ui.Layout.COMMAND_POS_X;
import static net.shchoo.scafe.scafe.client.ui.Layout.COMMAND_POS_Y;

/** "/" starts typing a command (e.g. "/1 5"); while typing, it takes every key. */
public class CommandLine extends Widget {
    private String typed = null;

    @Override
    public void draw(Screen screen, Player player, Board board) {
        if (typed == null) {
            return;
        }
        TextGraphics graphics = screen.newTextGraphics();
        graphics.setForegroundColor(TextColor.ANSI.DEFAULT);
        graphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
        graphics.putString(COMMAND_POS_X, COMMAND_POS_Y, "/" + typed);
    }

    @Override
    public boolean onKey(KeyStroke key, Player player, Board board) {
        if (typed == null) {
            if (key.getKeyType() == KeyType.Character && key.getCharacter() == '/') {
                typed = "";
                return true;
            }
            return false;
        }
        switch (key.getKeyType()) {
            case Character -> typed = typed + key.getCharacter();
            case Backspace -> typed = typed.isEmpty() ? typed : typed.substring(0, typed.length() - 1);
            case Enter -> {
                GameLog.say("/" + typed);
                ConsoleAction.submit(typed, player, board);
                typed = null;
            }
            case Escape -> typed = null;
            default -> {}
        }
        return true;
    }
}
