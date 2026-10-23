package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.Popup;
import net.shchoo.scafe.scafe.client.SkillStyle;
import net.shchoo.scafe.scafe.model.Building;
import net.shchoo.scafe.scafe.model.Direction;
import net.shchoo.scafe.scafe.model.MonsterType;
import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.protocol.Event;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.Symbols;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.screen.Screen;
import java.util.List;

import static net.shchoo.scafe.scafe.client.ui.Layout.*;

/**
 * The map (always on screen), the home arrow, waypoints and popups. Tile positions come from {@link Layout}'s view
 * math, which uses {@link Layout#MAP}, so this should be given that box.
 */
public class MapView extends Widget {

    public MapView(Rect bounds) {
        super(bounds);
    }

    @Override
    public void draw(Screen screen, Player player, Board board) {
        Rect map = bounds();
        drawFrame(screen);
        TextGraphics coords = screen.newTextGraphics();
        coords.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        coords.putString(map.x() + 2, map.y(), "(" + player.x + "," + player.y + ")" + (player.running ? " run" : ""));
        if (player.content.combatSkill() != null) {
            String hp = "hp " + player.hp + "/" + player.maxHp;
            coords.setForegroundColor(player.hp * 3 <= player.maxHp ? TextColor.ANSI.RED_BRIGHT : TextColor.ANSI.GREEN);
            coords.putString(map.right() - 1 - hp.length(), map.y(), hp);
        }
        for (int i = 0; i < BOARD_HEIGHT; i++) {
            for (int j = 0; j < BOARD_WIDTH; j++) {
                drawTile(player, board, screen, viewToWorldX(j, player.x), viewToWorldY(i, player.y), false);
            }
        }
        drawHomeIndicator(map, player, screen);
        drawWaypoint(map, player, screen);
        drawPopups(map, player, screen);
    }

    private static void drawPopups(Rect map, Player player, Screen screen) {
        TextGraphics graphics = screen.newTextGraphics();
        graphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
        int innerWidth = map.width() - 2;
        for (Popup popup : player.popups) {
            Position at = popup.at();
            if (!inView(at, player.x, player.y)) {
                continue;
            }
            String text = popup.text.length() > innerWidth ? popup.text.substring(0, innerWidth) : popup.text;
            int tileX = tileScreenX(at.x(), player.x);
            int tileY = tileScreenY(at.y(), player.y);
            int y = Math.max(map.y() + 1, tileY - popup.rise());
            int x = popup.side < 0 ? tileX - 1 - text.length() : popup.side > 0 ? tileX + 2 : tileX - text.length() / 2;
            x = Math.max(map.x() + 1, Math.min(map.right() - text.length(), x));
            graphics.setForegroundColor(popup.color);
            graphics.setBackgroundColor(popup.background);
            graphics.enableModifiers(SGR.BOLD);
            graphics.putString(x, y, text);
        }
    }

    /** Arrow on the map border pointing at the last place the player made something (or the origin), when it's off-screen. */
    private static void drawHomeIndicator(Rect map, Player player, Screen screen) {
        Position home = player.lastActionAt != null ? player.lastActionAt : new Position(0, 0);
        if (!inView(home, player.x, player.y)) {
            drawEdgeArrow(map, player, screen, home,
                    player.lastActionAt != null ? TextColor.ANSI.YELLOW_BRIGHT : TextColor.ANSI.WHITE_BRIGHT);
        }
    }

    /** Each waypoint's node in [brackets] when it's on screen, else an arrow on the border (drawn over the home arrow). */
    private static void drawWaypoint(Rect map, Player player, Screen screen) {
        for (Node waypoint : player.waypoints.nodes()) {
            Position at = waypoint.position;
            if (!inView(at, player.x, player.y)) {
                drawEdgeMarker(map, player, screen, at, WAYPOINT_COLOR);
            }
            else {
                int x = tileScreenX(at.x(), player.x), y = tileScreenY(at.y(), player.y);
                screen.setCharacter(x - 1, y, TextCharacter.fromCharacter('[', WAYPOINT_COLOR, TextColor.ANSI.DEFAULT)[0]);
                screen.setCharacter(x + 1, y, TextCharacter.fromCharacter(']', WAYPOINT_COLOR, TextColor.ANSI.DEFAULT)[0]);
            }
        }
    }

    public static final TextColor WAYPOINT_COLOR = TextColor.ANSI.MAGENTA_BRIGHT;

    private static void drawEdgeArrow(Rect map, Player player, Screen screen, Position target, TextColor color) {
        Edge edge = edgeTowards(map, player, target);
        screen.setCharacter(edge.x, edge.y, TextCharacter.fromCharacter(arrow(edge.side), color, TextColor.ANSI.DEFAULT)[0]);
    }

