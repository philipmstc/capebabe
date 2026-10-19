package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.SkillStyle;
import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.protocol.Intent;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.screen.Screen;

import static net.shchoo.scafe.scafe.client.ui.Layout.clampToView;
import static net.shchoo.scafe.scafe.client.ui.Layout.fit;

/**
 * The Map tab: focused, arrows move a hover cursor over the map and Enter walks there ({@link MapDetailView}
 * describes the hovered tile). Its box lists your waypoints.
 */
public class MapMenu extends Menu {
    /**
     * Where the hover cursor is, relative to you -- so it stays put on screen while you move, and "hover at the
     * edge, press Enter, press Enter again" keeps walking you that way.
     */
    private int offsetX;
    private int offsetY;

    public MapMenu() {
        super("Map");
    }

    @Override
    protected void onFocus(Player player, Board board) {
        offsetX = player.facing.dx;
        offsetY = player.facing.dy;
    }

    /** The hovered tile right now (wherever you've got to). */
    public Position cursor(Player player) {
        return new Position(player.x + offsetX, player.y + offsetY);
    }

    /** The hovered tile while the cursor is out, else null. */
    public Position hovered(Player player) {
        return isFocused() ? cursor(player) : null;
    }

    @Override
    public Result handleKey(KeyStroke key, Player player, Board board) {
        int dx = 0, dy = 0;
        switch (key.getKeyType()) {
            case ArrowLeft -> dx = -1;
            case ArrowRight -> dx = 1;
            case ArrowUp -> dy = -1;
            case ArrowDown -> dy = 1;
            case Enter -> player.send(new Intent.WalkTo(cursor(player)));
            case Escape -> {
                return Result.RELEASE;
            }
            default -> {
                return Result.IGNORED;
            }
        }
        Position moved = clampToView(new Position(player.x + offsetX + dx, player.y + offsetY + dy), player.x, player.y);
        offsetX = moved.x() - player.x;
        offsetY = moved.y() - player.y;
        return Result.HANDLED;
    }

    @Override
    public void draw(Screen screen, Player player, Board board) {
        if (isFocused()) {
            Position cursor = cursor(player);
            MapView.drawTile(player, board, screen, cursor.x(), cursor.y(), true);
        }
        TextGraphics g = screen.newTextGraphics();
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        g.setForegroundColor(TextColor.ANSI.DEFAULT);
        int row = firstRow();
        g.putString(textX(), row++, "Waypoints");
        if (player.waypoints.isEmpty()) {
            g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            g.putString(textX(), row, fit("none -- Skills tab, pick a tier, [Enter]", textWidth()));
        }
        for (Node node : player.waypoints.nodes()) {
            if (row >= hintRow()) {
                break;
            }
            g.setForegroundColor(MapView.WAYPOINT_COLOR);
            g.setCharacter(textX(), row, SkillStyle.getSymbolForSkill(node.skill()));
            g.setForegroundColor(TextColor.ANSI.DEFAULT);
            g.putString(textX() + 2, row++, fit(node.recipe.product.name + " at (" + node.position.x() + ","
                    + node.position.y() + "), " + player.stepsTo(node.position) + " steps", textWidth() - 2));
        }
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        g.putString(textX(), hintRow(), isFocused() ? "[arrows] hover  [Enter] walk  [Esc] back" : "[Down] hover the map");
    }
}
