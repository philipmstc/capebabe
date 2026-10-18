package net.shchoo.scafe.scafe.client.ui;

import com.googlecode.lanterna.TerminalSize;
import net.shchoo.scafe.scafe.model.Position;

/** Where everything goes on screen, and the map's view math. */
public final class Layout {
    private Layout() {}

    public static final TerminalSize TERMINAL_SIZE = new TerminalSize(100, 28);

    public static final int BOARD_HEIGHT = 7;
    public static final int BOARD_WIDTH = 11;
    public static final int BOARD_POS_X = 0;
    public static final int BOARD_POS_Y = 1;
    public static final int COMMAND_POS_X = 0;
    public static final int COMMAND_POS_Y = BOARD_POS_Y + 2 * BOARD_HEIGHT + 4;
    public static final int HEADER_POS_X = BOARD_POS_X + BOARD_WIDTH - 1;
    public static final int HEADER_POS_Y = 0;
    public static final int HEADER_GAP = 5;
    public static final int LIST_ITEM_POS_X = BOARD_POS_X + 2 * BOARD_WIDTH + 2;
    public static final int LIST_ITEM_POS_Y = 0;
    public static final int LOG_POS_X = 0;
    public static final int LOG_POS_Y = COMMAND_POS_Y + 1;
    public static final int LOG_LINES = 7;
    public static final int LIST_ROWS = COMMAND_POS_Y - 1 - LIST_ITEM_POS_Y;

    public static final int VIEW_CENTER_COLUMN = BOARD_WIDTH / 2;
    public static final int VIEW_CENTER_ROW = BOARD_HEIGHT / 2;

    public static int viewToWorldX(int viewColumn, int centerX) {
        return centerX + viewColumn - VIEW_CENTER_COLUMN;
    }

    public static int viewToWorldY(int viewRow, int centerY) {
        return centerY + viewRow - VIEW_CENTER_ROW;
    }

    public static Position clampToView(Position p, int centerX, int centerY) {
        int minX = viewToWorldX(0, centerX), maxX = viewToWorldX(BOARD_WIDTH - 1, centerX);
        int minY = viewToWorldY(0, centerY), maxY = viewToWorldY(BOARD_HEIGHT - 1, centerY);
        return new Position(Math.max(minX, Math.min(maxX, p.x())), Math.max(minY, Math.min(maxY, p.y())));
    }

    public static boolean inView(Position p, int centerX, int centerY) {
        return clampToView(p, centerX, centerY).equals(p);
    }
}
