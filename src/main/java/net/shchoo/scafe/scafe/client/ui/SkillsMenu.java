package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.SkillStyle;
import net.shchoo.scafe.scafe.model.ContentBatch;
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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;


import static net.shchoo.scafe.scafe.client.ui.Layout.*;

public class SkillsMenu extends Menu {
    /** The skill list's width while the product lines show beside it. */
    private static final int NARROW_LIST_WIDTH = 24;
    private static final int PRODUCT_LINE_HEIGHT = 3;

    private int index = 0;
    private boolean detailFocused = false;
    private int detailIndex = 0;
    /** Which recipe (tier) of the selected product line; Enter sets a waypoint to the nearest node for it. */
    private int tierIndex = 0;

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
                case ArrowLeft -> {
                    if (tierIndex == 0) {
                        detailFocused = false;
                    } else {
                        tierIndex--;
                    }
                }
                case ArrowRight -> tierIndex++;
                case Enter -> player.waypoints.toggle(selectedRecipe(skill), player);
                case Escape -> detailFocused = false;
                default -> {
                    return Result.IGNORED;
                }
            }
            tierIndex = Math.max(0, Math.min(skill.productLines.get(detailIndex).recipes.size() - 1, tierIndex));
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
                    tierIndex = 0;
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
            separator.drawLine(new TerminalPosition(detailX() - 2, firstRow()),
                    new TerminalPosition(detailX() - 2, hintRow()),
                    Symbols.SINGLE_LINE_VERTICAL);
            drawProductLines(screen, player, board.skills.get(index));
        }
    }

    /** Where the product lines go, right of the (narrowed) skill list. */
    private int detailX() {
        return textX() + NARROW_LIST_WIDTH + 2;
    }

    private int detailWidth() {
        return textX() + textWidth() - detailX();
    }

    public Recipe selectedRecipe(Skill skill) {
        return skill.productLines.get(detailIndex).recipes.get(tierIndex);
    }

    private void drawList(Screen screen, Player player, Board board) {
        TextGraphics textGraphics = screen.newTextGraphics();
        textGraphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
        int start = isFocused() ? Math.max(0, Math.min(index - rows() / 2, board.skills.size() - rows())) : 0;
        for (int i = start; i < Math.min(board.skills.size(), start + rows()); i++) {
            Skill skill = board.skills.get(i);
            int y = firstRow() + (i - start);
            boolean selected = isFocused() && i == index;
            if (selected) {
                textGraphics.setForegroundColor(detailFocused ? TextColor.ANSI.BLACK_BRIGHT : TextColor.ANSI.WHITE_BRIGHT);
                textGraphics.setCharacter(textX() - 1, y, '>');
            }
            textGraphics.setForegroundColor(SkillStyle.getColorForSkill(skill));
            textGraphics.setCharacter(textX(), y, SkillStyle.getSymbolForSkill(skill));

            switch (skill.type) {
                case PRIMARY -> textGraphics.setBackgroundColor(TextColor.ANSI.GREEN);
                case SECONDARY -> textGraphics.setBackgroundColor(TextColor.ANSI.BLUE);
                case TERTIARY -> textGraphics.setBackgroundColor(TextColor.ANSI.RED);
            }
            textGraphics.setForegroundColor(TextColor.ANSI.DEFAULT);
            textGraphics.putString(textX() + 2, y, "[Lv. " + player.getLevel(skill) + "]");
            textGraphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
            // todo format level string for multi-digit numbers etc
            if (selected) {
                textGraphics.enableModifiers(SGR.BOLD);
            }
            String text = skill.name + " " + XpTrackerView.compact(player.getExperience(skill))
                    + "/" + XpTrackerView.compact(player.getExperienceToNextLevel(skill));
            textGraphics.putString(textX() + 10, y, fit(text, (isFocused() ? NARROW_LIST_WIDTH : textWidth()) - 10));
            textGraphics.disableModifiers(SGR.BOLD);
        }
    }

    private void drawProductLines(Screen screen, Player player, Skill skill) {
        TextGraphics textGraphics = screen.newTextGraphics();
        textGraphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
        textGraphics.setForegroundColor(TextColor.ANSI.DEFAULT);
        textGraphics.enableModifiers(SGR.BOLD);
        textGraphics.putString(detailX(), firstRow(), fit(skill.type + " / " + skill.productLines.size() + " lines", detailWidth()));
        textGraphics.disableModifiers(SGR.BOLD);
        textGraphics.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        ContentBatch.Origin origin = player.content.origin(skill.id);
        textGraphics.putString(detailX(), firstRow() + 1, fit(origin == null ? "found by ?" : "found by " + origin.by(), detailWidth()));
        textGraphics.putString(detailX(), firstRow() + 2, fit(origin == null ? "(before this was kept)" : "on " + date(origin.at()), detailWidth()));
        textGraphics.setForegroundColor(TextColor.ANSI.DEFAULT);

        int fits = (rows() - 5) / PRODUCT_LINE_HEIGHT;
        int first = detailFocused ? Math.max(0, detailIndex - (fits - 1)) : 0;
        int y = firstRow() + 3;
        for (int i = first; i < Math.min(skill.productLines.size(), first + fits); i++) {
            ProductLine productLine = skill.productLines.get(i);
            Recipe base = productLine.recipes.get(0);
            boolean selected = detailFocused && i == detailIndex;

            if (selected) {
                textGraphics.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT);
                textGraphics.setCharacter(detailX() - 1, y, '>');
                textGraphics.enableModifiers(SGR.BOLD);
            }
            textGraphics.setForegroundColor(TextColor.ANSI.DEFAULT);
            textGraphics.putString(detailX(), y, fit(productLineName(base.product), detailWidth()));
            textGraphics.disableModifiers(SGR.BOLD);

            int x = detailX() + 2;
            textGraphics.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            textGraphics.putString(x, y + 1, "Lv");
            x += 3;
            int firstTier = selected ? Math.max(0, tierIndex - (detailWidth() - 5) / 3 + 1) : 0;
            for (int t = firstTier; t < productLine.recipes.size(); t++) {
                Recipe recipe = productLine.recipes.get(t);
                if (x + 3 > detailX() + detailWidth()) {
                    textGraphics.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
                    textGraphics.putString(x - 1, y + 1, "~");
                    break;
                }
                boolean unlocked = player.getLevel(skill) >= recipe.levelNeeded;
                boolean isWaypoint = player.waypoints.has(recipe);
                textGraphics.setForegroundColor(isWaypoint ? TextColor.ANSI.MAGENTA_BRIGHT
                        : unlocked ? TextColor.ANSI.GREEN_BRIGHT : TextColor.ANSI.BLACK_BRIGHT);
                textGraphics.setBackgroundColor(selected && t == tierIndex ? TextColor.ANSI.BLACK_BRIGHT : TextColor.ANSI.DEFAULT);
                if (selected && t == tierIndex && !unlocked && !isWaypoint) {
                    textGraphics.setForegroundColor(TextColor.ANSI.WHITE);
                }
                textGraphics.putString(x, y + 1, String.valueOf(recipe.levelNeeded));
                textGraphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
                x += 3;
            }

            textGraphics.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            String source = base.components.keySet().stream()
                    .map(SkillsMenu::productLineName)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("raw");
            textGraphics.putString(detailX() + 2, y + 2, fit("from " + source, detailWidth() - 2));
            y += PRODUCT_LINE_HEIGHT;
        }
        if (detailFocused) {
            Recipe recipe = selectedRecipe(skill);
            textGraphics.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            textGraphics.putString(detailX(), hintRow() - 1, fit(recipe.product.name, detailWidth()));
            textGraphics.putString(detailX(), hintRow(), "[Enter] waypoint");
        }
    }

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** A creation time in this computer's time zone. */
    static String date(long epochMillis) {
        return WHEN.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }

    private static String productLineName(Product product) {
        int dash = product.name.lastIndexOf('-');
        return dash < 0 ? product.name : product.name.substring(0, dash);
    }
}
