package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.protocol.Event;

import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.screen.Screen;

/** The action you're doing (green, filling up), or the monster you're fighting (red = its hp left). */
public class ProgressBarView extends Widget {

    public ProgressBarView(Rect bounds) {
        super(bounds);
    }

    @Override
    public void draw(Screen screen, Player player, Board board) {
        drawFrame(screen);
        Event.EntityInfo monster = player.fightingMonster();
        double fraction;
        String label;
        TextColor fill;
        if (monster != null) {
            fraction = monster.maxHp() == 0 ? 0 : (double) monster.hp() / monster.maxHp();
            label = monster.name() + " " + monster.hp() + "/" + monster.maxHp();
            fill = TextColor.ANSI.RED;
        } else if (player.currentAction != null) {
            fraction = player.actionFraction();
            label = player.currentAction.label();
            fill = TextColor.ANSI.GREEN;
        } else {
            return;
        }
        int left = bounds().x() + 1;
        int width = bounds().width() - 2;
        int y = firstRow();
        int filled = (int) Math.round(fraction * width);
        if (label.length() > width) {
            label = label.substring(0, width);
        }
        int labelStart = (width - label.length()) / 2;
        for (int i = 0; i < width; i++) {
            int l = i - labelStart;
            char c = l >= 0 && l < label.length() ? label.charAt(l) : ' ';
            TextColor bg = i < filled ? fill : TextColor.ANSI.DEFAULT;
            screen.setCharacter(left + i, y, TextCharacter.fromCharacter(c, TextColor.ANSI.WHITE_BRIGHT, bg)[0]);
        }
    }
}
