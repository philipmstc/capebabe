package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.Popup;
import net.shchoo.scafe.scafe.client.SkillStyle;
import net.shchoo.scafe.scafe.model.MonsterType;
import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.protocol.Event;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.Symbols;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.screen.Screen;
import java.util.List;

import static net.shchoo.scafe.scafe.client.ui.Layout.*;

/** The map, the action progress bar under it, the home arrow and popups. */
public class MapView extends Widget {

    @Override
    public void draw(Screen screen, Player player, Board board) {
        drawBox(screen, BOARD_POS_X, BOARD_POS_Y, BOARD_WIDTH, BOARD_HEIGHT, 2);
        drawBox(screen, BOARD_POS_X, BOARD_POS_Y + 2 * BOARD_HEIGHT + 1, BOARD_POS_X + 2 * BOARD_WIDTH, 2, 1);
        drawProgressBar(player, screen);
        TextGraphics coords = screen.newTextGraphics();
        coords.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        coords.putString(BOARD_POS_X + 2, BOARD_POS_Y, "(" + player.x + "," + player.y + ")" + (player.running ? " run" : ""));
        if (player.content.combatSkill() != null) {
            String hp = "hp " + player.hp + "/" + player.maxHp;
            coords.setForegroundColor(player.hp * 3 <= player.maxHp ? TextColor.ANSI.RED_BRIGHT : TextColor.ANSI.GREEN);
            coords.putString(BOARD_POS_X + 2 * BOARD_WIDTH - 1 - hp.length(), BOARD_POS_Y, hp);
        }
        for (int i = 0; i < BOARD_HEIGHT; i++) {
            for (int j = 0; j < BOARD_WIDTH; j++) {
                drawTile(player, board, screen, viewToWorldX(j, player.x), viewToWorldY(i, player.y), false);
            }
        }
        drawHomeIndicator(player, screen);
        drawPopups(player, screen);
    }

    /** The action you're doing (green, filling up), or the monster you're fighting (red = its hp left). */
    private static void drawProgressBar(Player player, Screen screen) {
        Event.EntityInfo monster = player.fightingMonster();
        double fraction;
        String label;
        TextColor fill;
        if (monster != null) {
            fraction = monster.maxHp() == 0 ? 0 : (double) monster.hp() / monster.maxHp();
            label = monster.name() + " " + monster.hp() + "/" + monster.maxHp();
            fill = TextColor.ANSI.RED;
        } else if (player.currentAction != null) {
            fraction = player.actionFraction();
            label = player.currentAction.label();
            fill = TextColor.ANSI.GREEN;
        } else {
            return;
        }
        int width = 2 * BOARD_WIDTH - 1;
        int y = BOARD_POS_Y + 2 * BOARD_HEIGHT + 2;
        int filled = (int) Math.round(fraction * width);
        if (label.length() > width) {
            label = label.substring(0, width);
        }
        int labelStart = (width - label.length()) / 2;
        for (int i = 0; i < width; i++) {
            int l = i - labelStart;
            char c = l >= 0 && l < label.length() ? label.charAt(l) : ' ';
            TextColor bg = i < filled ? fill : TextColor.ANSI.DEFAULT;
            screen.setCharacter(BOARD_POS_X + 1 + i, y, TextCharacter.fromCharacter(c, TextColor.ANSI.WHITE_BRIGHT, bg)[0]);
        }
    }

    private static void drawPopups(Player player, Screen screen) {
        TextGraphics graphics = screen.newTextGraphics();
        graphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
        for (Popup popup : player.popups) {
            Position at = popup.at();
            if (!clampToView(at, player.x, player.y).equals(at)) {
                continue;
            }
            String text = popup.text.length() > 2 * BOARD_WIDTH - 1 ? popup.text.substring(0, 2 * BOARD_WIDTH - 1) : popup.text;
            int tileX = BOARD_POS_X + 1 + (at.x() - player.x + VIEW_CENTER_COLUMN) * 2;
            int tileY = BOARD_POS_Y + 1 + (at.y() - player.y + VIEW_CENTER_ROW) * 2;
            int y = Math.max(BOARD_POS_Y + 1, tileY - popup.rise());
            int x = Math.max(BOARD_POS_X + 1, Math.min(BOARD_POS_X + 2 * BOARD_WIDTH - text.length(), tileX - text.length() / 2));
            graphics.setForegroundColor(popup.color);
            graphics.setBackgroundColor(popup.background);
            graphics.enableModifiers(SGR.BOLD);
            graphics.putString(x, y, text);
        }
    }

