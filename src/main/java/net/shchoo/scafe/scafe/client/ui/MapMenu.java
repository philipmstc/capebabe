package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.SkillStyle;
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
    /** Enter on a node: its menu (Craft / Craft AFK / Cancel) while it's open, else null. */
    private Node menuNode;
    private int menuIndex;

    /** The node menu's choices, top to bottom. */
    enum Choice { CRAFT, AFK, CANCEL }

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
        menuNode = null;
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
        return menuNode != null;
    }

    private Result menuKey(KeyStroke key, Player player) {
        switch (key.getKeyType()) {
            case ArrowUp -> menuIndex = Math.max(0, menuIndex - 1);
            case ArrowDown -> menuIndex = Math.min(Choice.values().length - 1, menuIndex + 1);
            case Escape -> menuNode = null;
            case Enter -> {
                Choice choice = Choice.values()[menuIndex];
                Position at = menuNode.position;
                menuNode = null;
                if (choice != Choice.CANCEL) {
                    player.craft(at, choice == Choice.AFK);
                    return Result.RELEASE;
                }
            }
            default -> {}
        }
        return Result.HANDLED;
    }

    @Override
    public Result handleKey(KeyStroke key, Player player, Board board) {
        if (menuNode != null) {
            return menuKey(key, player);
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
                    menuNode = node;
                    menuIndex = 0;
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

    /**
     * The node menu, just right of the node on the map:
     * <pre>
     * skillN{L}-T
     *   Craft skillN{L}-T
     *     2 x skill0{0}-1        (what it uses, if anything)
     *   Craft skillN{L}-T AFK    (half the xp; waits out respawns)
     *   Cancel
     * </pre>
     */
    @Override
    public void drawOnTop(Screen screen, Player player, Board board) {
        if (menuNode == null) {
            return;
        }
        String name = menuNode.recipe.product.name;
        String cost = menuNode.recipe.components.isEmpty() ? null : menuNode.recipe.components.entrySet().stream()
                .map(e -> e.getValue() + " x " + e.getKey().name).collect(java.util.stream.Collectors.joining(", "));
        java.util.List<String> lines = new java.util.ArrayList<>(java.util.List.of(name, "Craft " + name));
        if (cost != null) {
            lines.add(cost);
        }
        lines.add("Craft " + name + " AFK");
        lines.add("Cancel");
        int width = lines.stream().mapToInt(String::length).max().orElse(0) + 6;
        int x = Math.min(tileScreenX(menuNode.position.x(), player.x) + 3, TERMINAL_SIZE.getColumns() - width - 2);
        int y = Math.max(0, Math.min(tileScreenY(menuNode.position.y(), player.y) - 1, TERMINAL_SIZE.getRows() - lines.size() - 2));
        Rect box = new Rect(x, y, width + 2, lines.size() + 2);
        TextGraphics g = screen.newTextGraphics();
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        g.fillRectangle(new com.googlecode.lanterna.TerminalPosition(box.x(), box.y()),
                new com.googlecode.lanterna.TerminalSize(box.width(), box.height()), ' ');
        drawFrame(screen, box);
        int row = box.y() + 1;
        g.setForegroundColor(MapView.nodeColor(menuNode, player));
        g.enableModifiers(SGR.BOLD);
        g.putString(box.x() + 1, row++, name);
        g.disableModifiers(SGR.BOLD);
        for (Choice choice : Choice.values()) {
            boolean selected = choice.ordinal() == menuIndex;
            String text = switch (choice) {
                case CRAFT -> "Craft " + name;
                case AFK -> "Craft " + name + " AFK";
                case CANCEL -> "Cancel";
            };
            g.setForegroundColor(choice == Choice.CANCEL ? TextColor.ANSI.BLACK_BRIGHT
                    : selected ? TextColor.ANSI.WHITE_BRIGHT : TextColor.ANSI.DEFAULT);
            if (selected) {
                g.enableModifiers(SGR.BOLD);
            }
            g.putString(box.x() + 1, row++, (selected ? "> " : "  ") + text);
            g.disableModifiers(SGR.BOLD);
            if (choice == Choice.CRAFT && cost != null) {
                g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
                g.putString(box.x() + 5, row++, cost);
            }
        }
    }
}
