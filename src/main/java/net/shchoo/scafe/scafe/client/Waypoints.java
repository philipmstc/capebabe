package net.shchoo.scafe.scafe.client;

import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.Recipe;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Nodes you're heading for, one per recipe, picked in the Skills tab. Client-only: not saved or sent anywhere. */
public class Waypoints {
    private final Set<Node> nodes = new LinkedHashSet<>();

    public Collection<Node> nodes() {
        return Collections.unmodifiableSet(nodes);
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    public boolean has(Recipe recipe) {
        return nodes.stream().anyMatch(node -> node.recipe.equals(recipe));
    }

    /** Clears the waypoint for {@code recipe} if there is one, else adds one to the nearest node making it. */
    public void toggle(Recipe recipe, Player player) {
        if (nodes.removeIf(node -> node.recipe.equals(recipe))) {
            GameLog.say("Waypoint cleared.");
            return;
        }
        Node node = player.nearestNode(recipe);
        if (node == null) {
            GameLog.say("No known node makes " + recipe.product.name + " yet.");
            return;
        }
        nodes.add(node);
        GameLog.say("Waypoint: " + recipe.product.name + " at (" + node.position.x() + "," + node.position.y() + "), "
                + player.stepsTo(node.position) + " steps away.");
    }

    /** Called every frame: you've reached the waypoints you're facing. */
    void update(Position ahead) {
        if (nodes.removeIf(node -> node.position.equals(ahead))) {
            GameLog.say("You've reached your waypoint.");
        }
    }
}
