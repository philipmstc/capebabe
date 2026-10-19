package net.shchoo.scafe.scafe.protocol;

import net.shchoo.scafe.scafe.model.ContentBatch;
import net.shchoo.scafe.scafe.model.Direction;
import net.shchoo.scafe.scafe.model.Goal;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.WorldContent;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** What the server tells a client happened. Clients only change their view of the game in response to these. */
public sealed interface Event {
    record CharacterSnapshot(int id, String name, Position position, Map<Integer, Long> xp,
                             Map<Integer, Integer> inventory, List<GoalDef> goals, Set<Integer> triedNodes,
                             Position lastActionAt, int hp, int maxHp, Direction facing, int colorIndex,
                             boolean running, boolean autoInvestigate) {}

    record GoalDef(int skillId, int level, int productId) {
        public static GoalDef of(Goal goal) {
            return new GoalDef(goal.skill().id, goal.level(), goal.product().id);
        }

        public Goal toGoal(WorldContent content) {
            return new Goal(content.skill(skillId), level, content.product(productId));
        }
    }

    enum EntityKind { PLAYER, MONSTER }

    /**
     * Another player or a monster, as you see them. Monster ids are negative, player ids positive.
     * For players, total level = (number of skills in the world) + levelsGained, so it stays right when new
     * skills appear without the server re-sending everyone. For monsters, monsterTypeId says which kind.
     */
    record EntityInfo(int id, EntityKind kind, String name, Position position, Direction facing, int levelsGained,
                      int monsterTypeId, int hp, int maxHp, int colorIndex) {
        public static EntityInfo player(int id, String name, Position position, Direction facing, int levelsGained,
                                        int hp, int maxHp, int colorIndex) {
            return new EntityInfo(id, EntityKind.PLAYER, name, position, facing, levelsGained, 0, hp, maxHp, colorIndex);
        }

        public static EntityInfo monster(int id, String name, Position position, int monsterTypeId, int hp, int maxHp) {
            return new EntityInfo(id, EntityKind.MONSTER, name, position, Direction.DOWN, 0, monsterTypeId, hp, maxHp, 0);
        }

        public boolean isMonster() {
            return kind == EntityKind.MONSTER;
        }

        public int totalLevel(WorldContent content) {
            return content.skills.size() + levelsGained;
        }

        public EntityInfo at(Position newPosition, Direction newFacing) {
            return new EntityInfo(id, kind, name, newPosition, newFacing, levelsGained, monsterTypeId, hp, maxHp, colorIndex);
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
    /** Moved and/or turned. For your own character, sent for every step the server takes for you (so prediction can match). */
    record EntityMoved(int id, Position position, Direction facing) implements Event {}
    record EntityLeft(int id) implements Event {}
    /** Something you can see about another player or monster changed (levelled up, took damage...). */
    record EntityUpdated(EntityInfo entity) implements Event {}
    /** Your queued steps were thrown away (e.g. you started an action); you're at {@code position}. */
    record StepsCleared(Position position, Direction facing) implements Event {}

    /** The action restarts every {@code durationTicks} until {@link ActionStopped}. */
    record ActionStarted(int nodeId, String label, long startTick, int durationTicks) implements Event {}
    record ActionStopped() implements Event {}

    record XpGained(int skillId, long amount, long totalXp, Position at) implements Event {}
    record LevelUp(int skillId, int from, int to) implements Event {}
    record InventoryChanged(int productId, int count) implements Event {}
    record GoalAdded(GoalDef goal) implements Event {}
    record NodeTried(int nodeId) implements Event {}
    record HomeChanged(Position lastActionAt) implements Event {}
    record NodeChanged(NodeStatus status) implements Event {}

    record FightStarted(int monsterId) implements Event {}
    record FightEnded() implements Event {}
    /** Someone (you, a monster, another player) was hit for {@code damage}; 0 = a miss. */
    record Hit(int targetId, int damage, Position at) implements Event {}
    record HpChanged(int hp, int maxHp) implements Event {}
    record ColorChanged(int colorIndex) implements Event {}
    record RunningChanged(boolean running) implements Event {}
    record AutoInvestigateChanged(boolean on) implements Event {}

    /** Your name and password are fine; next send EnterWorld (with the world's password if it needs one). */
    record LoginAccepted(boolean worldPasswordNeeded, boolean newAccount) implements Event {}
    record LoginRefused(String reason) implements Event {}

    record Message(String text) implements Event {}
}
