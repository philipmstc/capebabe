package net.shchoo.scafe.scafe;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.screen.Screen;

import static net.shchoo.scafe.scafe.Main.*;

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
            textGraphics.putString(LIST_ITEM_POS_X, LIST_ITEM_POS_Y + 1, "No goals yet. Try (q) a locked node, then press g.");
            return;
        }
        for (int i = 0; i < Math.min(player.goals.size(), LIST_ROWS); i++) {
            Goal goal = player.goals.get(i);
            int y = LIST_ITEM_POS_Y + 1 + i;
            boolean met = goal.isMet(player);
            textGraphics.setForegroundColor(SkillStyle.getColorForSkill(goal.skill()));
            textGraphics.setCharacter(LIST_ITEM_POS_X, y, SkillStyle.getSymbolForSkill(goal.skill()));
            textGraphics.setForegroundColor(met ? TextColor.ANSI.GREEN_BRIGHT : TextColor.ANSI.DEFAULT);
            textGraphics.putString(LIST_ITEM_POS_X + 2, y, (met ? "[x] " : "[ ] ") + goal.describe());
        }
    }
}
