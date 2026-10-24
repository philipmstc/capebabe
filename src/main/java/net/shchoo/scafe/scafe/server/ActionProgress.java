package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.model.Direction;
import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.protocol.Event;

/**
 * A character's action in progress: performs every {@link Node#durationTicks()} ticks while it still can.
 * AFK ({@link #afk}): half the xp, and when the node runs out it waits beside it for it to come back, then
 * carries on, instead of stopping.
 */
public class ActionProgress {
    final Node node;
    final boolean afk;
    private final Position startedAt;
    private final Direction startedFacing;
    private final long startTick;
    private int ticksDone = 0;
    /** AFK, the node's empty: waiting for it to come back. */
    private boolean waiting;

    public ActionProgress(Node node, GameCharacter character, long startTick) {
        this(node, character, startTick, false);
    }

    public ActionProgress(Node node, GameCharacter character, long startTick, boolean afk) {
        this.node = node;
        this.afk = afk;
        this.startedAt = character.position();
        this.startedFacing = character.facing;
        this.startTick = startTick;
    }

    /** What the progress bar says. */
    static String label(Node node, boolean afk) {
        return node.label() + (afk ? " (AFK)" : "");
    }

    /** Returns false once finished or interrupted. Does nothing on the tick it started. */
    public boolean tick(GameCharacter character, long tick) {
        if (tick == startTick) {
            return true;
        }
        if (!character.position().equals(startedAt) || character.facing != startedFacing) {
            character.say(Event.MessageKind.ROUTINE, "Interrupted.");
            return false;
        }
        NodeStates nodes = character.nodes();
        if (afk && nodes.isDepleted(node)) {
            if (!waiting) {
                waiting = true;
                ticksDone = 0;
                character.say(Event.MessageKind.ROUTINE, "The " + node.label() + " ran out -- waiting for it to come back.");
                character.emit(new Event.ActionStarted(node.id, "waiting: " + node.label() + " (AFK)", tick,
                        Math.max(1, nodes.respawnIn(node))));
            }
            return true;
        }
        if (waiting) {
            waiting = false;
            ticksDone = 0;
            character.emit(new Event.ActionStarted(node.id, label(node, true), tick, node.durationTicks()));
            if (!character.canPerform(node)) {
                character.say("Can't make any more " + node.label() + ".");
                return false;
            }
            return true;
        }
        ticksDone++;
        if (ticksDone >= node.durationTicks()) {
            if (nodes.isDepleted(node)) {
                character.say("The " + node.label() + " ran out.");
                return false;
            }
            if (character.perform(node, afk) && nodes.consume(node) && !afk) {
                character.say("The " + node.label() + " ran out.");
                return false;
            }
            if (!character.canPerform(node)) {
                character.say("Can't make any more " + node.label() + ".");
                return false;
            }
            ticksDone = 0;
        }
        return true;
    }
}
