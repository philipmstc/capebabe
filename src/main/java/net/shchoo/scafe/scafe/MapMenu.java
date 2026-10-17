package net.shchoo.scafe.scafe;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.screen.Screen;

import java.util.List;
import java.util.stream.Collectors;

import static net.shchoo.scafe.scafe.Main.*;

public class MapMenu extends Menu {
    private Board.Position cursor;

    public MapMenu() {
        super("Map");
    }

    @Override
    protected void onFocus(Player player, Board board) {
        cursor = new Board.Position(player.x, player.y);
    }

    @Override
    public Result handleKey(KeyStroke key, Player player, Board board) {
        int dx = 0, dy = 0;
        switch (key.getKeyType()) {
            case ArrowLeft -> dx = -1;
            case ArrowRight -> dx = 1;
            case ArrowUp -> dy = -1;
            case ArrowDown -> dy = 1;
            case Enter -> player.send(new Intent.WalkTo(cursor));
            case Escape -> {
                return Result.RELEASE;
            }
            default -> {
                return Result.IGNORED;
            }
        }
        cursor = Board.clampToView(new Board.Position(cursor.x() + dx, cursor.y() + dy), player.x, player.y);
        return Result.HANDLED;
    }

    @Override
    public void draw(Screen screen, Player player, Board board) {
        TextGraphics textGraphics = screen.newTextGraphics();
        textGraphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
        textGraphics.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        int hintRow = LIST_ITEM_POS_Y + LIST_ROWS;
        Board.Position target;
        if (isFocused()) {
            cursor = Board.clampToView(cursor, player.x, player.y);
            BoardView.drawTile(player, board, screen, cursor.x(), cursor.y(), true);
            target = cursor;
            textGraphics.putString(LIST_ITEM_POS_X, hintRow, "[Enter] walk here  [Esc] back");
        } else {
            target = player.position();
            textGraphics.putString(LIST_ITEM_POS_X, hintRow, "[Down] inspect tiles");
        }

        boolean here = target.equals(player.position());
        textGraphics.setForegroundColor(TextColor.ANSI.DEFAULT);
        textGraphics.putString(LIST_ITEM_POS_X, LIST_ITEM_POS_Y + 1,
                (here ? "Here" : "Tile") + " (" + target.x() + "," + target.y() + ")");
        int row = LIST_ITEM_POS_Y + 2;
        for (Event.EntityInfo other : player.othersAt(target)) {
            textGraphics.setForegroundColor(BoardView.OTHER_PLAYER_COLOR);
            textGraphics.setCharacter(LIST_ITEM_POS_X, row, '*');
            textGraphics.setForegroundColor(TextColor.ANSI.DEFAULT);
            textGraphics.putString(LIST_ITEM_POS_X + 2, row, other.name());
            textGraphics.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            textGraphics.putString(LIST_ITEM_POS_X + 3 + other.name().length(), row++,
                    "total Lv. " + other.totalLevel(player.content));
        }
        Node node = board.nodeAt(target.x(), target.y());
        if (node == null) {
            if (row == LIST_ITEM_POS_Y + 2) {
                textGraphics.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
                textGraphics.putString(LIST_ITEM_POS_X, row, "Nothing here");
            }
            return;
        }
        textGraphics.setForegroundColor(BoardView.nodeColor(node, player));
        textGraphics.setCharacter(LIST_ITEM_POS_X, row, SkillStyle.getSymbolForSkill(node.skill()));
        textGraphics.setForegroundColor(TextColor.ANSI.DEFAULT);
        for (String line : describe(node)) {
            textGraphics.putString(LIST_ITEM_POS_X + 2, row++, line);
        }
        Event.NodeStatus status = player.status(node);
        textGraphics.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        if (node.maxCharges != Node.UNLIMITED) {
            textGraphics.putString(LIST_ITEM_POS_X + 2, row++, status.depleted()
                    ? "  empty -- respawning"
                    : "  " + status.charges() + "/" + node.maxCharges + " left");
        }
        if (node.capacity != Node.UNLIMITED || status.users() > 0) {
            textGraphics.putString(LIST_ITEM_POS_X + 2, row++, "  in use: " + status.users()
                    + (node.capacity == Node.UNLIMITED ? "" : "/" + node.capacity));
        }
        if (player.isLockedFor(node)) {
            textGraphics.setForegroundColor(TextColor.ANSI.RED);
            textGraphics.putString(LIST_ITEM_POS_X + 2, row, player.hasTried(node) ? "  locked -- g to add goal" : "  locked");
        }
    }

    private static List<String> describe(Node node) {
        Recipe recipe = node.recipe;
        String components = recipe.components.keySet().stream().map(p -> p.name).collect(Collectors.joining(", "));
        return List.of(
                recipe.skill.name + " [Lv. " + recipe.levelNeeded + "]",
                "  makes " + recipe.product.name + " (+" + recipe.expGained + "xp)",
                "  needs " + (components.isEmpty() ? "nothing" : components));
    }
}
