package net.shchoo.scafe.scafe.protocol;

import net.shchoo.scafe.scafe.model.Position;

import java.util.List;

/** What a client asks the server to do. The server decides what actually happens. */
public sealed interface Intent {
    /** Steps the server will queue ahead of you; clients predict at most this many. */
    int MAX_QUEUED_STEPS = 2;

    record Join(String name) implements Intent {}
    record Leave() implements Intent {}
    record Move(int dx, int dy) implements Intent {}
    record WalkTo(Position target) implements Intent {}
    /** Start using the node you're standing on, or stop the current action. */
    record Perform() implements Intent {}
    record AddGoal() implements Intent {}
    /** Fight a monster next to you (or stop fighting). */
    record Attack() implements Intent {}
    /** Debug / world-building commands (the 1 / 2 / 3 keys). */
    record Admin(String command, List<String> args) implements Intent {}
}
