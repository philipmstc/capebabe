package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.ConsoleAction;
import net.shchoo.scafe.scafe.client.GameLog;
import net.shchoo.scafe.scafe.client.Player;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;

/**
 * "/" starts typing in the chat (the bottom row of the log box); Enter says it to everyone in the world. A line
 * starting with "/" is a command instead ("/1 3": the old console). While typing, it takes every key. It's first in
 * line for keys, so it can't draw on top of anything -- {@link MessageLogView} shows what's being typed.
 */
public class CommandLine extends Widget {
    private static final int MAX_TYPED = 200;
    private String typed = null;

    /** What's typed so far, or null when not typing. */
    public String typed() {
        return typed;
    }

    /** As "/" does (clicking the log): start typing, unless already. */
    public void startTyping() {
        if (typed == null) {
            typed = "";
        }
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
            case Character -> {
                if (typed.length() < MAX_TYPED) {
                    typed = typed + key.getCharacter();
                }
            }
            case Backspace -> typed = typed.isEmpty() ? typed : typed.substring(0, typed.length() - 1);
            case Enter -> {
                String line = typed.strip();
                typed = null;
                if (line.startsWith("/")) {
                    GameLog.say(line);
                    ConsoleAction.submit(line.substring(1), player, board);
                } else if (!line.isEmpty()) {
                    player.say(line);
                }
            }
            case Escape -> typed = null;
            default -> {}
        }
        return true;
    }
}
