package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.generation.WorldGenerator;
import net.shchoo.scafe.scafe.model.View;
import net.shchoo.scafe.scafe.model.Stations;
import net.shchoo.scafe.scafe.model.Building;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.ContentBatch;
import net.shchoo.scafe.scafe.model.Direction;
import net.shchoo.scafe.scafe.model.MonsterType;
import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.Skill;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.protocol.Event;
import net.shchoo.scafe.scafe.protocol.GameClock;
import net.shchoo.scafe.scafe.protocol.Intent;
import net.shchoo.scafe.scafe.model.Stat;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The authoritative game for one world. Runs its own tick loop; clients talk to it only through
 * {@link Session}s (intents in, events out), so it never needs locks.
 */
public class GameServer {

    /** One connected client. Thread-safe: the client side sends/drains, the server thread does the rest. */
    public static class Session {
        private final Queue<Intent> intents = new ConcurrentLinkedQueue<>();
        private final Queue<Event> events = new ConcurrentLinkedQueue<>();
        private volatile GameCharacter character;
        private volatile boolean closed;
        /** May use the debug / world-building commands. */
        private final boolean admin;
        /** Connected over the network (needs the world password, if the world has one), and from where. */
        private final boolean remote;
        private final String address;
        /** Set once Login passes; EnterWorld then joins as this name. */
        private String loggedInAs;
        /** For a new account (or one without a password yet): the password to save once EnterWorld passes. */
        private String pendingPasswordHash;

        Session(boolean admin, boolean remote, String address) {
            this.admin = admin;
            this.remote = remote;
            this.address = address;
        }

        /** Logged in and playing. */
        public boolean inWorld() {
            return character != null;
        }

        /** True once the server has ended the session (left, or refused for good); its last events still drain. */
        public boolean isClosed() {
            return closed;
        }

        public void send(Intent intent) {
            intents.add(intent);
        }

        public List<Event> drain() {
            List<Event> drained = new ArrayList<>();
            Event event;
            while ((event = events.poll()) != null) {
                drained.add(event);
            }
            return drained;
        }

        public void close() {
            send(new Intent.Leave());
        }
    }

    /** Every 50 ticks = 30s. */
    public static final int AUTOSAVE_TICKS = 50;
    /** You hear about characters and monsters within this many tiles (either axis). The map shows 5 x 3 each way. */
    public static final int VIEW_RANGE = 16;
    /** Longest chat line (characters); longer ones are cut. */
    public static final int MAX_CHAT = 200;
    /** How close (in steps) a new node has to appear for auto-investigate to go and look. */
    public static final int AUTO_INVESTIGATE_RANGE = 10;
    /** Failed logins (wrong password / world password) one address gets within the window before it's locked out. */
    public static final int MAX_FAILED_LOGINS = 5;
    public static final long FAILED_LOGIN_WINDOW_MILLIS = 10 * 60 * 1000;
    public static final int MIN_PASSWORD_LENGTH = 4;

    public final World world;
    /** Null = nothing is saved (tests, throwaway worlds). */
    private final Storage store;
    private final List<Session> sessions = new CopyOnWriteArrayList<>();
    private final Map<Integer, GameCharacter> online = new LinkedHashMap<>();
    private final NodeStates nodes = new NodeStates(this::broadcast);
    private Discovery discovery = new Discovery(new Random());
    private final Monsters monsters = new Monsters(this::passable);
    private final Combat combat = new Combat(this);
    /** Combat rolls, monster wandering and spawn spots. */
    private Random random = new Random();
    private int nextCharacterId = 1;
    private long tick = 0;
    private volatile boolean running;
    private Thread thread;
    /** Network players must give this to enter; null = no world password. */
    private String worldPassword;
    /** Recent failed logins (wall-clock millis) per address. */
    private final Map<String, Deque<Long>> failedLogins = new HashMap<>();
    /** Passwords when nothing is saved (store == null). */
    private final Map<String, String> unsavedPasswords = new HashMap<>();

    public GameServer(World world) {
        this(world, null);
    }

    public GameServer(World world, Storage store) {
        this.world = world;
        this.store = store;
    }

    /** Account names (lower case) allowed admin commands from anywhere, on top of local / {@code --admin} sessions. */
    private Set<String> admins = Set.of();

