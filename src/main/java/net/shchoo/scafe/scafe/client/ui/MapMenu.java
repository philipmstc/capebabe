package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.SkillStyle;
import net.shchoo.scafe.scafe.model.MonsterType;
import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.Recipe;
import net.shchoo.scafe.scafe.protocol.Event;
import net.shchoo.scafe.scafe.protocol.Intent;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.screen.Screen;
import java.util.List;
import java.util.stream.Collectors;

import static net.shchoo.scafe.scafe.client.ui.Layout.*;

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
        TextGraphics textGraphics = screen.newTextGraphics();
        textGraphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
        textGraphics.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        int hintRow = LIST_ITEM_POS_Y + LIST_ROWS;
        Position target;
        if (isFocused()) {
            Position cursor = cursor(player);
            MapView.drawTile(player, board, screen, cursor.x(), cursor.y(), true);
            target = cursor;
            textGraphics.putString(LIST_ITEM_POS_X, hintRow, "[Enter] walk here  [Esc] back");
        } else {
            target = player.ahead();
            textGraphics.putString(LIST_ITEM_POS_X, hintRow, "[Down] inspect tiles");
        }

        boolean here = target.equals(player.ahead());
        textGraphics.setForegroundColor(TextColor.ANSI.DEFAULT);
        textGraphics.putString(LIST_ITEM_POS_X, LIST_ITEM_POS_Y + 1,
                (here ? "Ahead" : "Tile") + " (" + target.x() + "," + target.y() + ")");
        int row = LIST_ITEM_POS_Y + 2;
        for (Event.EntityInfo other : player.othersAt(target)) {
            MonsterType type = other.isMonster() ? player.content.monster(other.monsterTypeId()) : null;
            textGraphics.setForegroundColor(type != null ? SkillStyle.getColorForMonster(type) : SkillStyle.playerColor(other.colorIndex()));
            textGraphics.setCharacter(LIST_ITEM_POS_X, row, type != null ? type.symbol : other.facing().arrow);
            textGraphics.setForegroundColor(TextColor.ANSI.DEFAULT);
            textGraphics.putString(LIST_ITEM_POS_X + 2, row, other.name());
            textGraphics.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            String details = (type != null ? "Lv. " + type.level : "total Lv. " + other.totalLevel(player.content))
                    + "  hp " + other.hp() + "/" + other.maxHp();
            textGraphics.putString(LIST_ITEM_POS_X + 3 + other.name().length(), row++, details);
            if (type != null) {
                boolean nextTo = Math.max(Math.abs(target.x() - player.x), Math.abs(target.y() - player.y)) <= 1;
                textGraphics.putString(LIST_ITEM_POS_X + 2, row++, nextTo ? "  [f] fight" : "  get next to it to fight");
                textGraphics.putString(LIST_ITEM_POS_X + 2, row++, "  one-of-a-kinds found: " + type.drops.size()
                        + ", next 1 in " + type.nextDropOneIn());
            }
        }
        Node node = board.nodeAt(target.x(), target.y());
        if (node == null) {
            if (row == LIST_ITEM_POS_Y + 2) {
                textGraphics.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
                textGraphics.putString(LIST_ITEM_POS_X, row, "Nothing here");
            }
            return;
        }
        textGraphics.setForegroundColor(MapView.nodeColor(node, player));
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
