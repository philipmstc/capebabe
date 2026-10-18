package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.model.Position;

/** A character's action in progress: performs every {@link Node#durationTicks()} ticks while it still can. */
public class ActionProgress {
    final Node node;
    private final Position startedAt;
    private final long startTick;
    private int ticksDone = 0;

    public ActionProgress(Node node, GameCharacter character, long startTick) {
        this.node = node;
        this.startedAt = character.position();
        this.startTick = startTick;
    }

    /** Returns false once finished or interrupted. Does nothing on the tick it started. */
    public boolean tick(GameCharacter character, long tick) {
        if (tick == startTick) {
            return true;
        }
        if (!character.position().equals(startedAt)) {
            character.say("Interrupted.");
            return false;
        }
        ticksDone++;
        if (ticksDone >= node.durationTicks()) {
            NodeStates nodes = character.nodes();
            if (nodes.isDepleted(node)) {
                character.say("The " + node.label() + " ran out.");
                return false;
            }
            if (character.perform(node) && nodes.consume(node)) {
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
