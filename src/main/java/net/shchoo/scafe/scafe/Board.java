package net.shchoo.scafe.scafe;

import java.util.*;

public class Board {
    public record Position(int x, int y) {}

    public static final int VIEW_CENTER_COLUMN = Main.BOARD_WIDTH / 2;
    public static final int VIEW_CENTER_ROW = Main.BOARD_HEIGHT / 2;

    /** The client's copy of the world's content. */
    public final WorldContent content;
    /** Live view of the world's skills. */
    public final List<Skill> skills;
    public final MessageLog log = new MessageLog();

    public Board(WorldContent content) {
        this.content = content;
        this.skills = content.skills;
    }

    public Node nodeAt(int worldX, int worldY) {
        return content.nodeAt(new Position(worldX, worldY));
    }

    public Node nodeAt(Player player) {
        return nodeAt(player.x, player.y);
    }

    public static int viewToWorldX(int viewColumn, int centerX) {
        return centerX + viewColumn - VIEW_CENTER_COLUMN;
    }

    public static int viewToWorldY(int viewRow, int centerY) {
        return centerY + viewRow - VIEW_CENTER_ROW;
    }

    public static Position clampToView(Position p, int centerX, int centerY) {
        int minX = viewToWorldX(0, centerX), maxX = viewToWorldX(Main.BOARD_WIDTH - 1, centerX);
        int minY = viewToWorldY(0, centerY), maxY = viewToWorldY(Main.BOARD_HEIGHT - 1, centerY);
        return new Position(Math.max(minX, Math.min(maxX, p.x())), Math.max(minY, Math.min(maxY, p.y())));
    }
}
