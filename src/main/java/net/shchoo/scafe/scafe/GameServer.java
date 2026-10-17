package net.shchoo.scafe.scafe;

import java.sql.SQLException;
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
        private GameCharacter character;
        private volatile boolean closed;
        /** May use the debug / world-building commands. */
        private final boolean admin;

        Session(boolean admin) {
            this.admin = admin;
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
    /** You hear about other characters within this many tiles (either axis). The map shows 5 x 3 each way. */
    public static final int VIEW_RANGE = 16;

    public final World world;
    /** Null = nothing is saved (tests, throwaway worlds). */
    private final GameStore store;
    private final List<Session> sessions = new CopyOnWriteArrayList<>();
    private final Map<Integer, GameCharacter> online = new LinkedHashMap<>();
    private final NodeStates nodes = new NodeStates(this::broadcast);
    private Discovery discovery = new Discovery(new Random());
    private int nextCharacterId = 1;
    private long tick = 0;
    private volatile boolean running;
    private Thread thread;

    public GameServer(World world) {
        this(world, null);
    }

    public GameServer(World world, GameStore store) {
        this.world = world;
        this.store = store;
    }

    /** A local session (same JVM), which is trusted with admin commands. */
    public Session connect() {
        return connect(true);
    }

    public Session connect(boolean admin) {
        Session session = new Session(admin);
        sessions.add(session);
        return session;
    }

    public long currentTick() {
        return tick;
    }

    /** Swap out how the world grows from play ({@link Discovery#off()} for tests that need a fixed world). */
    public void setDiscovery(Discovery discovery) {
        this.discovery = discovery;
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
     * One game tick: joins/leaves and movement intents, then walkers step, then actions progress, then the
     * remaining intents (perform, goals, admin) -- so "step then press q" acts on the tile you stepped to.
     */
    public void tick() {
        tick++;
        broadcast(new Event.Tick(tick));
        List<Map.Entry<Session, Intent>> afterMovement = new ArrayList<>();
        for (Session session : sessions) {
            Intent intent;
            while ((intent = session.intents.poll()) != null) {
                if (intent instanceof Intent.Perform || intent instanceof Intent.AddGoal || intent instanceof Intent.Admin) {
                    afterMovement.add(Map.entry(session, intent));
                } else {
                    handle(session, intent);
                }
            }
        }
        sessions.removeIf(session -> session.closed);
        List<GameCharacter> moved = new ArrayList<>();
        for (GameCharacter character : online.values()) {
            Board.Position before = character.position();
            character.walker.tick();
            if (!character.position().equals(before)) {
                moved.add(character);
                character.triggers.add(Discovery.Trigger.MOVED);
            }
        }
        updateVisibility();
        for (GameCharacter mover : moved) {
            Event event = new Event.EntityMoved(mover.id, mover.position());
            mover.emit(event);
            online.values().stream().filter(c -> c.seen.contains(mover.id)).forEach(c -> c.emit(event));
        }
        for (GameCharacter character : online.values()) {
            if (character.currentAction != null && !character.currentAction.tick(character, tick)) {
                character.stopAction(null);
            }
        }
        afterMovement.forEach(entry -> handle(entry.getKey(), entry.getValue()));
        for (int id : nodes.drainStranded()) {
            GameCharacter character = online.get(id);
            if (character != null && character.currentAction != null) {
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
        for (GameCharacter changed : online.values()) {
            if (changed.infoChanged) {
                changed.infoChanged = false;
                Event event = new Event.EntityUpdated(changed.info());
                online.values().stream().filter(c -> c.seen.contains(changed.id)).forEach(c -> c.emit(event));
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
        } catch (SQLException e) {
            // keep playing; the next save will try again
            e.printStackTrace();
        }
    }

    private void saveAll() {
        online.values().forEach(this::save);
    }

    private void handle(Session session, Intent intent) {
        if (intent instanceof Intent.Join join) {
            if (session.character == null) {
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
        if (intent instanceof Intent.Move move) {
            character.queueStep(move.dx(), move.dy());
        } else if (intent instanceof Intent.WalkTo walkTo) {
            character.walkTo(walkTo.target());
        } else if (intent instanceof Intent.Perform) {
            character.performOrStop(nodeUnder(character), tick);
        } else if (intent instanceof Intent.AddGoal) {
            character.addGoal(nodeUnder(character));
        } else if (intent instanceof Intent.Admin admin) {
            if (session.admin) {
                admin(character, admin.command(), admin.args());
            } else {
                character.say("Admin commands are turned off for you on this server.");
            }
        }
    }

    private Node nodeUnder(GameCharacter character) {
        return world.content.nodeAt(character.position());
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
        } catch (SQLException e) {
            e.printStackTrace();
            session.events.add(new Event.Message("Couldn't load " + name + ": " + e.getMessage()));
            return;
        }
        session.character = character;
        character.connect(session.events::add, nodes);
        List<GameCharacter> nearby = online.values().stream().filter(other -> inRange(character, other)).toList();
        online.put(character.id, character);
        nearby.forEach(other -> character.seen.add(other.id));
        character.emit(new Event.Welcome(world.name, tick, List.copyOf(world.content.history()), character.snapshot(),
                nearby.stream().map(GameCharacter::info).toList(), nodes.snapshot()));
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
        online.remove(character.id);
        character.disconnect();
        save(character);
        for (GameCharacter other : online.values()) {
            if (other.seen.remove(character.id)) {
                other.emit(new Event.EntityLeft(character.id));
            }
        }
    }

    private void admin(GameCharacter character, String command, List<String> args) {
        try {
            ContentBatch batch = switch (command) {
                case "1" -> WorldGenerator.newSkills(world, intArg(args, 0, 1));
                case "2" -> WorldGenerator.newProductLines(world, intArg(args, 0, 1));
                case "3" -> WorldGenerator.newNode(world, character.position(),
                        intArg(args, 0, Node.UNLIMITED), intArg(args, 1, Node.UNLIMITED), intArg(args, 2, 10));
                default -> throw new IllegalArgumentException("Unknown admin command " + command);
            };
            applyContent(batch);
        } catch (RuntimeException e) {
            character.say("Couldn't run " + command + ": " + e.getMessage());
        }
    }

    private static int intArg(List<String> args, int index, int fallback) {
        return args.size() > index ? Integer.parseInt(args.get(index)) : fallback;
    }

    private GameCharacter loadOrCreate(String name) throws SQLException {
        if (store == null) {
            return new GameCharacter(nextCharacterId++, name);
        }
        GameCharacter character = store.loadCharacter(world, name);
        return character != null ? character : store.createCharacter(world, name);
    }

    /** The only way content changes: save the whole batch, apply it, then tell everyone in one event. */
    void applyContent(ContentBatch batch) {
        if (batch.isEmpty()) {
            return;
        }
        if (store != null) {
            try {
                store.saveBatch(world.id, batch);
            } catch (SQLException e) {
                throw new IllegalStateException("couldn't save new content: " + e.getMessage(), e);
            }
        }
        world.content.apply(batch);
        broadcast(new Event.ContentAdded(batch));
    }

    void tellOthers(GameCharacter except, String text) {
        online.values().stream().filter(c -> c != except).forEach(c -> c.say(text));
    }

    private void broadcast(Event event) {
        online.values().forEach(character -> character.emit(event));
    }

    private static boolean inRange(GameCharacter a, GameCharacter b) {
        return Math.abs(a.x - b.x) <= VIEW_RANGE && Math.abs(a.y - b.y) <= VIEW_RANGE;
    }

    /** Tells characters about others coming into / going out of range. */
    private void updateVisibility() {
        for (GameCharacter viewer : online.values()) {
            for (GameCharacter other : online.values()) {
                if (other == viewer) {
                    continue;
                }
                boolean visible = inRange(viewer, other);
                if (visible && viewer.seen.add(other.id)) {
                    viewer.emit(new Event.EntityEntered(other.info()));
                } else if (!visible && viewer.seen.remove(other.id)) {
                    viewer.emit(new Event.EntityLeft(other.id));
                }
            }
        }
    }
}
