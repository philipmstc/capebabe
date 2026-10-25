package net.shchoo.scafe.scafe.client;

import net.shchoo.scafe.scafe.client.ui.Clicks;
import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.Skill;
import net.shchoo.scafe.scafe.model.WorldContent;

import java.util.*;

public class Board {
    /** The client's copy of the world's content. */
    public final WorldContent content;
    /** Live view of the world's skills. */
    public final List<Skill> skills;
    public final MessageLog log = new MessageLog();
    /** What can be clicked this frame (see {@link Clicks}). */
    public final Clicks clicks = new Clicks();
    /** The screen cell under the mouse (-1: not over the window), for hover. */
    public int mouseX = -1;
    public int mouseY = -1;
    /** Nothing's drawn over what's under the mouse (no popup, no bank), as of the last frame: hover may show it. */
    public boolean mouseUncovered;

    /** Works out {@link #mouseUncovered} from what was drawn (call after a frame, or when the mouse moves). */
    public void mouseSettled() {
        mouseUncovered = clicks.layerAt(mouseX, mouseY) == Clicks.BASE;
    }

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
}
