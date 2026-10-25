package net.shchoo.scafe.scafe.protocol;

import net.shchoo.scafe.scafe.model.EquipSlot;
import net.shchoo.scafe.scafe.model.Position;

import java.util.List;

/** What a client asks the server to do. The server decides what actually happens. */
public sealed interface Intent {
    /** Steps the server will queue ahead of you; clients predict at most this many. */
    int MAX_QUEUED_STEPS = 2;
    /**
     * Player colors are an index into the client's palette of this many (the server checks them against it, so
     * changing it changes the protocol: bump PROTOCOL_VERSION). The client's palette is tested to match.
     */
    int PLAYER_COLORS = 12;
    /**
     * Bump whenever an Intent or Event changes shape (fields added/renamed, types added), so a client and server
     * from different builds refuse each other with a clear message instead of misbehaving.
     */
    int PROTOCOL_VERSION = 24;

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
    /** Use an inventory item on the node at {@code target}: walk over if needed, then start that node's action. */
    record UseItem(int productId, Position target) implements Intent {}
    record AddGoal() implements Intent {}

    /** Point a slot at an item: it's used up from the inventory (every stack) until there's none left. */
    record Equip(EquipSlot slot, int productId) implements Intent {}

    record Unequip(EquipSlot slot) implements Intent {}

    /** Craft at the node at {@code target} (walking over first if need be); {@code afk}: half the xp, waits out respawns. */
    record Craft(Position target, boolean afk) implements Intent {}

    /** Fight this monster: walk up to it first if need be (following it, for a while), then the fight starts. */
    record Fight(int monsterId) implements Intent {}

    /** At Lv. 100: start the skill over from Lv. 1, banking its xp; what you make with it hits harder. */
    record Prestige(int skillId) implements Intent {}

    /** From the station you're at to the one with this id (see {@code Stations}), paying the fare. */
    record Ride(int stationId) implements Intent {}

    /** Only next to a bank. */
    record Deposit(int productId, int count) implements Intent {}

    /** Only next to a bank; takes no more than fits in the inventory. */
    record Withdraw(int productId, int count) implements Intent {}

    /** {@code index} into your goals; {@code level} one of {@code Goal.LEVELS}. */
    record SetGoalLevel(int index, int level) implements Intent {}
    /** Fight a monster next to you (or stop fighting). */
    record Attack() implements Intent {}
    record SetColor(int colorIndex) implements Intent {}
    /** Running = two steps per tick. */
    record SetRunning(boolean running) implements Intent {}
    /** Auto-investigate: while you're moving, a node appearing nearby turns you towards it. */
    record SetAutoInvestigate(boolean on) implements Intent {}
    /** At a church: all your hp back. */
    record Heal() implements Intent {}
    /** At a church: a blessing on this recipe, paid for with that item ({@link net.shchoo.scafe.scafe.model.Prayer}). */
    record Pray(int recipeId, int paymentProductId) implements Intent {}
    /** Say something to everyone in the world. */
    record Chat(String text) implements Intent {}
    /** Debug / world-building commands (the 1 / 2 / 3 keys). */
    record Admin(String command, List<String> args) implements Intent {}
}
