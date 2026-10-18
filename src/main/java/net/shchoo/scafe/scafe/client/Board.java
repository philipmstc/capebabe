package net.shchoo.scafe.scafe.client;

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
