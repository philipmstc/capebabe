package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.protocol.Event;
import net.shchoo.scafe.scafe.protocol.GameClock;

import java.util.*;
import java.util.function.Consumer;

/** Server-side runtime state of nodes: who's using them, charges left, respawn timers. Not saved. */
public class NodeStates {

    static class State {
        final Node node;
        int charges;
        int respawnIn;
        final Set<Integer> users = new LinkedHashSet<>();
        /** Those of {@link #users} crafting AFK: someone crafting for real can bump one off a full node. */
        final Set<Integer> afk = new LinkedHashSet<>();

        State(Node node) {
            this.node = node;
            this.charges = node.maxCharges;
        }

        boolean depleted() {
            return respawnIn > 0;
        }

        boolean isDefault() {
            return !depleted() && users.isEmpty() && charges == node.maxCharges;
        }

        Event.NodeStatus status() {
            return new Event.NodeStatus(node.id, charges, depleted(), users.size());
        }
    }

    private final Map<Integer, State> states = new HashMap<>();
    private final List<State> justDepleted = new ArrayList<>();
    private final Consumer<Event> broadcast;
    /** What every node's capacity is up by just now ({@link Node#capacityBonus} of the players online). */
    private int bonus;
    /** AFK users bumped off a full node this tick, for the server to stop: [character id, node id]. */
    private final List<int[]> bumped = new ArrayList<>();

    public NodeStates(Consumer<Event> broadcast) {
        this.broadcast = broadcast;
    }

    /** {@code online} players in the world now: sets the capacity bonus; true if it changed. */
    boolean setOnline(int online) {
        int now = Node.capacityBonus(online);
        if (now == bonus) {
            return false;
        }
        bonus = now;
        return true;
    }

    int bonus() {
        return bonus;
    }

    private State of(Node node) {
        return states.computeIfAbsent(node.id, id -> new State(node));
    }

    private void changed(State state) {
        broadcast.accept(new Event.NodeChanged(state.status()));
        if (state.isDefault()) {
            states.remove(state.node.id);
        }
    }

    /** Why this character can't start using the node right now, or null if it can. */
    String whyNot(Node node, int characterId) {
        State state = states.get(node.id);
        if (state == null) {
            return null;
        }
        if (state.depleted()) {
            return "It's empty -- back in about " + (state.respawnIn * GameClock.TICK_MILLIS / 1000 + 1) + "s.";
        }
        int capacity = node.capacity(bonus);
        if (capacity != Node.UNLIMITED && state.users.size() >= capacity && !state.users.contains(characterId)) {
            return "Someone else is using that (" + state.users.size() + "/" + capacity + ").";
        }
        return null;
    }

    /**
     * Full, and someone wants it for real (not AFK): bumps one of its AFK users off to make room (the server stops
     * them, {@link #drainBumped}). Returns whether there was one to bump.
     */
    boolean bumpAfk(Node node) {
        State state = states.get(node.id);
        if (state == null || state.afk.isEmpty()) {
            return false;
        }
        int who = state.afk.iterator().next();
        state.afk.remove(who);
        state.users.remove(who);
        bumped.add(new int[]{who, node.id});
        changed(state);
        return true;
    }

    /** AFK users bumped this tick: [character id, node id]. */
    List<int[]> drainBumped() {
        List<int[]> out = List.copyOf(bumped);
        bumped.clear();
        return out;
    }

    /** Ticks until an empty node is back (0 if it isn't empty). */
    int respawnIn(Node node) {
        State state = states.get(node.id);
        return state != null && state.depleted() ? state.respawnIn : 0;
    }

    boolean isDepleted(Node node) {
        State state = states.get(node.id);
        return state != null && state.depleted();
    }

    void join(Node node, int characterId, boolean afk) {
        State state = of(node);
        if (afk) {
            state.afk.add(characterId);
        }
        if (state.users.add(characterId)) {
            changed(state);
        }
    }

    void leave(Node node, int characterId) {
        State state = states.get(node.id);
        if (state != null) {
            state.afk.remove(characterId);
            if (state.users.remove(characterId)) {
                changed(state);
            }
        }
    }

    /** One successful use. Returns true if that emptied the node. */
    boolean consume(Node node) {
        if (node.maxCharges == Node.UNLIMITED) {
            return false;
        }
        State state = of(node);
        state.charges--;
        boolean emptied = state.charges <= 0;
        if (emptied) {
            state.charges = 0;
            state.respawnIn = Math.max(1, node.respawnTicks);
            justDepleted.add(state);
        }
        changed(state);
        return emptied;
    }

    /** Character ids still using nodes that ran out this tick (so the server can stop them). */
    List<Integer> drainStranded() {
        List<Integer> stranded = new ArrayList<>();
        for (State state : justDepleted) {
            stranded.addAll(state.users);
        }
        justDepleted.clear();
        return stranded;
    }

    /** Counts down respawn timers. */
    void tick() {
        for (State state : List.copyOf(states.values())) {
            if (state.depleted() && --state.respawnIn == 0) {
                state.charges = state.node.maxCharges;
                changed(state);
            }
        }
    }

    /** Everything that isn't in its default state, for newly joined clients. */
    List<Event.NodeStatus> snapshot() {
        return states.values().stream().map(State::status).toList();
    }
}
