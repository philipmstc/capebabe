package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.generation.WorldGenerator;
import net.shchoo.scafe.scafe.model.ContentBatch;
import net.shchoo.scafe.scafe.model.Direction;
import net.shchoo.scafe.scafe.model.MonsterType;
import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.protocol.Event;
import net.shchoo.scafe.scafe.protocol.GameClock;
import net.shchoo.scafe.scafe.protocol.Intent;

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
    /** You hear about characters and monsters within this many tiles (either axis). The map shows 5 x 3 each way. */
    public static final int VIEW_RANGE = 16;

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

    public GameServer(World world) {
        this(world, null);
    }

    public GameServer(World world, Storage store) {
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

    /** Nodes block movement; players and monsters don't. */
    boolean passable(Position position) {
        return world.content.nodeAt(position) == null;
    }

    /** A tile something new shouldn't appear on: a node, a monster or a player is there. */
    boolean taken(Position position) {
        return !passable(position) || monsters.occupied(position)
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
                        || intent instanceof Intent.Attack) {
                    afterMovement.add(Map.entry(session, intent));
                } else {
                    handle(session, intent);
                }
            }
        }
        sessions.removeIf(session -> session.closed);
        List<Map.Entry<GameCharacter, Event>> steps = new ArrayList<>();
        for (GameCharacter character : online.values()) {
            for (int step = 0; step < (character.running ? 2 : 1); step++) {
                Position before = character.position();
                Walker.Result result = character.walker.tick();
                if (result == Walker.Result.BLOCKED) {
                    character.say("Something's in the way.");
                }
                if (result != Walker.Result.STEPPED) {
                    break;
                }
                // one event per step, even a turn or a bump, so the client can match its predictions one by one
                steps.add(Map.entry(character, new Event.EntityMoved(character.id, character.position(), character.facing)));
                if (!character.position().equals(before)) {
                    character.triggers.add(Discovery.Trigger.MOVED);
                }
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
        combat.regenerate(tick);
        updateVisibility();
        for (GameCharacter changed : online.values()) {
            if (changed.infoChanged) {
                changed.infoChanged = false;
                toObservers(changed.id, new Event.EntityUpdated(changed.info()));
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
            Direction direction = Direction.of(move.dx(), move.dy());
            if (direction != null) {
                character.queueStep(direction);
            }
        } else if (intent instanceof Intent.SetRunning setRunning) {
            character.running = setRunning.running();
            character.saveSoon = true;
            character.emit(new Event.RunningChanged(character.running));
        } else if (intent instanceof Intent.SetColor setColor) {
            if (setColor.colorIndex() >= 0 && setColor.colorIndex() < Intent.PLAYER_COLORS) {
                character.colorIndex = setColor.colorIndex();
                character.infoChanged = true;
                character.saveSoon = true;
                character.emit(new Event.ColorChanged(character.colorIndex));
            }
        } else if (intent instanceof Intent.WalkTo walkTo) {
            character.walkTo(walkTo.target());
        } else if (intent instanceof Intent.Perform) {
            if (character.currentFight != null) {
                combat.endFight(character, "You stop fighting.");
            } else {
                character.performOrStop(nodeAhead(character), tick);
            }
        } else if (intent instanceof Intent.Attack) {
            combat.attack(character, tick);
        } else if (intent instanceof Intent.AddGoal) {
            character.addGoal(nodeAhead(character));
        } else if (intent instanceof Intent.Admin admin) {
            if (session.admin) {
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
        character.connect(session.events::add, nodes);
        character.walker.setPassable(this::passable);
        character.hp = character.maxHp();
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
            if (command.equals("4")) {
                spawnMonsterNextTo(character, !args.isEmpty() && args.get(0).equals("new"));
                return;
            }
            ContentBatch batch = switch (command) {
                case "1" -> WorldGenerator.newSkills(world, intArg(args, 0, 1));
                case "2" -> WorldGenerator.newProductLines(world, intArg(args, 0, 1));
                case "3" -> WorldGenerator.newNode(world, character.ahead(),
                        intArg(args, 0, Node.UNLIMITED), intArg(args, 1, Node.UNLIMITED), intArg(args, 2, 10));
                default -> throw new IllegalArgumentException("Unknown admin command " + command);
            };
            applyContent(batch);
        } catch (RuntimeException e) {
            character.say("Couldn't run " + command + ": " + e.getMessage());
        }
    }

    /** Debug "/4" (random known kind) or "/4 new" (a new kind): a monster right next to you. */
    private void spawnMonsterNextTo(GameCharacter character, boolean newKind) {
        if (newKind || world.content.monsters.isEmpty()) {
            applyContent(WorldGenerator.newMonsterType(world));
        }
        List<MonsterType> kinds = world.content.monsters;
        MonsterType type = newKind ? kinds.get(kinds.size() - 1) : kinds.get(random.nextInt(kinds.size()));
        Position at = Discovery.freeSpot(this, character.position(), 1, 1, random);
        spawnMonster(type, at == null ? character.position() : at);
        character.say("A " + type.name + " (Lv. " + type.level + ") appears. Press f next to it to fight.");
        updateVisibility();
    }

    private static int intArg(List<String> args, int index, int fallback) {
        return args.size() > index ? Integer.parseInt(args.get(index)) : fallback;
    }

    private GameCharacter loadOrCreate(String name) throws Exception {
        return store == null ? new GameCharacter(nextCharacterId++, name) : store.loadOrCreateCharacter(name);
    }

    /** The only way content changes: save the whole batch, apply it, then tell everyone in one event. */
    void applyContent(ContentBatch batch) {
        applyContent(batch, null);
    }

    /** Like {@link #applyContent(ContentBatch)}, also saving {@code grant} in the same transaction. */
    void applyContent(ContentBatch batch, Storage.Grant grant) {
        if (batch.isEmpty()) {
            return;
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
    }

    void tellOthers(GameCharacter except, String text) {
        online.values().stream().filter(c -> c != except).forEach(c -> c.say(text));
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
