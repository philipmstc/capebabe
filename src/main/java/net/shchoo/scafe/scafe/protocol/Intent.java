package net.shchoo.scafe.scafe.protocol;

import net.shchoo.scafe.scafe.model.Position;

import java.util.List;

/** What a client asks the server to do. The server decides what actually happens. */
public sealed interface Intent {
    /** Steps the server will queue ahead of you; clients predict at most this many. */
    int MAX_QUEUED_STEPS = 2;
    /** Player colors are an index into the client's palette of this many. */
    int PLAYER_COLORS = 8;
    /**
     * Bump whenever an Intent or Event changes shape (fields added/renamed, types added), so a client and server
     * from different builds refuse each other with a clear message instead of misbehaving.
     */
    int PROTOCOL_VERSION = 1;

    /** Step 1 of joining: who you are. Answered with LoginAccepted or LoginRefused. */
    record Login(int protocolVersion, String name, String password) implements Intent {}
    /** Step 2: the world's password (null if LoginAccepted said none is needed). Answered with Welcome or LoginRefused. */
    record EnterWorld(String worldPassword) implements Intent {}
    /**
     * Joining with just a name: only for trusted in-process clients (tests, tools). From the network it's what
     * clients from before logins sent, so it gets "update your game".
     */
    record Join(String name) implements Intent {}
    record Leave() implements Intent {}
    record Move(int dx, int dy) implements Intent {}
    record WalkTo(Position target) implements Intent {}
    /** Start using the node you're standing on, or stop the current action. */
    record Perform() implements Intent {}
    record AddGoal() implements Intent {}
    /** Fight a monster next to you (or stop fighting). */
    record Attack() implements Intent {}
    record SetColor(int colorIndex) implements Intent {}
    /** Running = two steps per tick. */
    record SetRunning(boolean running) implements Intent {}
    /** Auto-investigate: while you're moving, a node appearing nearby turns you towards it. */
    record SetAutoInvestigate(boolean on) implements Intent {}
    /** Debug / world-building commands (the 1 / 2 / 3 keys). */
    record Admin(String command, List<String> args) implements Intent {}
}
