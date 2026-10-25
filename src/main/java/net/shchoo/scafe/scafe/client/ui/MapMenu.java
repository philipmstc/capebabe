package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.SkillStyle;
import net.shchoo.scafe.scafe.model.Building;
import net.shchoo.scafe.scafe.model.MonsterType;
import net.shchoo.scafe.scafe.protocol.Event;
import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.Product;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.screen.Screen;

import java.util.Comparator;

import static net.shchoo.scafe.scafe.client.ui.Layout.clampToView;
import static net.shchoo.scafe.scafe.client.ui.Layout.inView;
import static net.shchoo.scafe.scafe.client.ui.Layout.fit;
import static net.shchoo.scafe.scafe.client.ui.Layout.TERMINAL_SIZE;
import static net.shchoo.scafe.scafe.client.ui.Layout.tileScreenX;
import static net.shchoo.scafe.scafe.client.ui.Layout.tileScreenY;

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
    /** Picked in the inventory to use on a node; while set, Enter uses it on the hovered node. */
    private Product using;
    /** A node / monster / player's menu (Enter on a node, or right-click) while it's open, else null. */
    private PopupMenu popup;
    /** Where the popup's about. */
    private Position popupAt;
    /** The popup came from a right-click: closing it puts the map back down too. */
    private boolean popupByMouse;

    public MapMenu() {
        super("Map");
    }

    @Override
    protected void onFocus(Player player, Board board) {
        offsetX = player.facing.dx;
        offsetY = player.facing.dy;
        using = null;
    }

    @Override
    public void unfocus() {
        super.unfocus();
        using = null;
        popup = null;
    }

    /** Focus with {@code item} in hand: the cursor starts on the nearest node on screen that takes it. */
    public void startUsing(Product item, Player player, Board board) {
        focus(player, board);
        using = item;
        player.content.nodes().stream()
                .filter(node -> node.recipe.components.containsKey(item.root()))
                .filter(node -> inView(node.position, player.x, player.y))
                .min(Comparator.comparingInt(node -> player.stepsTo(node.position)))
                .ifPresent(node -> {
                    offsetX = node.position.x() - player.x;
                    offsetY = node.position.y() - player.y;
                });
    }

    /** The item in hand, or null. */
    public Product using() {
        return using;
    }

    /** The hovered tile right now (wherever you've got to). */
    public Position cursor(Player player) {
        return new Position(player.x + offsetX, player.y + offsetY);
    }

    /** The hovered tile while the cursor is out, else null. */
    public Position hovered(Player player) {
        return isFocused() ? cursor(player) : null;
    }

    public boolean nodeMenuOpen() {
        return popup != null && popup.isOpen();
    }

    /** The open popup (for tests), or null. */
    public PopupMenu popup() {
        return nodeMenuOpen() ? popup : null;
    }

    /** What doing an option puts the map down after: anything but Cancel (and Cancel too, from a right-click). */
    private boolean acted;

    private Runnable then(Runnable action) {
        return () -> {
            acted = true;
            action.run();
        };
    }

    /**
     * A node's menu: Craft (with what it uses under it) / Craft AFK / Walk here / Cancel, titled with what it
     * makes.
     */
    private PopupMenu nodeMenu(Node node, Player player) {
        String name = node.recipe.product.name;
        String cost = node.recipe.components.isEmpty() ? null : node.recipe.components.entrySet().stream()
                .map(e -> e.getValue() + " x " + e.getKey().name).collect(java.util.stream.Collectors.joining(", "));
        Position at = node.position;
        return new PopupMenu(name, MapView.nodeColor(node, player), java.util.List.of(
                new PopupMenu.Option("Craft " + name, cost, then(() -> player.craft(at, false))),
                PopupMenu.Option.of("AFK " + name, then(() -> player.craft(at, true))),
                PopupMenu.Option.of("Walk here", then(() -> player.walkTo(at)))));
    }

    /** A monster's menu: "monster3 (Lv 12)", Fight / Walk here / Cancel. */
    private PopupMenu monsterMenu(Event.EntityInfo monster, MonsterType type, Player player) {
        Position at = monster.position();
        return new PopupMenu(monster.name() + " (Lv " + type.level + ")", SkillStyle.getColorForMonster(type), java.util.List.of(
                PopupMenu.Option.of("Fight", then(() -> player.fight(monster.id()))),
                PopupMenu.Option.of("Walk here", then(() -> player.walkTo(at)))));
    }

    /** Another player's menu: "shchoo (Lv 12)" (combat), Trade / Fight / Friend (coming) / Walk here / Cancel. */
    private PopupMenu playerMenu(Event.EntityInfo other, Player player) {
        Position at = other.position();
        return new PopupMenu(other.name() + " (Lv " + Math.max(1, other.combatLevel()) + ")",
                SkillStyle.playerColor(other.colorIndex()), java.util.List.of(
                new PopupMenu.Option("Trade", null, null),
                new PopupMenu.Option("Fight", null, null),
                new PopupMenu.Option("Friend", null, null),
                PopupMenu.Option.of("Walk here", then(() -> player.walkTo(at)))));
    }

    /** Opens a popup; when it closes the map goes back down if something was done, or it was a right-click's. */
    private void open(PopupMenu menu, Position at, boolean byMouse) {
        popup = menu;
        popupAt = at;
        popupByMouse = byMouse;
        acted = false;
        menu.onClose(() -> {
            popup = null;
            if (acted || popupByMouse) {
                unfocus();
            }
        });
    }

    /**
     * A click on the map's tile {@code tile}. Aiming an item: left uses it there, right cancels. Otherwise left:
     * a monster -- walk up and fight it; a node -- walk up and craft; a building -- walk up and use it (q); anywhere
     * else -- walk there. Right: the monster's / player's / node's menu; nothing elsewhere.
     */
    public void clickTile(Position tile, Clicks.Button button, Player player, Board board) {
        if (using != null && isFocused()) {
            if (button == Clicks.Button.LEFT) {
                player.useItem(using, tile);
            }
            unfocus();
            return;
        }
        java.util.List<Event.EntityInfo> here = player.othersAt(tile);
        Event.EntityInfo monster = here.stream().filter(Event.EntityInfo::isMonster).findFirst().orElse(null);
        Event.EntityInfo other = here.stream().filter(e -> !e.isMonster()).findFirst().orElse(null);
        MonsterType type = monster == null ? null : player.content.monster(monster.monsterTypeId());
        Node node = board.nodeAt(tile.x(), tile.y());
        Building building = player.content.solidBuildingAt(tile);
        if (button == Clicks.Button.LEFT) {
            unfocus();
            if (monster != null) {
                player.fight(monster.id());
            } else if (node != null) {
                player.craft(tile, false);
            } else if (building != null) {
                player.useBuilding(building);
            } else {
                player.walkTo(tile);
            }
            return;
        }
        PopupMenu menu = monster != null && type != null ? monsterMenu(monster, type, player)
                : other != null ? playerMenu(other, player)
                : node != null ? nodeMenu(node, player) : null;
        if (menu == null) {
            return;
        }
        claim(player, board);
        offsetX = tile.x() - player.x;
        offsetY = tile.y() - player.y;
        open(menu, tile, true);
    }

    @Override
    public Result handleKey(KeyStroke key, Player player, Board board) {
        if (nodeMenuOpen()) {
            popup.key(key);
            return Result.HANDLED;
        }
        int dx = 0, dy = 0;
        switch (key.getKeyType()) {
            case ArrowLeft -> dx = -1;
            case ArrowRight -> dx = 1;
            case ArrowUp -> dy = -1;
            case ArrowDown -> dy = 1;
            case Enter -> {
                if (using != null) {
                    player.useItem(using, cursor(player));
                    return Result.RELEASE;
                }
                Node node = board.nodeAt(cursor(player).x(), cursor(player).y());
                if (node != null) {
                    open(nodeMenu(node, player), node.position, false);
                    return Result.HANDLED;
                }
                player.walkTo(cursor(player));
            }
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
        if (isFocused() && using != null) {
            g.setForegroundColor(TextColor.ANSI.YELLOW_BRIGHT);
            g.putString(textX(), row++, fit("Using " + using.name + " on...", textWidth()));
            Node hovered = board.nodeAt(cursor(player).x(), cursor(player).y());
            boolean takes = hovered != null && hovered.recipe.components.containsKey(using.root());
            g.setForegroundColor(takes ? TextColor.ANSI.GREEN_BRIGHT : TextColor.ANSI.BLACK_BRIGHT);
            g.putString(textX(), row++, fit(hovered == null ? "  (point at a node)"
                    : takes ? "  " + hovered.label() + ": takes it" : "  " + hovered.label() + ": doesn't take it", textWidth()));
            row++;
            g.setForegroundColor(TextColor.ANSI.DEFAULT);
        }
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
        g.putString(textX(), hintRow(), !isFocused() ? "[Down] hover the map"
                : using != null ? "[arrows] aim  [Enter] use it  [Esc] cancel" : "[arrows] hover  [Enter] walk / node menu  [Esc] back");
    }

    /** The popup, just right of what it's about on the map (a click that closes it puts the map down if it should). */
    @Override
    public void drawOnTop(Screen screen, Player player, Board board) {
        if (popup == null || !popup.isOpen()) {
            return;
        }
        popup.draw(screen, tileScreenX(popupAt.x(), player.x) + 3, tileScreenY(popupAt.y(), player.y) - 1, board.clicks);
    }
}
