package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.SkillStyle;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.ProductLine;
import net.shchoo.scafe.scafe.model.Recipe;
import net.shchoo.scafe.scafe.model.Skill;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.Symbols;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.screen.Screen;

import static net.shchoo.scafe.scafe.client.ui.Layout.*;

public class SkillsMenu extends Menu {
    private static final int LIST_WIDTH = 32;
    private static final int DETAIL_POS_X = LIST_ITEM_POS_X + LIST_WIDTH + 2;
    private static final int PRODUCT_LINE_HEIGHT = 3;

    private int index = 0;
    private boolean detailFocused = false;
    private int detailIndex = 0;

    public SkillsMenu() {
        super("Skills");
    }

    @Override
    protected boolean canFocus(Player player, Board board) {
        return !board.skills.isEmpty();
    }

    @Override
    protected void onFocus(Player player, Board board) {
        index = 0;
        detailFocused = false;
    }

    @Override
    public Result handleKey(KeyStroke key, Player player, Board board) {
        Skill skill = board.skills.get(index);
        if (detailFocused) {
            switch (key.getKeyType()) {
                case ArrowUp -> detailIndex = Math.max(0, detailIndex - 1);
                case ArrowDown -> detailIndex = Math.max(0, Math.min(skill.productLines.size() - 1, detailIndex + 1));
                case ArrowLeft, Escape -> detailFocused = false;
                default -> {
                    return Result.IGNORED;
                }
            }
            return Result.HANDLED;
        }
        switch (key.getKeyType()) {
            case ArrowUp -> {
                if (index == 0) return Result.RELEASE;
                index--;
            }
            case ArrowDown -> index = Math.min(board.skills.size() - 1, index + 1);
            case ArrowRight, Enter -> {
                if (!skill.productLines.isEmpty()) {
                    detailFocused = true;
                    detailIndex = 0;
                }
            }
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
        if (board.skills.isEmpty()) {
            return;
        }
        drawList(screen, player, board);
        if (isFocused()) {
            TextGraphics separator = screen.newTextGraphics();
            separator.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            separator.drawLine(new TerminalPosition(DETAIL_POS_X - 2, LIST_ITEM_POS_Y + 1),
                    new TerminalPosition(DETAIL_POS_X - 2, LIST_ITEM_POS_Y + LIST_ROWS),
                    Symbols.SINGLE_LINE_VERTICAL);
            drawProductLines(screen, player, board.skills.get(index));
        }
    }

    private void drawList(Screen screen, Player player, Board board) {
        TextGraphics textGraphics = screen.newTextGraphics();
        textGraphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
        int start = isFocused() ? Math.max(0, Math.min(index - LIST_ROWS / 2, board.skills.size() - LIST_ROWS)) : 0;
        for (int i = start; i < Math.min(board.skills.size(), start + LIST_ROWS); i++) {
            Skill skill = board.skills.get(i);
            int y = LIST_ITEM_POS_Y + 1 + (i - start);
            boolean selected = isFocused() && i == index;
            if (selected) {
                textGraphics.setForegroundColor(detailFocused ? TextColor.ANSI.BLACK_BRIGHT : TextColor.ANSI.WHITE_BRIGHT);
                textGraphics.setCharacter(LIST_ITEM_POS_X - 1, y, '>');
            }
            textGraphics.setForegroundColor(SkillStyle.getColorForSkill(skill));
            textGraphics.setCharacter(LIST_ITEM_POS_X, y, SkillStyle.getSymbolForSkill(skill));

            switch (skill.type) {
                case PRIMARY -> textGraphics.setBackgroundColor(TextColor.ANSI.GREEN);
                case SECONDARY -> textGraphics.setBackgroundColor(TextColor.ANSI.BLUE);
                case TERTIARY -> textGraphics.setBackgroundColor(TextColor.ANSI.RED);
            }
            textGraphics.setForegroundColor(TextColor.ANSI.DEFAULT);
            textGraphics.putString(LIST_ITEM_POS_X + 2, y, "[Lv. " + player.getLevel(skill) + "]");
            textGraphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
            // todo format level string for multi-digit numbers etc
            if (selected) {
                textGraphics.enableModifiers(SGR.BOLD);
            }
            String text = skill.name + " : " + player.getExperience(skill) + "/" + player.getExperienceToNextLevel(skill);
            textGraphics.putString(LIST_ITEM_POS_X + 10, y, fit(text, LIST_WIDTH - 10));
            textGraphics.disableModifiers(SGR.BOLD);
        }
    }

    private void drawProductLines(Screen screen, Player player, Skill skill) {
        TextGraphics textGraphics = screen.newTextGraphics();
        textGraphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
        textGraphics.setForegroundColor(TextColor.ANSI.DEFAULT);
        textGraphics.enableModifiers(SGR.BOLD);
        textGraphics.putString(DETAIL_POS_X, LIST_ITEM_POS_Y + 1, skill.type + " / " + skill.productLines.size() + " lines");
        textGraphics.disableModifiers(SGR.BOLD);

        int fits = (LIST_ROWS - 1) / PRODUCT_LINE_HEIGHT;
        int first = detailFocused ? Math.max(0, detailIndex - (fits - 1)) : 0;
        int y = LIST_ITEM_POS_Y + 2;
        for (int i = first; i < Math.min(skill.productLines.size(), first + fits); i++) {
            ProductLine productLine = skill.productLines.get(i);
            Recipe base = productLine.recipes.get(0);
            boolean selected = detailFocused && i == detailIndex;

            if (selected) {
                textGraphics.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT);
                textGraphics.setCharacter(DETAIL_POS_X - 1, y, '>');
                textGraphics.enableModifiers(SGR.BOLD);
            }
            textGraphics.setForegroundColor(TextColor.ANSI.DEFAULT);
            textGraphics.putString(DETAIL_POS_X, y, productLineName(base.product));
            textGraphics.disableModifiers(SGR.BOLD);

            int x = DETAIL_POS_X + 2;
            textGraphics.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            textGraphics.putString(x, y + 1, "Lv");
            x += 3;
            for (Recipe recipe : productLine.recipes) {
                boolean unlocked = player.getLevel(skill) >= recipe.levelNeeded;
                textGraphics.setForegroundColor(unlocked ? TextColor.ANSI.GREEN_BRIGHT : TextColor.ANSI.BLACK_BRIGHT);
                textGraphics.putString(x, y + 1, String.valueOf(recipe.levelNeeded));
                x += 3;
            }

            textGraphics.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            String source = base.components.keySet().stream()
                    .map(SkillsMenu::productLineName)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("raw");
            textGraphics.putString(DETAIL_POS_X + 2, y + 2, "from " + source);
            y += PRODUCT_LINE_HEIGHT;
        }
    }

    private static String fit(String text, int width) {
        return text.length() <= width ? text : text.substring(0, width - 1) + "~";
    }

    private static String productLineName(Product product) {
        int dash = product.name.lastIndexOf('-');
        return dash < 0 ? product.name : product.name.substring(0, dash);
    }
}