    /** Arrow on the map border pointing at the last place the player made something (or the origin), when it's off-screen. */
    private static void drawHomeIndicator(Player player, Screen screen) {
        Position home = player.lastActionAt != null ? player.lastActionAt : new Position(0, 0);
        if (clampToView(home, player.x, player.y).equals(home)) {
            return;
        }
        int centerX = BOARD_POS_X + 1 + VIEW_CENTER_COLUMN * 2;
        int centerY = BOARD_POS_Y + 1 + VIEW_CENTER_ROW * 2;
        int halfWidth = BOARD_WIDTH, halfHeight = BOARD_HEIGHT;
        double dx = (home.x() - player.x) * 2.0;
        double dy = (home.y() - player.y) * 2.0;
        double scale = Math.min(dx == 0 ? Double.MAX_VALUE : halfWidth / Math.abs(dx),
                dy == 0 ? Double.MAX_VALUE : halfHeight / Math.abs(dy));
        int x = (int) Math.round(centerX + dx * scale);
        int y = (int) Math.round(centerY + dy * scale);

        char arrow;
        if (Math.abs(dx) * halfHeight >= Math.abs(dy) * halfWidth) {
            x = dx > 0 ? BOARD_POS_X + 2 * BOARD_WIDTH : BOARD_POS_X;
            y = Math.max(BOARD_POS_Y + 1, Math.min(BOARD_POS_Y + 2 * BOARD_HEIGHT - 1, y));
            arrow = dx > 0 ? Symbols.ARROW_RIGHT : Symbols.ARROW_LEFT;
        } else {
            y = dy > 0 ? BOARD_POS_Y + 2 * BOARD_HEIGHT : BOARD_POS_Y;
            x = Math.max(BOARD_POS_X + 1, Math.min(BOARD_POS_X + 2 * BOARD_WIDTH - 1, x));
            arrow = dy > 0 ? Symbols.ARROW_DOWN : Symbols.ARROW_UP;
        }
        TextColor color = player.lastActionAt != null ? TextColor.ANSI.YELLOW_BRIGHT : TextColor.ANSI.WHITE_BRIGHT;
        screen.setCharacter(x, y, TextCharacter.fromCharacter(arrow, color, TextColor.ANSI.DEFAULT)[0]);
    }

    public static void drawTile(Player player, Board board, Screen screen, int worldX, int worldY, boolean highlighted) {
        Node node = board.nodeAt(worldX, worldY);
        boolean isPlayerPosition = player.x == worldX && player.y == worldY;
        boolean hasAction = node != null;
        boolean isOrigin = worldX == 0 && worldY == 0;
        char result = isPlayerPosition
                ? player.facing.arrow
                : hasAction
                ? SkillStyle.getSymbolForSkill(node.skill())
                : isOrigin ? 'o' : Symbols.BULLET;

        TextColor fg = isPlayerPosition
                ? SkillStyle.playerColor(player.colorIndex)
                : hasAction
                ? nodeColor(node, player)
                : isOrigin ? TextColor.ANSI.WHITE_BRIGHT : TextColor.ANSI.BLACK_BRIGHT;
        if (!isPlayerPosition) {
            List<Event.EntityInfo> here = player.othersAt(new Position(worldX, worldY));
            Event.EntityInfo monster = here.stream().filter(Event.EntityInfo::isMonster).findFirst().orElse(null);
            MonsterType type = monster == null ? null : player.content.monster(monster.monsterTypeId());
            if (type != null) {
                result = type.symbol;
                fg = SkillStyle.getColorForMonster(type);
            } else if (!here.isEmpty()) {
                result = here.get(0).facing().arrow;
                fg = SkillStyle.playerColor(here.get(0).colorIndex());
            }
        }

        int screenX = BOARD_POS_X + 1 + (worldX - player.x + VIEW_CENTER_COLUMN) * 2;
        int screenY = BOARD_POS_Y + 1 + (worldY - player.y + VIEW_CENTER_ROW) * 2;
        TextColor bg = highlighted ? TextColor.ANSI.BLACK_BRIGHT : TextColor.ANSI.DEFAULT;
        if (highlighted) {
            if (fg == TextColor.ANSI.BLACK_BRIGHT) {
                fg = TextColor.ANSI.WHITE;
            }
            screen.setCharacter(screenX - 1, screenY, TextCharacter.fromCharacter(' ', fg, bg)[0]);
            screen.setCharacter(screenX + 1, screenY, TextCharacter.fromCharacter(' ', fg, bg)[0]);
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

    private static void drawBox(Screen screen,
                                int xStart,
                                int yStart,
                                int width,
                                int height,
                                int scale) {
        TextGraphics graphics = screen.newTextGraphics();
        TerminalPosition topLeft = new TerminalPosition(xStart, yStart);
        TerminalPosition topRight = new TerminalPosition(xStart + scale * width, yStart);
        TerminalPosition bottomLeft = new TerminalPosition(xStart, yStart + scale * height);
        TerminalPosition bottomRight = new TerminalPosition(xStart + scale * width, yStart + scale * height);
        graphics.setForegroundColor(TextColor.ANSI.DEFAULT);
        graphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
        graphics.drawLine(topLeft, topRight, Symbols.SINGLE_LINE_HORIZONTAL);
        graphics.drawLine(topLeft, bottomLeft, Symbols.SINGLE_LINE_VERTICAL);
        graphics.drawLine(topRight, bottomRight, Symbols.SINGLE_LINE_VERTICAL);
        graphics.drawLine(bottomLeft, bottomRight, Symbols.SINGLE_LINE_HORIZONTAL);
        graphics.setCharacter(topLeft, Symbols.SINGLE_LINE_TOP_LEFT_CORNER);
        graphics.setCharacter(topRight, Symbols.SINGLE_LINE_TOP_RIGHT_CORNER);
        graphics.setCharacter(bottomLeft, Symbols.SINGLE_LINE_BOTTOM_LEFT_CORNER);
        graphics.setCharacter(bottomRight, Symbols.SINGLE_LINE_BOTTOM_RIGHT_CORNER);
    }
}