    /**
     * A waypoint off-screen: three cells of the border in double lines with an arrow in the middle (along the
     * top: double line, arrow, double line; down a side the same, vertically). Within a cell of a corner it wraps
     * round the corner instead -- the double corner symbol with one double line along each edge, no arrow.
     */
    private static void drawEdgeMarker(Rect map, Player player, Screen screen, Position target, TextColor color) {
        Edge edge = edgeTowards(map, player, target);
        boolean horizontal = edge.side == Direction.UP || edge.side == Direction.DOWN;
        int stepX = horizontal ? 1 : 0, stepY = horizontal ? 0 : 1;
        for (int i = -1; i <= 1; i++) {
            int x = edge.x + i * stepX, y = edge.y + i * stepY;
            if (corner(map, x, y) != 0) {
                int inX = x == map.x() ? 1 : -1, inY = y == map.y() ? 1 : -1;
                put(screen, x, y, corner(map, x, y), color);
                put(screen, x + inX, y, Symbols.DOUBLE_LINE_HORIZONTAL, color);
                put(screen, x, y + inY, Symbols.DOUBLE_LINE_VERTICAL, color);
                return;
            }
        }
        char line = horizontal ? Symbols.DOUBLE_LINE_HORIZONTAL : Symbols.DOUBLE_LINE_VERTICAL;
        put(screen, edge.x - stepX, edge.y - stepY, line, color);
        put(screen, edge.x, edge.y, arrow(edge.side), color);
        put(screen, edge.x + stepX, edge.y + stepY, line, color);
    }

    private static void put(Screen screen, int x, int y, char c, TextColor color) {
        screen.setCharacter(x, y, TextCharacter.fromCharacter(c, color, TextColor.ANSI.DEFAULT)[0]);
    }

    /** The double-line corner symbol if (x, y) is one of the map box's corners, else 0. */
    private static char corner(Rect map, int x, int y) {
        boolean left = x == map.x(), right = x == map.right(), top = y == map.y(), bottom = y == map.bottom();
        if (top && left) return Symbols.DOUBLE_LINE_TOP_LEFT_CORNER;
        if (top && right) return Symbols.DOUBLE_LINE_TOP_RIGHT_CORNER;
        if (bottom && left) return Symbols.DOUBLE_LINE_BOTTOM_LEFT_CORNER;
        if (bottom && right) return Symbols.DOUBLE_LINE_BOTTOM_RIGHT_CORNER;
        return 0;
    }

    private static char arrow(Direction side) {
        return switch (side) {
            case UP -> Symbols.ARROW_UP;
            case DOWN -> Symbols.ARROW_DOWN;
            case LEFT -> Symbols.ARROW_LEFT;
            case RIGHT -> Symbols.ARROW_RIGHT;
        };
    }

    /** A cell on the map's border (never a corner) and which side it's on. */
    private record Edge(int x, int y, Direction side) {}

    /** Where the line from you towards {@code target} (off-screen) crosses the map's border. */
    private static Edge edgeTowards(Rect map, Player player, Position target) {
        int centerX = tileScreenX(player.x, player.x);
        int centerY = tileScreenY(player.y, player.y);
        // screen distance from the center to the border, and to the target (tiles are 2 columns by 1 row)
        int halfWidth = map.right() - centerX, halfHeight = map.bottom() - centerY;
        double dx = (target.x() - player.x) * 2.0;
        double dy = target.y() - player.y;
        double scale = Math.min(dx == 0 ? Double.MAX_VALUE : halfWidth / Math.abs(dx),
                dy == 0 ? Double.MAX_VALUE : halfHeight / Math.abs(dy));
        int x = (int) Math.round(centerX + dx * scale);
        int y = (int) Math.round(centerY + dy * scale);
        if (Math.abs(dx) * halfHeight >= Math.abs(dy) * halfWidth) {
            return new Edge(dx > 0 ? map.right() : map.x(), Math.max(map.y() + 1, Math.min(map.bottom() - 1, y)),
                    dx > 0 ? Direction.RIGHT : Direction.LEFT);
        }
        return new Edge(Math.max(map.x() + 1, Math.min(map.right() - 1, x)), dy > 0 ? map.bottom() : map.y(),
                dy > 0 ? Direction.DOWN : Direction.UP);
    }

