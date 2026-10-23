package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.Recipe;
import net.shchoo.scafe.scafe.model.WorldContent;

/** Test help: a node for {@code recipe} at {@code at}, added to the running server's world. */
final class WorldGeneratorAccess {
    private WorldGeneratorAccess() {}

    static Node place(GameServer server, Recipe recipe, Position at) {
        WorldContent scratch = server.world.content.copy();
        Node node = new Node(scratch.allocateId(), at, scratch.recipe(recipe.id), Node.UNLIMITED, Node.UNLIMITED, 0);
        scratch.addNode(node);
        server.applyContent(scratch.diff(server.world.content));
        return node;
    }
}
