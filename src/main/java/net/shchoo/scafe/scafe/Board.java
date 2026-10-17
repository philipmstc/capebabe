package net.shchoo.scafe.scafe;

import java.util.*;

public class Board {
    public abstract static class Action {
        public static final int DEFAULT_DURATION_TICKS = 20;

        public abstract Skill perform(Player player);
        public abstract Skill forWhich();

        /** Whether starting this would succeed; if not, perform() is called right away to report why. */
        public boolean canPerform(Player player) {
            return true;
        }

        public int durationTicks() {
            return DEFAULT_DURATION_TICKS;
        }

        public String progressLabel() {
            return forWhich().name;
        }

        public List<String> describe() {
            return List.of(forWhich().name);
        }

        public boolean isLockedFor(Player player) {
            return false;
        }

        /** True once the player has tried this while it was locked for them. */
        public boolean wasAttempted() {
            return false;
        }

        public Optional<Goal> goal() {
            return Optional.empty();
        }
    }

    public record Position(int x, int y) {}

    public static final int VIEW_CENTER_COLUMN = Main.BOARD_WIDTH / 2;
    public static final int VIEW_CENTER_ROW = Main.BOARD_HEIGHT / 2;

    public Board(List<Skill> skills) {
        this.skills = skills;
    }

    private final Map<Position, List<Action>> actions = new HashMap<>();
    public List<Skill> skills;
    public final MessageLog log = new MessageLog();

    public List<Action> availableActions(int worldX, int worldY) {
        return actions.getOrDefault(new Position(worldX, worldY), List.of());
    }

    public List<Action> availableActions(Player player) {
        return availableActions(player.x, player.y);
    }

    public void setActions(int worldX, int worldY, List<Action> tileActions) {
        actions.put(new Position(worldX, worldY), tileActions);
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