    public static void drawTile(Player player, Board board, Screen screen, int worldX, int worldY, boolean highlighted) {
        Node node = board.nodeAt(worldX, worldY);
        Building building = player.content.buildingAt(new Position(worldX, worldY));
        boolean isPlayerPosition = player.x == worldX && player.y == worldY;
        boolean hasAction = node != null;
        boolean isOrigin = worldX == 0 && worldY == 0;
        char result = isPlayerPosition
                ? player.facing.arrow
                : hasAction
                ? SkillStyle.getSymbolForSkill(node.skill())
                : isOrigin ? 'o' : Symbols.BULLET;

        TextColor fg = isPlayerPosition
                ? player.hp <= 0 && player.flickerOff() ? TextColor.ANSI.BLACK_BRIGHT : SkillStyle.playerColor(player.colorIndex)
                : hasAction
                ? nodeColor(node, player)
                : new Position(worldX, worldY).equals(player.walkTarget) ? TextColor.ANSI.RED_BRIGHT
                : worldX % 10 == 0 && worldY % 10 == 0 ? TextColor.ANSI.WHITE_BRIGHT : TextColor.ANSI.BLACK_BRIGHT;
        char leftOfBuilding = ' ';
        char rightOfBuilding = ' ';
        if (building != null && !isPlayerPosition) {
            Position at = new Position(worldX, worldY);
            result = building.glyph(at);
            fg = TextColor.ANSI.YELLOW_BRIGHT;
            leftOfBuilding = building.leftOf(at);
            rightOfBuilding = building.rightOf(at);
        }
        if (!isPlayerPosition) {
            List<Event.EntityInfo> here = player.othersAt(new Position(worldX, worldY));
            Event.EntityInfo monster = here.stream().filter(Event.EntityInfo::isMonster).findFirst().orElse(null);
            MonsterType type = monster == null ? null : player.content.monster(monster.monsterTypeId());
            if (type != null) {
                result = type.symbol;
                fg = SkillStyle.getColorForMonster(type);
            } else if (!here.isEmpty()) {
                result = here.get(0).facing().arrow;
                fg = here.get(0).hp() <= 0 && player.flickerOff() ? TextColor.ANSI.BLACK_BRIGHT
                        : SkillStyle.playerColor(here.get(0).colorIndex());
            }
        }

        int screenX = tileScreenX(worldX, player.x);
        int screenY = tileScreenY(worldY, player.y);
        TextColor bg = highlighted ? TextColor.ANSI.BLACK_BRIGHT : TextColor.ANSI.DEFAULT;
        if (highlighted && fg == TextColor.ANSI.BLACK_BRIGHT) {
            fg = TextColor.ANSI.WHITE;
        }

        char leftOfNode = leftOfBuilding;
        char rightOfNode = rightOfBuilding;
        boolean marked = false;
        if (hasAction) {
            if (player.highlightedRecipes.contains(node.recipe)) {
                bg = SkillStyle.darkOf(node.skill());
                fg = TextColor.ANSI.BLACK;
                marked = true;
            }
            if (player.showProductTiers) {
                if (node.recipe.product.tier > 0) {
                    leftOfNode = '.';
                }
                if (node.recipe.product.tier > 1) {
                    rightOfNode = '.';
                }
                if (node.recipe.product.tier > 2) {
                    leftOfNode = ':';
                }
                if (node.recipe.product.tier > 3) {
                    rightOfNode = ':';
                }
            }
        }
        // The cells either side of a tile are shared with its neighbours (tiles are 2 columns apart), and tiles are
        // drawn left to right: write them only when there's something to show there, or the next tile along would
        // be wiping out this one's right-hand cell (and this one its left neighbour's) with a plain blank.
        // ...and never onto the map's frame: the outermost columns' side cells are the border itself
        boolean wholeCell = marked || highlighted;
        if ((wholeCell || leftOfNode != ' ') && screenX - 1 > Layout.MAP.x()) {
            screen.setCharacter(screenX - 1, screenY, TextCharacter.fromCharacter(leftOfNode, fg, bg)[0]);
        }
        if ((wholeCell || rightOfNode != ' ') && screenX + 1 < Layout.MAP.right()) {
            screen.setCharacter(screenX + 1, screenY, TextCharacter.fromCharacter(rightOfNode, fg, bg)[0]);
        }
        screen.setCharacter(screenX, screenY, TextCharacter.fromCharacter(result, fg, bg)[0]);
    }

    public static TextColor nodeColor(Node node, Player player) {
        if (player.status(node).depleted()) {
            return TextColor.ANSI.BLACK_BRIGHT;
        }
        TextColor.ANSI color = SkillStyle.getColorForSkill(node.skill());
        return player.hasTried(node) && player.isLockedFor(node) ? SkillStyle.dim(color) : color;
    }
}
