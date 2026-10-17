package net.shchoo.scafe.scafe;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.screen.Screen;

import java.util.List;

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
            case Enter -> player.walker.walkTo(cursor);
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
        List<Board.Action> tileActions = board.availableActions(target.x(), target.y());
        if (tileActions.isEmpty()) {
            textGraphics.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            textGraphics.putString(LIST_ITEM_POS_X, LIST_ITEM_POS_Y + 2, "Nothing here");
            return;
        }
        int row = LIST_ITEM_POS_Y + 2;
        for (Board.Action action : tileActions) {
            textGraphics.setForegroundColor(BoardView.nodeColor(action, player));
            textGraphics.setCharacter(LIST_ITEM_POS_X, row, SkillStyle.getSymbolForSkill(action.forWhich()));
            textGraphics.setForegroundColor(TextColor.ANSI.DEFAULT);
            for (String line : action.describe()) {
                textGraphics.putString(LIST_ITEM_POS_X + 2, row++, line);
            }
            if (action.isLockedFor(player)) {
                textGraphics.setForegroundColor(TextColor.ANSI.RED);
                textGraphics.putString(LIST_ITEM_POS_X + 2, row++, action.wasAttempted() ? "  locked -- g to add goal" : "  locked");
                textGraphics.setForegroundColor(TextColor.ANSI.DEFAULT);
            }
        }
    }
}
