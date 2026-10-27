package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.SkillStyle;
import net.shchoo.scafe.scafe.model.Building;
import net.shchoo.scafe.scafe.model.MonsterType;
import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.Recipe;
import net.shchoo.scafe.scafe.protocol.Event;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.screen.Screen;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static net.shchoo.scafe.scafe.client.ui.Layout.fit;

/** Always on screen: what's on the tile you're facing, or on the hovered tile while something is hovering. */
public class MapDetailView extends Widget {
    /** The hovered tile, or null to show the tile ahead of you. */
    private final Function<Player, Position> hovered;

    public MapDetailView(Rect bounds, Function<Player, Position> hovered) {
        super(bounds);
        this.hovered = hovered;
    }

    @Override
    public void draw(Screen screen, Player player, Board board) {
        drawFrame(screen);
        Position hover = hovered.apply(player);
        Position target = hover != null ? hover : player.ahead();
        boolean ahead = target.equals(player.ahead());

        TextGraphics g = screen.newTextGraphics();
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        g.putString(textX(), bounds().y(), (ahead ? "Ahead" : "Tile") + " (" + target.x() + "," + target.y() + ")");
        if (hover == null) {
            g.putString(textX(), hintRow(), "Map tab, [Down]: inspect tiles");
        }

        int row = firstRow();
        int last = hintRow() - 1;
        for (Event.EntityInfo other : player.othersAt(target)) {
            if (row > last) {
                return;
            }
            MonsterType type = other.isMonster() ? player.content.monster(other.monsterTypeId()) : null;
            g.setForegroundColor(type != null ? SkillStyle.getColorForMonster(type) : SkillStyle.playerColor(other.colorIndex()));
            g.setCharacter(textX(), row, type != null ? type.symbol : other.facing().arrow);
            g.setForegroundColor(TextColor.ANSI.DEFAULT);
            g.putString(textX() + 2, row, fit(other.name(), textWidth() - 2));
            g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            String details = (type != null ? "Lv. " + type.level : "total Lv. " + other.totalLevel(player.content))
                    + "  hp " + other.hp() + "/" + other.maxHp();
            g.putString(textX() + 3 + other.name().length(), row++,
                    fit(details, Math.max(0, textWidth() - 3 - other.name().length())));
            if (type != null) {
                boolean nextTo = player.stepsTo(target) <= 1;
                List<String> lines = List.of(nextTo ? "  [f] fight" : "  get next to it to fight",
                        "  drops found: " + type.drops.size() + ", next 1 in " + type.nextDropOneIn());
                for (String line : lines) {
                    if (row <= last) {
                        g.putString(textX() + 2, row++, fit(line, textWidth() - 2));
                    }
                }
            }
        }
        Building building = player.content.buildingAt(target);
        if (building != null && building.walkable(target) && row <= last) {
            g.setForegroundColor(TextColor.ANSI.YELLOW);
            g.putString(textX(), row, fit("Train tracks", textWidth()));
            return;
        }
        if (building != null && row <= last) {
            g.setForegroundColor(TextColor.ANSI.YELLOW_BRIGHT);
            g.putString(textX(), row++, fit(building.kind().label, textWidth()));
            g.setForegroundColor(TextColor.ANSI.DEFAULT);
            if (row <= last) {
                g.putString(textX() + 2, row++, fit(player.ahead().equals(target)
                        ? building.kind().action : "walk up to it and press q", textWidth() - 2));
            }
            return;
        }
        Node node = board.nodeAt(target.x(), target.y());
        if (node == null) {
            if (row == firstRow()) {
                g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
                g.putString(textX(), row, "Nothing here");
            }
            return;
        }
        if (row > last) {
            return;
        }
        g.setForegroundColor(MapView.nodeColor(node, player));
        g.setCharacter(textX(), row, SkillStyle.getSymbolForSkill(node.skill()));
        g.setForegroundColor(TextColor.ANSI.DEFAULT);
        List<String> lines = new ArrayList<>(describe(node));
        Event.NodeStatus status = player.status(node);
        int firstStatus = lines.size();
        if (node.maxCharges != Node.UNLIMITED) {
            lines.add(status.depleted() ? "  empty -- respawning" : "  " + status.charges() + "/" + node.maxCharges + " left");
        }
        int capacity = node.capacity(player.capacityBonus);
        if (capacity != Node.UNLIMITED || status.users() > 0) {
            lines.add("  in use: " + status.users() + (capacity == Node.UNLIMITED ? "" : "/" + capacity));
        }
        boolean locked = player.isLockedFor(node);
        if (locked) {
            lines.add(player.hasTried(node) ? "  locked -- g to add goal" : "  locked");
        }
        for (int i = 0; i < lines.size() && row <= last; i++) {
            if (i == firstStatus) {
                g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            }
            if (locked && i == lines.size() - 1) {
                g.setForegroundColor(TextColor.ANSI.RED);
            }
            g.putString(textX() + 2, row++, fit(lines.get(i), textWidth() - 2));
        }
    }

    static List<String> describe(Node node) {
        Recipe recipe = node.recipe;
        return List.of(
                recipe.skill.name + " [Lv. " + recipe.levelNeeded + "]",
                "  makes " + recipe.product.name + " (+" + recipe.expGained + "xp)",
                "  needs " + recipe.describeComponents());
    }
}
