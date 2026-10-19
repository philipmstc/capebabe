package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.SkillStyle;
import net.shchoo.scafe.scafe.model.Goal;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.screen.Screen;

import static net.shchoo.scafe.scafe.client.ui.Layout.*;

public class GoalsMenu extends Menu {

    public GoalsMenu() {
        super("Goals");
    }

    @Override
    protected boolean canFocus(Player player, Board board) {
        return false;
    }

    @Override
    public Result handleKey(KeyStroke key, Player player, Board board) {
        return Result.RELEASE;
    }

    @Override
    public void draw(Screen screen, Player player, Board board) {
        TextGraphics textGraphics = screen.newTextGraphics();
        textGraphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
        if (player.goals.isEmpty()) {
            textGraphics.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            textGraphics.putString(textX(), firstRow(), "No goals yet. Try (q) a locked node, then g.");
            return;
        }
        for (int i = 0; i < Math.min(player.goals.size(), rows()); i++) {
            Goal goal = player.goals.get(i);
            int y = firstRow() + i;
            boolean met = goal.isMet(player.getLevel(goal.skill()));
            textGraphics.setForegroundColor(SkillStyle.getColorForSkill(goal.skill()));
            textGraphics.setCharacter(textX(), y, SkillStyle.getSymbolForSkill(goal.skill()));
            textGraphics.setForegroundColor(met ? TextColor.ANSI.GREEN_BRIGHT : TextColor.ANSI.DEFAULT);
            textGraphics.putString(textX() + 2, y, fit((met ? "[x] " : "[ ] ") + goal.describe(), textWidth() - 2));
        }
    }
}
