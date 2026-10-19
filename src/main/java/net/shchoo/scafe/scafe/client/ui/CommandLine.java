package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.ConsoleAction;
import net.shchoo.scafe.scafe.client.GameLog;
import net.shchoo.scafe.scafe.client.Player;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;


/**
 * "/" starts typing a command (e.g. "/1 5"); while typing, it takes every key. It's first in line for keys, so
 * it can't draw on top of anything -- {@link MessageLogView} shows what's being typed.
 */
public class CommandLine extends Widget {
    private String typed = null;

    /** What's typed so far (without the "/"), or null when not typing a command. */
    public String typed() {
        return typed;
    }

    @Override
    public void draw(Screen screen, Player player, Board board) {}

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
                System.out.println(typed);
                typed = null;
            }
            case Escape -> typed = null;
            default -> {}
        }
        return true;
    }
}
