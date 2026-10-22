package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.SkillStyle;
import net.shchoo.scafe.scafe.model.Goal;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.screen.Screen;

import static net.shchoo.scafe.scafe.client.ui.Layout.*;

/** Up/Down pick a goal, Left/Right move its level between {@link Goal#LEVELS}. */
public class GoalsMenu extends Menu {
    private int index;

    public GoalsMenu() {
        super("Goals");
    }

    public int index() {
        return index;
    }

    @Override
    protected boolean canFocus(Player player, Board board) {
        return !player.goals.isEmpty();
    }

    @Override
    protected void onFocus(Player player, Board board) {
        index = Math.min(index, player.goals.size() - 1);
    }

    @Override
    public Result handleKey(KeyStroke key, Player player, Board board) {
        if (player.goals.isEmpty()) {
            return Result.RELEASE;
        }
        index = Math.min(index, player.goals.size() - 1);
        switch (key.getKeyType()) {
            case ArrowUp -> {
                if (index == 0) return Result.RELEASE;
                index--;
            }
            case ArrowDown -> index = Math.min(player.goals.size() - 1, index + 1);
            case ArrowRight -> player.stepGoalLevel(index, 1);
            case ArrowLeft -> player.stepGoalLevel(index, -1);
            case Escape -> {
                return Result.RELEASE;
            }
            default -> {
                return Result.IGNORED;
            }
        }
        return Result.HANDLED;
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
        int start = isFocused() ? Math.max(0, Math.min(index - rows() / 2, player.goals.size() - rows())) : 0;
        for (int i = start; i < Math.min(player.goals.size(), start + rows()); i++) {
            Goal goal = player.goals.get(i);
            int y = firstRow() + (i - start);
            boolean selected = isFocused() && i == index;
            boolean met = goal.isMet(player.getLevel(goal.skill()));
            if (selected) {
                textGraphics.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT);
                textGraphics.setCharacter(textX() - 1, y, '>');
                textGraphics.enableModifiers(SGR.BOLD);
            }
            textGraphics.setForegroundColor(SkillStyle.getColorForSkill(goal.skill()));
            textGraphics.setCharacter(textX(), y, SkillStyle.getSymbolForSkill(goal.skill()));
            textGraphics.setForegroundColor(met ? TextColor.ANSI.GREEN_BRIGHT : TextColor.ANSI.DEFAULT);
            String level = selected ? "< Lv. " + goal.level() + " >" : "Lv. " + goal.level();
            String text = (met ? "[x] " : "[ ] ") + goal.skill().name + " " + level + "  " + goal.product().name;
            textGraphics.putString(textX() + 2, y, fit(text, textWidth() - 2));
            textGraphics.disableModifiers(SGR.BOLD);
        }
        if (isFocused()) {
            textGraphics.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            textGraphics.putString(textX(), hintRow(), fit("Left/Right: goal level", textWidth()));
        }
    }
}
