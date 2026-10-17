package net.shchoo.scafe.scafe;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** What the server tells a client happened. Clients only change their view of the game in response to these. */
public sealed interface Event {
    record CharacterSnapshot(int id, String name, Board.Position position, Map<Integer, Long> xp,
                             Map<Integer, Integer> inventory, List<GoalDef> goals, Set<Integer> triedNodes,
                             Board.Position lastActionAt) {}

    record GoalDef(int skillId, int level, int productId) {}

    /**
     * Another player as you see them. Total level = (number of skills in the world) + levelsGained, so it stays
     * right when new skills appear without the server re-sending everyone.
     */
    record EntityInfo(int id, String name, Board.Position position, int levelsGained) {
        public int totalLevel(WorldContent content) {
            return content.skills.size() + levelsGained;
        }

        EntityInfo at(Board.Position newPosition) {
            return new EntityInfo(id, name, newPosition, levelsGained);
        }
    }

    /** A node's live state; nodes not mentioned are full, respawned and unused. */
    record NodeStatus(int nodeId, int charges, boolean depleted, int users) {}

    record Welcome(String worldName, long tick, List<ContentBatch> content, CharacterSnapshot you,
                   List<EntityInfo> others, List<NodeStatus> nodes) implements Event {}

    /** Sent at the start of every server tick. */
    record Tick(long tick) implements Event {}

    record ContentAdded(ContentBatch batch) implements Event {}

    record EntityEntered(EntityInfo entity) implements Event {}
    record EntityMoved(int id, Board.Position position) implements Event {}
    record EntityLeft(int id) implements Event {}
    /** Something you can see about another player changed (e.g. they levelled up). */
    record EntityUpdated(EntityInfo entity) implements Event {}
    /** Your queued steps were thrown away (e.g. you started an action); you're at {@code position}. */
    record StepsCleared(Board.Position position) implements Event {}

    /** The action restarts every {@code durationTicks} until {@link ActionStopped}. */
    record ActionStarted(int nodeId, String label, long startTick, int durationTicks) implements Event {}
    record ActionStopped() implements Event {}

    record XpGained(int skillId, long amount, long totalXp, Board.Position at) implements Event {}
    record LevelUp(int skillId, int from, int to) implements Event {}
    record InventoryChanged(int productId, int count) implements Event {}
    record GoalAdded(GoalDef goal) implements Event {}
    record NodeTried(int nodeId) implements Event {}
    record HomeChanged(Board.Position lastActionAt) implements Event {}
    record NodeChanged(NodeStatus status) implements Event {}

    record Message(String text) implements Event {}
}