    /** {@code --admins name1,name2}: these players may use admin (world-building) commands. */
    public void setAdmins(Collection<String> names) {
        admins = names.stream().map(n -> n.trim().toLowerCase(java.util.Locale.ROOT)).filter(n -> !n.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** A local session (same JVM), which is trusted with admin commands. */
    public Session connect() {
        return connect(true);
    }

    public Session connect(boolean admin) {
        return connect(admin, false, "local");
    }

    /** A network client from {@code address}: has to log in, and give the world password if there is one. */
    public Session connectRemote(boolean admin, String address) {
        return connect(admin, true, address);
    }

    private Session connect(boolean admin, boolean remote, String address) {
        Session session = new Session(admin, remote, address);
        sessions.add(session);
        return session;
    }

    public void setWorldPassword(String worldPassword) {
        this.worldPassword = worldPassword == null || worldPassword.isEmpty() ? null : worldPassword;
    }

    /** Characters in the world right now. Server thread (or tests driving tick()) only. */
    public int onlineCount() {
        return online.size();
    }

    public long currentTick() {
        return tick;
    }

    /** Swap out how the world grows from play ({@link Discovery#off()} for tests that need a fixed world). */
    public void setDiscovery(Discovery discovery) {
        this.discovery = discovery;
    }

    /** Swap the randomness used for combat and monsters (tests use a fixed or rigged one). */
    public void setRandom(Random random) {
        this.random = random;
    }

    Random random() {
        return random;
    }

    Monsters monsters() {
        return monsters;
    }

    Collection<GameCharacter> onlineCharacters() {
        return online.values();
    }

    GameCharacter online(int id) {
        return online.get(id);
    }

    /** Nodes and buildings block movement; players and monsters don't. */
    boolean passable(Position position) {
        return !world.content.blocked(position);
    }

    /** Gives the world one of each building (bank, church, station, merchant) it hasn't got, near the origin but not on it. */
    public void ensureBuildings() {
        for (Building.Kind kind : Building.Kind.values()) {
            if (world.content.buildings().stream().noneMatch(b -> b.kind() == kind)) {
                applyContent(WorldGenerator.newBuilding(world, kind));
            }
        }
    }

    /** The prestige version of {@code base}: made (new content, saved and sent to everyone) the first time it's needed. */
    Product prestigeProduct(Product base, int prestige) {
        Product variant = world.content.variant(base, prestige);
        if (variant == null) {
            applyContent(WorldGenerator.newPrestigeProduct(world, base, prestige));
            variant = world.content.variant(base, prestige);
        }
        return variant == null ? base : variant;
    }

    /** Ticks on the train, end to end. */
    static final int TRAIN_TICKS = 5;

    /**
     * All aboard for {@code stationId} (picked from the station menu, which the client opened on q), paying the
     * fare ({@link Stations#fare}): one of skillN{0}-0 for station N.
     */
    void ride(GameCharacter character, int stationId) {
        Building from = Stations.near(world.content, character.position());
        List<Building> line = Stations.all(world.content);
        Building to = line.stream().filter(b -> b.id() == stationId).findFirst().orElse(null);
        if (from == null) {
            character.say("You need to be at a station.");
            return;
        }
        if (to == null) {
            character.say("There's no such station.");
            return;
        }
        if (to.equals(from)) {
            character.say("You're already here.");
            return;
        }
        int index = line.indexOf(to);
        if (!Stations.connected(world.content, from, to)) {
            character.say("There's no direct line from " + Stations.name(line.indexOf(from)) + " to " + Stations.name(index) + ".");
            return;
        }
        Product fare = Stations.fare(world.content, index);
        if (fare != null && character.inventory.getOrDefault(fare, 0) < 1) {
            character.say("The fare to " + Stations.name(index) + " is 1 x " + fare.name + ".");
            return;
        }
        if (fare != null) {
            character.changeInventory(fare, -1);
        }
        character.stopAction(null);
        character.stopWalking();
        character.rideTo = to;
        character.ridingUntil = tick + TRAIN_TICKS;
        character.emit(new Event.ActionStarted(-1, "riding the train", tick, TRAIN_TICKS));
        character.say(Event.MessageKind.NOTICE, "All aboard for " + Stations.name(index) + "! The train pulls out...");
    }

    /** Far from every station you might come across a new one ({@link Discovery}); just inside your view, ahead. */
    void discoverStation(GameCharacter character) {
        Position wanted = edgeOfView(Building.Kind.STATION, character.position(), character.facing);
        int before = Stations.all(world.content).size();
        applyContent(WorldGenerator.newBuildingAt(world, Building.Kind.STATION, wanted, 6, this::taken), character);
        List<Building> line = Stations.all(world.content);
        if (line.size() == before) {
            return;
        }
        Building found = line.get(line.size() - 1);
        Position at = found.platform();
        String name = Stations.name(line.size() - 1);
        character.say(Event.MessageKind.NOTICE, "You spot a train station ahead: " + name + " (" + at.x() + ", " + at.y() + ")!");
        tellOthers(character, Event.MessageKind.NOTICE, character.name + " found a new train station: " + name
                + " (" + at.x() + ", " + at.y() + ").");
    }

    /**
     * Where a building found by someone at {@code at} facing {@code facing} goes: just inside the edge of their
     * view in that direction (its nearest 4 tiles, or all of it if smaller, on screen), centred across it --
     * so it's seen, but not on top of them.
     */
    static Position edgeOfView(Building.Kind kind, Position at, Direction facing) {
        int w = kind.width(), h = kind.height();
        int showW = Math.min(w, 4), showH = Math.min(h, 4);
        return switch (facing) {
            case RIGHT -> new Position(at.x() + View.HALF_WIDTH - showW + 1, at.y() - h / 2);
            case LEFT -> new Position(at.x() - View.HALF_WIDTH + showW - w, at.y() - h / 2);
            case DOWN -> new Position(at.x() - w / 2, at.y() + View.HALF_HEIGHT - showH + 1);
            case UP -> new Position(at.x() - w / 2, at.y() - View.HALF_HEIGHT + showH - h);
        };
    }

    /** Riders whose train is in: off at the platform. */
    private void arrive() {
        for (GameCharacter character : online.values()) {
            if (!character.isRiding() || tick < character.ridingUntil) {
                continue;
            }
            Building to = character.rideTo;
            character.ridingUntil = -1;
            character.rideTo = null;
            Position platform = to.platform();
            character.moveTo(platform);
            character.face(Direction.DOWN);
            character.saveSoon = true;
            character.emit(new Event.StepsCleared(platform, character.facing));
            toObservers(character.id, new Event.EntityMoved(character.id, platform, character.facing));
            character.emit(new Event.ActionStopped());
            character.say(Event.MessageKind.NOTICE, "The train pulls in at "
                    + Stations.name(Stations.all(world.content).indexOf(to)) + ".");
        }
    }

    /** A bank right next to (or under) {@code position}, or null. */
    private Building bankNear(Position position) {
        return world.content.buildings().stream()
                .filter(b -> b.kind() == Building.Kind.BANK && b.within(position, 1)).findFirst().orElse(null);
    }

    /** A tile something new shouldn't appear on: a node, a monster or a player is there. */
    boolean taken(Position position) {
        return world.content.builtOn(position) || monsters.occupied(position)
                || online.values().stream().anyMatch(c -> c.position().equals(position));
    }

    Monster spawnMonster(MonsterType type, Position at) {
        return monsters.spawn(type, at);
    }

    void removeMonster(Monster monster) {
        monsters.remove(monster);
    }

    /** Sends to every character that can currently see the entity (not the entity itself). */
    void toObservers(int entityId, Event event) {
        online.values().stream().filter(c -> c.seen.contains(entityId)).forEach(c -> c.emit(event));
    }

    public static boolean isValidName(String name) {
        return name != null && name.matches("[A-Za-z0-9_-]{1,20}");
    }

    /** Runs the tick loop on its own thread until {@link #stop()}. */
    public void start() {
        running = true;
        thread = new Thread(this::run, "game-server-" + world.id);
        thread.setDaemon(true);
        thread.start();
    }

    /** Stops ticking and saves everyone. Blocks until done. */
    public void stop() {
        running = false;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else {
            saveAll();
        }
    }

    private void run() {
        GameClock clock = new GameClock();
        while (running) {
            for (int due = clock.ticksDue(); due > 0; due--) {
                try {
                    tick();
                } catch (RuntimeException e) {
                    // one bad tick shouldn't take the world down
                    e.printStackTrace();
                }
            }
            try {
                Thread.sleep(Math.max(1, (long) ((1 - clock.partialTick()) * GameClock.TICK_MILLIS)));
            } catch (InterruptedException e) {
                break;
            }
        }
        saveAll();
    }

    /**
     * One game tick: joins/leaves and movement intents, then walkers (players, then monsters) step, then actions
     * and fights progress, then the remaining intents (perform, goals, attack, admin) -- so "step then press q"
     * acts on the tile you stepped to.
     */
    public void tick() {
        tick++;
        broadcast(new Event.Tick(tick));
        List<Map.Entry<Session, Intent>> afterMovement = new ArrayList<>();
        for (Session session : sessions) {
            Intent intent;
            while ((intent = session.intents.poll()) != null) {
                if (intent instanceof Intent.Perform || intent instanceof Intent.AddGoal || intent instanceof Intent.Admin
                        || intent instanceof Intent.Attack || intent instanceof Intent.UseItem) {
                    afterMovement.add(Map.entry(session, intent));
                } else {
                    handle(session, intent);
                }
            }
        }
        sessions.removeIf(session -> session.closed);
        List<Map.Entry<GameCharacter, Event>> steps = new ArrayList<>();
        for (GameCharacter character : online.values()) {
            Position startOfTick = character.position();
            for (int step = 0; step < (character.running ? 2 : 1); step++) {
                Walker.Result result = character.walker.tick();
                if (result == Walker.Result.BLOCKED) {
                    character.say(Event.MessageKind.ROUTINE, "Something's in the way.");
                }
                if (result != Walker.Result.STEPPED) {
                    break;
                }
                character.lastSteppedTick = tick;
                // one event per step, even a turn or a bump, so the client can match its predictions one by one
                steps.add(Map.entry(character, new Event.EntityMoved(character.id, character.position(), character.facing)));
            }
            // one discovery roll per tick spent moving, walking or running (running isn't a faster way to discover)
            if (!character.position().equals(startOfTick)) {
                character.triggers.add(Discovery.Trigger.MOVED);
                character.count(Stat.STEPS);
            }
        }
        List<Monster> movedMonsters = monsters.tick(random, online.values());
        updateVisibility();
        for (Map.Entry<GameCharacter, Event> step : steps) {
            step.getKey().emit(step.getValue());
            toObservers(step.getKey().id, step.getValue());
        }
        for (Monster mover : movedMonsters) {
            toObservers(mover.id, new Event.EntityMoved(mover.id, mover.position(), Direction.DOWN));
        }
        for (GameCharacter character : online.values()) {
            if (character.currentAction != null && !character.currentAction.tick(character, tick)) {
                character.stopAction(null);
            }
        }
        combat.tick(tick);
        for (GameCharacter character : online.values()) {
            if (character.pendingUse != null) {
                character.arrive(world.content.nodeAt(character.pendingUse), tick);
            }
        }
        afterMovement.forEach(entry -> handle(entry.getKey(), entry.getValue()));
        for (int id : nodes.drainStranded()) {
            GameCharacter character = online.get(id);
            // AFK, you wait for it to come back (ActionProgress)
            if (character != null && character.currentAction != null && !character.currentAction.afk) {
                character.stopAction("The " + character.currentAction.node.label() + " ran out.");
            }
        }
        nodes.tick();
        for (GameCharacter character : List.copyOf(online.values())) {
            List<Discovery.Trigger> triggers = List.copyOf(character.triggers);
            character.triggers.clear();
            for (Discovery.Trigger trigger : triggers) {
                try {
                    discovery.roll(this, character, trigger);
                } catch (RuntimeException e) {
                    // a failed discovery (e.g. couldn't save) shouldn't cost anyone their tick
                    e.printStackTrace();
                }
            }
        }
        combat.regenerate(tick);
        combat.wakeUp(tick);
        combat.chase(tick);
        // time played: counted quietly (the client counts along between updates, see Player.timePlayedTicks)
        online.values().forEach(c -> c.stats.merge(Stat.TIME_PLAYED, 1L, Long::sum));
        arrive();
        updateVisibility();
        for (GameCharacter changed : online.values()) {
            if (changed.infoChanged) {
                changed.infoChanged = false;
                toObservers(changed.id, new Event.EntityUpdated(changed.info()));
            }
        }
        for (GameCharacter character : online.values()) {
            if (character.statsChanged) {
                character.statsChanged = false;
                character.emit(new Event.StatsChanged(character.statsByName()));
            }
        }
        boolean autosave = tick % AUTOSAVE_TICKS == 0;
        for (GameCharacter character : online.values()) {
            if (autosave || character.saveSoon) {
                save(character);
            }
        }
    }

    private void save(GameCharacter character) {
        character.saveSoon = false;
        if (store == null) {
            return;
        }
        try {
            store.saveCharacter(character);
        } catch (Exception e) {
            // keep playing; the next save will try again
            e.printStackTrace();
        }
    }

    private void saveAll() {
        online.values().forEach(this::save);
    }

    private void handle(Session session, Intent intent) {
        if (intent instanceof Intent.Login login) {
            if (session.character == null) {
                login(session, login);
            }
            return;
        }
        if (intent instanceof Intent.EnterWorld enter) {
            if (session.character == null) {
                enterWorld(session, enter.worldPassword());
            }
            return;
        }
        if (intent instanceof Intent.Join join) {
            if (session.remote) {
                refuse(session, "This server needs a newer version of the game. Update and try again.");
                session.events.add(new Event.Message("This server needs a newer version of the game. Update and try again."));
                endSession(session);
            } else if (session.character == null) {
                join(session, join.name());
            }
            return;
        }
        if (intent instanceof Intent.Leave) {
            leave(session);
            return;
        }
        GameCharacter character = session.character;
        if (character == null) {
            return;
        }
        if ((character.isDown() || character.isRiding()) && !(intent instanceof Intent.Chat)) {
            // knocked out or on the train: nothing to do but wait (and talk); undo any steps the client guessed at
            if (intent instanceof Intent.Move) {
                character.emit(new Event.StepsCleared(character.position(), character.facing));
            }
            return;
        }
        if (intent instanceof Intent.Move || intent instanceof Intent.WalkTo || intent instanceof Intent.Perform
                || intent instanceof Intent.Craft || intent instanceof Intent.UseItem || intent instanceof Intent.Attack
                || intent instanceof Intent.Ride) {
            // doing something else: not walking over to a fight any more
            character.pendingFight = null;
        }
        if (intent instanceof Intent.Move move) {
            Direction direction = Direction.of(move.dx(), move.dy());
            if (direction != null) {
                character.queueStep(direction);
            }
        } else if (intent instanceof Intent.SetRunning setRunning) {
            character.running = setRunning.running();
            character.saveSoon = true;
            character.emit(new Event.RunningChanged(character.running));
        } else if (intent instanceof Intent.SetAutoInvestigate set) {
            character.autoInvestigate = set.on();
            character.saveSoon = true;
            character.emit(new Event.AutoInvestigateChanged(character.autoInvestigate));
        } else if (intent instanceof Intent.Chat chat) {
            String text = chat.text() == null ? "" : chat.text().replaceAll("\\p{Cntrl}", "").strip();
            if (!text.isEmpty()) {
                broadcast(new Event.Chat(character.name, character.colorIndex,
                        text.length() > MAX_CHAT ? text.substring(0, MAX_CHAT) : text));
            }
        } else if (intent instanceof Intent.SetColor setColor) {
            if (setColor.colorIndex() >= 0 && setColor.colorIndex() < Intent.PLAYER_COLORS) {
                character.colorIndex = setColor.colorIndex();
                character.infoChanged = true;
                character.saveSoon = true;
                character.emit(new Event.ColorChanged(character.colorIndex));
            }
        } else if (intent instanceof Intent.Heal || intent instanceof Intent.Pray) {
            Building building = world.content.solidBuildingAt(character.ahead());
            if (building == null || building.kind() != Building.Kind.CHURCH) {
                character.say("You need to be at a church.");
            } else if (intent instanceof Intent.Pray pray) {
                character.pray(world.content.recipe(pray.recipeId()), world.content.product(pray.paymentProductId()));
            } else {
                character.heal();
            }
        } else if (intent instanceof Intent.WalkTo walkTo) {
            character.walkTo(walkTo.target());
        } else if (intent instanceof Intent.Perform) {
            Building building = world.content.solidBuildingAt(character.ahead());
            if (character.currentFight != null) {
                combat.endFight(character, "You stop fighting.");
            } else if (building != null && building.kind() == Building.Kind.BANK) {
                // the client opened its bank window already (it has the contents); just stop what you were doing
                character.stopAction(null);
            } else if (building != null && building.kind() == Building.Kind.CHURCH) {
                // the client opened its church window already; just stop what you were doing
                character.stopAction(null);
            } else if (building != null && building.kind() == Building.Kind.STATION) {
                // the client opened its station menu already; just stop what you were doing
                character.stopAction(null);
            } else if (building != null && building.kind() == Building.Kind.MERCHANT) {
                character.say("The ant waves its antennae: not open for business yet.");
            } else {
                character.performOrStop(nodeAhead(character), tick);
            }
        } else if (intent instanceof Intent.UseItem use) {
            if (character.currentFight != null) {
                combat.endFight(character, null);
            }
            character.useItem(world.content.product(use.productId()), world.content.nodeAt(use.target()), use.target(), tick);
        } else if (intent instanceof Intent.Craft craft) {
            if (character.currentFight != null) {
                combat.endFight(character, null);
            }
            character.craftAt(world.content.nodeAt(craft.target()), craft.target(), craft.afk(), tick);
        } else if (intent instanceof Intent.Attack) {
            combat.attack(character, tick);
        } else if (intent instanceof Intent.AddGoal) {
            character.addGoal(nodeAhead(character));
        } else if (intent instanceof Intent.Equip equip) {
            if (equip.slot() != null) {
                character.equip(equip.slot(), world.content.product(equip.productId()));
            }
        } else if (intent instanceof Intent.Fight fight) {
            combat.fightAt(character, fight.monsterId(), tick);
        } else if (intent instanceof Intent.Prestige prestige) {
            character.prestige(world.content.skill(prestige.skillId()));
        } else if (intent instanceof Intent.Ride ride) {
            ride(character, ride.stationId());
        } else if (intent instanceof Intent.Deposit deposit) {
            if (bankNear(character.position()) == null) {
                character.say("You need to be at a bank.");
            } else {
                character.deposit(world.content.product(deposit.productId()), deposit.count());
            }
        } else if (intent instanceof Intent.Withdraw withdraw) {
            if (bankNear(character.position()) == null) {
                character.say("You need to be at a bank.");
            } else {
                character.withdraw(world.content.product(withdraw.productId()), withdraw.count());
            }
        } else if (intent instanceof Intent.Unequip unequip) {
            if (unequip.slot() != null) {
                character.unequip(unequip.slot());
            }
        } else if (intent instanceof Intent.SetGoalLevel set) {
            character.setGoalLevel(set.index(), set.level());
        } else if (intent instanceof Intent.Admin admin) {
            if (session.admin || admins.contains(character.name.toLowerCase(java.util.Locale.ROOT))) {
                admin(character, admin.command(), admin.args());
            } else {
                character.say("Admin commands are turned off for you on this server.");
            }
        }
    }

    /** You act on the node you're facing. */
    private Node nodeAhead(GameCharacter character) {
        return world.content.nodeAt(character.ahead());
    }

    private void login(Session session, Intent.Login login) {
        if (login.protocolVersion() != Intent.PROTOCOL_VERSION) {
            refuse(session, "Your game is version " + login.protocolVersion() + " but this server is version "
                    + Intent.PROTOCOL_VERSION + ". " + (login.protocolVersion() < Intent.PROTOCOL_VERSION
                    ? "Update your game." : "The server needs updating."));
            endSession(session);
            return;
        }
        if (lockedOut(session.address)) {
            refuse(session, "Too many failed logins. Try again in a few minutes.");
            endSession(session);
            return;
        }
        String name = login.name();
        if (!isValidName(name)) {
            refuse(session, "Names are 1-20 letters, digits, - or _.");
            return;
        }
        if (online.values().stream().anyMatch(c -> c.name.equals(name))) {
            refuse(session, name + " is already playing in this world.");
            return;
        }
        String password = login.password() == null ? "" : login.password();
        String stored;
        try {
            stored = store == null ? unsavedPasswords.get(name) : store.passwordHash(name);
        } catch (Exception e) {
            e.printStackTrace();
            refuse(session, "Couldn't check that account: " + e.getMessage());
            return;
        }
        boolean newAccount = stored == null;
        if (newAccount) {
            if (password.length() < MIN_PASSWORD_LENGTH) {
                refuse(session, "New here? Pick a password of at least " + MIN_PASSWORD_LENGTH + " characters.");
                return;
            }
            session.pendingPasswordHash = Passwords.hash(password);
        } else if (!Passwords.matches(password, stored)) {
            failedLogin(session, "Wrong name or password.");
            return;
        }
        session.loggedInAs = name;
        session.events.add(new Event.LoginAccepted(session.remote && worldPassword != null, newAccount));
    }

    private void enterWorld(Session session, String given) {
        if (session.loggedInAs == null) {
            refuse(session, "Log in first.");
            return;
        }
        if (session.remote && worldPassword != null && !Passwords.sameSecret(worldPassword, given)) {
            failedLogin(session, "Wrong world password.");
            return;
        }
        if (session.pendingPasswordHash != null) {
            try {
                if (store == null) {
                    unsavedPasswords.put(session.loggedInAs, session.pendingPasswordHash);
                } else {
                    store.setPasswordHash(session.loggedInAs, session.pendingPasswordHash);
                }
            } catch (Exception e) {
                e.printStackTrace();
                refuse(session, "Couldn't save your account: " + e.getMessage());
                return;
            }
            session.pendingPasswordHash = null;
        }
        join(session, session.loggedInAs);
    }

    private void refuse(Session session, String reason) {
        session.events.add(new Event.LoginRefused(reason));
    }

    /** A wrong password: counts towards the address's lockout, and ends the session once it's locked out. */
    private void failedLogin(Session session, String reason) {
        long now = System.currentTimeMillis();
        Deque<Long> recent = failedLogins.computeIfAbsent(session.address, a -> new ArrayDeque<>());
        recent.addLast(now);
        refuse(session, reason);
        if (lockedOut(session.address)) {
            refuse(session, "Too many failed logins. Try again in a few minutes.");
            endSession(session);
        }
    }

    private boolean lockedOut(String address) {
        Deque<Long> recent = failedLogins.get(address);
        if (recent == null) {
            return false;
        }
        long cutoff = System.currentTimeMillis() - FAILED_LOGIN_WINDOW_MILLIS;
        while (!recent.isEmpty() && recent.peekFirst() < cutoff) {
            recent.removeFirst();
        }
        return recent.size() >= MAX_FAILED_LOGINS;
    }

    /** Refused for good: the network side sends what's queued, then hangs up. */
    private void endSession(Session session) {
        session.closed = true;
    }

    private void join(Session session, String name) {
        if (!isValidName(name)) {
            session.events.add(new Event.Message("Names are 1-20 letters, digits, - or _."));
            return;
        }
        if (online.values().stream().anyMatch(c -> c.name.equals(name))) {
            session.events.add(new Event.Message(name + " is already playing in this world."));
            return;
        }
        GameCharacter character;
        try {
            character = loadOrCreate(name);
        } catch (Exception e) {
            e.printStackTrace();
            session.events.add(new Event.Message("Couldn't load " + name + ": " + e.getMessage()));
            return;
        }
        session.character = character;
        character.connect(session.events::add, nodes, world.content, this::prestigeProduct);
        character.walker.setPassable(this::passable);
        character.hp = character.savedHp == null ? character.maxHp() : Math.max(1, Math.min(character.savedHp, character.maxHp()));
        List<GameCharacter> nearby = online.values().stream()
                .filter(other -> inRange(character.position(), other.position())).toList();
        List<Monster> nearbyMonsters = monsters.all().stream()
                .filter(monster -> inRange(character.position(), monster.position())).toList();
        online.put(character.id, character);
        nearby.forEach(other -> character.seen.add(other.id));
        nearbyMonsters.forEach(monster -> character.seen.add(monster.id));
        List<Event.EntityInfo> others = new ArrayList<>(nearby.stream().map(GameCharacter::info).toList());
        nearbyMonsters.forEach(monster -> others.add(monster.info()));
        character.emit(new Event.Welcome(world.name, tick, List.copyOf(world.content.history()), character.snapshot(),
                others, nodes.snapshot()));
        List<String> alsoHere = online.values().stream().filter(c -> c != character).map(c -> c.name).toList();
        if (!alsoHere.isEmpty()) {
            character.say(Event.MessageKind.NOTICE, "Also here: " + String.join(", ", alsoHere) + ".");
        }
        tellOthers(character, Event.MessageKind.NOTICE, name + " logged in.");
        for (GameCharacter other : nearby) {
            other.seen.add(character.id);
            other.emit(new Event.EntityEntered(character.info()));
        }
    }

    private void leave(Session session) {
        if (session.closed) {
            return;
        }
        session.closed = true;
        GameCharacter character = session.character;
        session.character = null;
        if (character == null) {
            return;
        }
        character.stopAction(null);
        combat.endFight(character, null);
        if (character.isDown()) {
            combat.wakeUp(character);
        }
        // logging out on the train: you never left the platform
        character.ridingUntil = -1;
        character.rideTo = null;
        online.remove(character.id);
        character.savedHp = character.currentHp();
        character.disconnect();
        save(character);
        tellOthers(character, Event.MessageKind.NOTICE, character.name + " logged out.");
        for (GameCharacter other : online.values()) {
            if (other.seen.remove(character.id)) {
                other.emit(new Event.EntityLeft(character.id));
            }
        }
    }

    private void admin(GameCharacter character, String command, List<String> args) {
        try {
            if (command.equals("4")) {
                spawnMonsterNextTo(character, !args.isEmpty() && args.get(0).equals("new"));
                return;
            }
            if (command.equals("5")) {
                giveExperience(character, args);
                return;
            }
            ContentBatch batch = switch (command) {
                case "1" -> WorldGenerator.newSkills(world, intArg(args, 0, 1));
                case "2" -> WorldGenerator.newProductLines(world, intArg(args, 0, 1));
                case "3" -> WorldGenerator.newNode(world, character.ahead(),
                        intArg(args, 0, Node.UNLIMITED), intArg(args, 1, Node.UNLIMITED), intArg(args, 2, 10));
                default -> throw new IllegalArgumentException("Unknown admin command " + command);
            };
            applyContent(batch, character);
            for (ContentBatch.SkillDef skill : batch.skills()) {
                if (discovery.enabled()) {
                    Discovery.spawnStarterNode(this, character, world.content.skill(skill.id()), random);
                }
            }
        } catch (RuntimeException e) {
            character.say("Couldn't run " + command + ": " + e.getMessage());
        }
    }

    /** Debug "/4" (random known kind) or "/4 new" (a new kind): a monster right next to you. */
    private void spawnMonsterNextTo(GameCharacter character, boolean newKind) {
        if (newKind || world.content.monsters.isEmpty()) {
            applyContent(WorldGenerator.newMonsterType(world), character);
        }
        List<MonsterType> kinds = world.content.monsters;
        MonsterType type = newKind ? kinds.get(kinds.size() - 1) : kinds.get(random.nextInt(kinds.size()));
        Position at = Discovery.freeSpot(this, character.position(), 1, 1, random);
        spawnMonster(type, at == null ? character.position() : at);
        character.say("A " + type.name + " (Lv. " + type.level + ") appears. Press f next to it to fight.");
        updateVisibility();
    }

    /** Debug "/5 skill [amount]": skill is its name ("skill1", "combat") or id; amount defaults to 10,000. */
    private void giveExperience(GameCharacter character, List<String> args) {
        if (args.isEmpty()) {
            character.say("Usage: /5 <skill name or id> [amount]");
            return;
        }
        String wanted = args.get(0);
        Skill skill = world.content.skills.stream().filter(s -> s.name.equalsIgnoreCase(wanted)).findFirst()
                .orElseGet(() -> wanted.matches("\\d+") ? world.content.skill(Integer.parseInt(wanted)) : null);
        if (skill == null) {
            character.say("No skill called " + wanted + ".");
            return;
        }
        int amount = intArg(args, 1, 10_000);
        if (amount <= 0) {
            character.say("Experience has to be more than 0.");
            return;
        }
        character.gainExperience(skill, amount);
    }

    private static int intArg(List<String> args, int index, int fallback) {
        return args.size() > index ? Integer.parseInt(args.get(index)) : fallback;
    }

    private GameCharacter loadOrCreate(String name) throws Exception {
        return store == null ? new GameCharacter(nextCharacterId++, name) : store.loadOrCreateCharacter(name);
    }

    /** The only way content changes: save the whole batch, apply it, then tell everyone in one event. */
    void applyContent(ContentBatch batch) {
        applyContent(batch, null, null);
    }

    /** Like {@link #applyContent(ContentBatch)}, also saving {@code grant} in the same transaction. */
    /** Like {@link #applyContent(ContentBatch)}, recording that {@code by}'s action made it, and when. */
    void applyContent(ContentBatch batch, GameCharacter by) {
        applyContent(batch, null, by);
    }

    void applyContent(ContentBatch batch, Storage.Grant grant) {
        applyContent(batch, grant, null);
    }

    void applyContent(ContentBatch batch, Storage.Grant grant, GameCharacter by) {
        if (batch.isEmpty()) {
            return;
        }
        if (by != null) {
            batch = batch.withOrigin(new ContentBatch.Origin(by.name, System.currentTimeMillis()));
        }
        if (store != null) {
            try {
                store.saveContent(batch, grant);
            } catch (Exception e) {
                throw new IllegalStateException("couldn't save new content: " + e.getMessage(), e);
            }
        }
        world.content.apply(batch);
        broadcast(new Event.ContentAdded(batch));
        for (ContentBatch.NodeDef node : batch.nodes()) {
            investigate(new Position(node.x(), node.y()));
        }
    }

    /** Auto-investigate: characters on the move near a new node change course for it (standing still = left alone). */
    private void investigate(Position node) {
        for (GameCharacter character : online.values()) {
            boolean moving = character.walker.isWalking() || character.lastSteppedTick >= tick - 1;
            int distance = Math.max(Math.abs(node.x() - character.x), Math.abs(node.y() - character.y));
            if (character.autoInvestigate && moving && distance <= AUTO_INVESTIGATE_RANGE && distance > 1) {
                character.say("You spot something new nearby and head over.");
                character.walkTo(node);
            }
        }
    }

    void tellOthers(GameCharacter except, String text) {
        tellOthers(except, Event.MessageKind.INFO, text);
    }

    void tellOthers(GameCharacter except, Event.MessageKind kind, String text) {
        online.values().stream().filter(c -> c != except).forEach(c -> c.say(kind, text));
    }

    private void broadcast(Event event) {
        online.values().forEach(character -> character.emit(event));
    }

    private static boolean inRange(Position a, Position b) {
        return Math.abs(a.x() - b.x()) <= VIEW_RANGE && Math.abs(a.y() - b.y()) <= VIEW_RANGE;
    }

    /** Tells characters about players and monsters coming into / going out of range, or gone altogether. */
    private void updateVisibility() {
        Map<Integer, Event.EntityInfo> entities = new LinkedHashMap<>();
        online.values().forEach(c -> entities.put(c.id, c.info()));
        monsters.all().forEach(m -> entities.put(m.id, m.info()));
        for (GameCharacter viewer : online.values()) {
            for (Event.EntityInfo entity : entities.values()) {
                if (entity.id() == viewer.id) {
                    continue;
                }
                boolean visible = inRange(viewer.position(), entity.position());
                if (visible && viewer.seen.add(entity.id())) {
                    viewer.emit(new Event.EntityEntered(entity));
                } else if (!visible && viewer.seen.remove(entity.id())) {
                    viewer.emit(new Event.EntityLeft(entity.id()));
                }
            }
            viewer.seen.removeIf(id -> {
                boolean gone = !entities.containsKey(id);
                if (gone) {
                    viewer.emit(new Event.EntityLeft(id));
                }
                return gone;
            });
        }
    }
}
