package net.shchoo.scafe.scafe.client;

import net.shchoo.scafe.scafe.model.Building;
import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.Recipe;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Nodes you're heading for, one per recipe, picked in the Skills tab -- and apart from them, one building, picked
 * from the Map tab's points of interest. Client-only: not saved or sent anywhere.
 */
public class Waypoints {
    private final Set<Node> nodes = new LinkedHashSet<>();
    private Building building;

    public Collection<Node> nodes() {
        return Collections.unmodifiableSet(nodes);
    }

    /** The building you're heading for, or null. */
    public Building building() {
        return building;
    }

    public boolean isEmpty() {
        return nodes.isEmpty() && building == null;
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

    /** Clears the building waypoint if it's {@code target}, else heads for {@code target} instead. */
    public void toggle(Building target, Player player) {
        if (target.equals(building)) {
            building = null;
            GameLog.say("Waypoint cleared.");
            return;
        }
        building = target;
        Position at = player.nearestTile(target);
        GameLog.say("Waypoint: " + target.kind().label + " at (" + at.x() + "," + at.y() + "), "
                + player.stepsTo(at) + " steps away.");
    }

    /** Whether there's a waypoint other than {@code recipe}'s (a building's counts). */
    public boolean hasOthers(Recipe recipe) {
        return building != null || nodes.stream().anyMatch(node -> !node.recipe.equals(recipe));
    }

    /** Clears every waypoint but {@code recipe}'s. */
    public void clearOthers(Recipe recipe) {
        boolean cleared = nodes.removeIf(node -> !node.recipe.equals(recipe)) | building != null;
        building = null;
        if (cleared) {
            GameLog.say("Other waypoints cleared.");
        }
    }

    /** Called every frame: you've reached the waypoints you're facing. */
    void update(Position ahead) {
        if (nodes.removeIf(node -> node.position.equals(ahead))) {
            GameLog.say("You've reached your waypoint.");
        }
        if (building != null && building.solidTiles().contains(ahead)) {
            GameLog.say("You've reached the " + building.kind().label + ".");
            building = null;
        }
    }
}
