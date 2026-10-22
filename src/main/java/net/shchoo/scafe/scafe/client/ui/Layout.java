package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.model.Position;

import com.googlecode.lanterna.TerminalSize;

/**
 * Where every panel goes (one {@link Rect} each, frame included), worked out from the terminal and map sizes,
 * and the map's view math.
 * <pre>
 *  Inventory     # skill0 Lv. 5 [ 203/1.2k ] 600k/h  *    Map  Skills  Goals  Settings
 * +-INVENTORY-+ +-MAP---------------------------------+  +-- TABS -------------+
 * |  item     | |                                     |  | the selected tab /  |
 * |  cells    | |                                     |  | settings            |
 * +-EQUIPMENT-+ |       [ PROGRESS, when busy ]       |  |                     |
 * +-------------+-------------------------------------+  +---------------------+
 * +-LOG ----------------------------------------------+  +-- DETAIL -----------+
 * | log / /command                                    |  | tile ahead / hover  |
 * +---------------------------------------------------+  +---------------------+
 * </pre>
 */
public final class Layout {
    private Layout() {}

    public static final TerminalSize TERMINAL_SIZE = new TerminalSize(142, 40);

    /** The map's size in tiles; each tile is 2 columns by 1 row. */
    public static final int BOARD_WIDTH = 29;
    public static final int BOARD_HEIGHT = 23;

    public static final int HEADER_ROW = 1;
    public static final int HEADER_GAP = 4;
    private static final int LEFT_WIDTH = 23;

    public static final Rect MAP = new Rect(LEFT_WIDTH + 1, HEADER_ROW + 1, 2 * BOARD_WIDTH + 1, BOARD_HEIGHT + 2);
    public static final Rect INVENTORY = new Rect(0, MAP.y(), LEFT_WIDTH, MAP.height() - 4);
    /** Weapon + armour, two lines under the inventory. */
    public static final Rect EQUIPMENT = new Rect(0, INVENTORY.bottom() + 1, LEFT_WIDTH, 4);
    private static final int PROGRESS_WIDTH = 31;
    /** One unframed line over the map's last row of tiles. */
    public static final Rect PROGRESS = new Rect(MAP.x() + (MAP.width() - PROGRESS_WIDTH) / 2, MAP.bottom() - 1, PROGRESS_WIDTH, 1);

    private static final int RIGHT_X = MAP.right() + 2;
    private static final int RIGHT_WIDTH = TERMINAL_SIZE.getColumns() - RIGHT_X;
    /** One unframed line on the very top row, over the map; the tab headers are on the row below. */
    public static final Rect XP = new Rect(MAP.x(), 0, MAP.width() - 1, 1);
    public static final Rect TABS = new Rect(RIGHT_X, MAP.y(), RIGHT_WIDTH, MAP.height());
    public static final Rect PLAYER_DETAILS = new Rect(RIGHT_X + RIGHT_WIDTH + 1, MAP.y(), 6, 1);

    private static final int BOTTOM_Y = MAP.bottom() + 1;
    private static final int BOTTOM_HEIGHT = TERMINAL_SIZE.getRows() - BOTTOM_Y;
    public static final Rect LOG = new Rect(0, BOTTOM_Y, MAP.right() + 1, BOTTOM_HEIGHT);
    public static final Rect DETAIL = new Rect(RIGHT_X, BOTTOM_Y, RIGHT_WIDTH, BOTTOM_HEIGHT);

    // inventory cells: 3 across, 5 rows visible, each 5 wide and 2 tall
    public static final int INVENTORY_COLUMNS = 3;
    public static final int INVENTORY_ROWS = 5;
    public static final int CELL_WIDTH = 6;
    public static final int CELL_STEP_X = 7;
    public static final int CELL_STEP_Y = 4;

    public static final int VIEW_CENTER_COLUMN = BOARD_WIDTH / 2;
    public static final int VIEW_CENTER_ROW = BOARD_HEIGHT / 2;

    public static int viewToWorldX(int viewColumn, int centerX) {
        return centerX + viewColumn - VIEW_CENTER_COLUMN;
    }

    public static int viewToWorldY(int viewRow, int centerY) {
        return centerY + viewRow - VIEW_CENTER_ROW;
    }

    /** Screen column of a world tile, with the view centered on centerX. Tiles are 2 columns wide... */
    public static int tileScreenX(int worldX, int centerX) {
        return MAP.x() + 1 + (worldX - centerX + VIEW_CENTER_COLUMN) * 2;
    }

    /** ...and 1 row tall. */
    public static int tileScreenY(int worldY, int centerY) {
        return MAP.y() + 1 + (worldY - centerY + VIEW_CENTER_ROW) ;
    }

    public static Position clampToView(Position p, int centerX, int centerY) {
        int minX = viewToWorldX(0, centerX), maxX = viewToWorldX(BOARD_WIDTH - 1, centerX);
        int minY = viewToWorldY(0, centerY), maxY = viewToWorldY(BOARD_HEIGHT - 1, centerY);
        return new Position(Math.max(minX, Math.min(maxX, p.x())), Math.max(minY, Math.min(maxY, p.y())));
    }

    public static boolean inView(Position p, int centerX, int centerY) {
        return clampToView(p, centerX, centerY).equals(p);
    }

    /** Cuts text to fit {@code width} columns, marking the cut with "~". */
    public static String fit(String text, int width) {
        return text.length() <= width ? text : text.substring(0, Math.max(0, width - 1)) + "~";
    }
}
