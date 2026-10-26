package net.shchoo.scafe.scafe.client;

import com.googlecode.lanterna.Symbols;
import com.googlecode.lanterna.TextColor;
import net.shchoo.scafe.scafe.model.Building;
import net.shchoo.scafe.scafe.model.EquipSlot;
import net.shchoo.scafe.scafe.model.Stacks;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.*;
import net.shchoo.scafe.scafe.protocol.Event;
import net.shchoo.scafe.scafe.protocol.GameClock;
import net.shchoo.scafe.scafe.protocol.GameConnection;
import net.shchoo.scafe.scafe.protocol.Intent;

import java.util.*;

/**
 * The client's view of its own character (plus the other entities it can see). Only changes in response to
 * server {@link Event}s; anything the player wants to do goes out through {@link #send(Intent)}.
 */
public class Player {
    public static final int LEVEL_UP_POPUP_FRAMES = 45;

    /** What the progress bar shows; {@code nodeId} -1 for something that isn't crafting (the train). */
    public record ActionView(int nodeId, String label, long startTick, int durationTicks) {}

    private final GameConnection connection;
    public final WorldContent content = new WorldContent();
    public int id;
    public String name;
    public String worldName;
    public boolean joined;
    /** Where you're drawn: the server's position plus any steps you've taken that it hasn't confirmed yet. */
    public int x;
    public int y;
    /** Which way you're drawn facing (predicted, like x/y). */
    public Direction facing = Direction.DOWN;
    public int colorIndex;

    // Settings
    public boolean running;
    public boolean autoInvestigate;
    // ...and the display ones, kept on this computer, not the server (DisplaySettings)
    public boolean showProductTiers;
    public boolean showTimeEstimate;
    /** Hide routine messages ("Made x") in the chat. */
    public boolean compactChat;
    public boolean lightMode;
    /** Called when a display setting changes (the game saves them). */
    public Runnable onDisplaySettingsChanged = () -> {};
    /** Your lifetime counts in this world. */
    public final Map<Stat, Long> stats = new EnumMap<>(Stat.class);

    /** Where you are and which way you face. */
    record Pose(Position position, Direction facing) {}

    private Pose server = new Pose(new Position(0, 0), Direction.DOWN);
    /** Steps (or turns) sent but not yet confirmed, each with where it should leave you. */
    private final Deque<Pose> predicted = new ArrayDeque<>();
    private final Map<Skill, Long> xp = new HashMap<>();
    /** Times each skill's been prestiged, and the xp banked each time (all of it added up). */
    private final Map<Skill, Integer> prestige = new HashMap<>();
    private final Map<Skill, Long> bankedXp = new HashMap<>();
    public final LinkedHashMap<Product, Integer> inventory = new LinkedHashMap<>();
    /** Your bank's contents, mirrored from the server; {@link #bankOpen} while its window is up. */
    public final LinkedHashMap<Product, Integer> bank = new LinkedHashMap<>();
    public boolean bankOpen;
    /** The station menu is up (q at a station). */
    public boolean stationMenuOpen;
    /** q at a church: its window (heal, or pray for a node) is up. */
    public boolean churchMenuOpen;
    /** The Merch Ant trades with you (you've prestiged); its window is up; its "wants to speak with you" is showing. */
    public boolean metMerchant;
    public boolean merchantMenuOpen;
    public boolean merchantCalling;
    /** Your church blessing (null: none) and the steps / actions it has left ({@link net.shchoo.scafe.scafe.model.Prayer}). */
    public Recipe blessing;
    public int blessingLeft;
    /** The church oracle's last word on the next new skill (null: none yet), and when it came. */
    private Event.OracleSaid oracle;
    private long oracleAt;
    private long bankSentAt = -1;
    /** Render frames so far, for things that flicker. */
    private long frames;
    /** Knocked out players (0 hp) flicker: dark grey this many frames, then their colour as many. */
    public static final int FLICKER_FRAMES = 3;

    /** Every so often, for a moment: the merchant's ant flexing (waving its antennae). */
    public boolean wiggle() {
        return frames % 40 >= 34;
    }

    /** Whether a knocked-out player shows dark grey this frame. */
    public boolean flickerOff() {
        return (frames / FLICKER_FRAMES) % 2 == 0;
    }
    /** What you fight with (null = fists) and wear (null = none), mirrored from the server. */
    public Product weapon;
    public Product armor;
    public final List<Goal> goals = new ArrayList<>();
    private final Set<Integer> triedNodes = new HashSet<>();
    public Position lastActionAt;
    public final Waypoints waypoints = new Waypoints();

    // client side only.
    public Set<Recipe> highlightedRecipes = new HashSet<>();

    /** Login progress, for the login screen: the server's latest answer (cleared once read). */
    public Event.LoginAccepted loginAccepted;
    public String loginRefused;
    private boolean inWorld;
    public ActionView currentAction;
    /** Other players and monsters you can see, by id (monsters have negative ids). */
    public final Map<Integer, Event.EntityInfo> others = new LinkedHashMap<>();
    public int hp = 10;
    public int maxHp = 10;
    /** The monster you're fighting, or null. */
    public Integer fightingId;
    private final Map<Integer, Event.NodeStatus> nodeStatus = new HashMap<>();
    public final List<Popup> popups = new ArrayList<>();
    public final XpTracker xpTracker = new XpTracker();
    private long serverTick;
    /** The tick the stats last came in (time played counts on from there). */
    private long statsTick;
    /** Where a hover + Enter walk is headed (drawn red on the map), or null. Client-only. */
    public Position walkTarget;
    /** The server tick of the last step we took (or of setting the walk target): a walk that stalls gets forgotten. */
    private long lastWalkProgress;
    /** Ticks without a step before a walk target is dropped (no path, something in the way). */
    private static final int WALK_STALL_TICKS = 3;
    private long lastTickAt = System.currentTimeMillis();

    public Player(GameConnection connection) {
        this.connection = connection;
    }

    public void send(Intent intent) {
        connection.send(intent);
    }

    /** Applies everything the server sent since last frame. */
    public void receive() {
        connection.poll().forEach(this::apply);
    }

    public void toggleHighlighting(Recipe recipe) {
        if (highlightedRecipes.contains(recipe)) {
            highlightedRecipes.remove(recipe);
        }
        else {
            highlightedRecipes.add(recipe);
        }
    }

    /** The closest node (in steps, diagonals counting one) that makes {@code recipe}, or null if none is known. */
    public Node nearestNode(Recipe recipe) {
        Node nearest = null;
        int best = Integer.MAX_VALUE;
        for (Node node : content.nodes()) {
            if (!node.recipe.equals(recipe) || status(node).depleted()) {
                continue;
            }
            int distance = stepsTo(node.position);
            if (distance < best || distance == best && node.id < nearest.id) {
                nearest = node;
                best = distance;
            }
        }
        return nearest;
    }

    public int stepsTo(Position position) {
        return Math.max(Math.abs(position.x() - x), Math.abs(position.y() - y));
    }

    public void apply(Event event) {
        if (event instanceof Event.Tick tick) {
            serverTick = tick.tick();
            lastTickAt = System.currentTimeMillis();
        } else if (event instanceof Event.Welcome welcome) {
            welcome(welcome);
            inWorld = true;
        } else if (event instanceof Event.LoginAccepted accepted) {
            loginAccepted = accepted;
        } else if (event instanceof Event.LoginRefused refused) {
            loginRefused = refused.reason();
        } else if (event instanceof Event.ContentAdded added) {
            content.apply(added.batch());
        } else if (event instanceof Event.EntityEntered entered) {
            others.put(entered.entity().id(), entered.entity());
        } else if (event instanceof Event.StepsCleared cleared) {
            server = new Pose(cleared.position(), cleared.facing());
            predicted.clear();
            showPredicted();
        } else if (event instanceof Event.EntityUpdated updated) {
            others.computeIfPresent(updated.entity().id(), (k, info) -> updated.entity());
        } else if (event instanceof Event.EntityLeft left) {
            others.remove(left.id());
        } else if (event instanceof Event.EntityMoved moved) {
            if (moved.id() == id) {
                lastWalkProgress = serverTick;
                server = new Pose(moved.position(), moved.facing());
                if (!predicted.isEmpty() && predicted.peekFirst().equals(server)) {
                    predicted.pollFirst();
                } else {
                    // the server disagrees with what we guessed: snap to where it says we are
                    predicted.clear();
                }
                showPredicted();
            } else {
                others.computeIfPresent(moved.id(), (k, info) -> info.at(moved.position(), moved.facing()));
            }
        } else if (event instanceof Event.ActionStarted started) {
            currentAction = new ActionView(started.nodeId(), started.label(), started.startTick(), started.durationTicks());
            xpTracker.setActive(true);
        } else if (event instanceof Event.ActionStopped) {
            currentAction = null;
            xpTracker.setActive(false);
        } else if (event instanceof Event.XpGained gained) {
            Skill skill = content.skill(gained.skillId());
            xp.put(skill, gained.totalXp());
            xpTracker.record(skill, gained.amount());
            popups.add(new Popup(gained.at(), "+" + gained.amount() + "xp", SkillStyle.getColorForSkill(skill)));
        } else if (event instanceof Event.OracleSaid said) {
            oracle = said;
            oracleAt = System.currentTimeMillis();
        } else if (event instanceof Event.MerchantCalls) {
            metMerchant = true;
            merchantCalling = true;
        } else if (event instanceof Event.BlessingChanged changed) {
            blessing = changed.recipeId() < 0 ? null : content.recipe(changed.recipeId());
            blessingLeft = changed.left();
        } else if (event instanceof Event.Prestiged prestiged) {
            Skill skill = content.skill(prestiged.skillId());
            xp.put(skill, 0L);
            prestige.put(skill, prestiged.prestige());
            bankedXp.put(skill, prestiged.bankedXp());
        } else if (event instanceof Event.LevelUp levelUp) {
            onLevelUp(content.skill(levelUp.skillId()), levelUp.from(), levelUp.to());
        } else if (event instanceof Event.InventoryChanged changed) {
            inventory.put(content.product(changed.productId()), changed.count());
        } else if (event instanceof Event.BankChanged changed) {
            Product item = content.product(changed.productId());
            if (changed.count() <= 0) {
                bank.remove(item);
            } else {
                bank.put(item, changed.count());
            }
        } else if (event instanceof Event.GoalAdded added) {
            goals.add(added.goal().toGoal(content));
        } else if (event instanceof Event.EquipmentChanged changed) {
            Product item = changed.productId() < 0 ? null : content.product(changed.productId());
            if (changed.slot() == EquipSlot.WEAPON) {
                weapon = item;
            } else {
                armor = item;
            }
        } else if (event instanceof Event.GoalChanged changed) {
            if (changed.index() < goals.size()) {
                goals.set(changed.index(), changed.goal().toGoal(content));
            }
        } else if (event instanceof Event.NodeTried tried) {
            triedNodes.add(tried.nodeId());
        } else if (event instanceof Event.NodeChanged changed) {
            nodeStatus.put(changed.status().nodeId(), changed.status());
        } else if (event instanceof Event.FightStarted started) {
            fightingId = started.monsterId();
        } else if (event instanceof Event.FightEnded) {
            fightingId = null;
        } else if (event instanceof Event.ColorChanged changed) {
            colorIndex = changed.colorIndex();
        } else if (event instanceof Event.RunningChanged changed) {
            running = changed.running();
        } else if (event instanceof Event.AutoInvestigateChanged changed) {
            autoInvestigate = changed.on();
        } else if (event instanceof Event.HpChanged changed) {
            hp = changed.hp();
            maxHp = changed.maxHp();
        } else if (event instanceof Event.Hit hit) {
            boolean me = hit.targetId() == id;
            popups.add(Popup.hit(hit.at(), String.valueOf(hit.damage()),
                    hit.damage() == 0 ? TextColor.ANSI.BLUE : TextColor.ANSI.RED, hitSide(hit, me)));
        } else if (event instanceof Event.HomeChanged home) {
            lastActionAt = home.lastActionAt();
        } else if (event instanceof Event.Message message) {
            GameLog.say(message.kind() == null ? Event.MessageKind.INFO : message.kind(), message.text());
        } else if (event instanceof Event.Chat chat) {
            GameLog.chat(chat.from(), chat.colorIndex(), chat.text());
        } else if (event instanceof Event.StatsChanged changed) {
            setStats(changed.stats());
        }
    }

    private void welcome(Event.Welcome welcome) {
        welcome.content().stream().filter(b -> b.version() > content.version()).forEach(content::apply);
        Event.CharacterSnapshot you = welcome.you();
        id = you.id();
        name = you.name();
        worldName = welcome.worldName();
        server = new Pose(you.position(), you.facing());
        predicted.clear();
        showPredicted();
        colorIndex = you.colorIndex();
        running = you.running();
        autoInvestigate = you.autoInvestigate();
        bank.clear();
        you.bank().forEach((productId, count) -> bank.put(content.product(productId), count));
        weapon = you.weaponId() < 0 ? null : content.product(you.weaponId());
        blessing = you.blessingRecipeId() < 0 ? null : content.recipe(you.blessingRecipeId());
        blessingLeft = you.blessingLeft();
        metMerchant = you.metMerchant();
        armor = you.armorId() < 0 ? null : content.product(you.armorId());
        setStats(you.stats());
        you.xp().forEach((skillId, amount) -> xp.put(content.skill(skillId), amount));
        prestige.clear();
        bankedXp.clear();
        if (you.prestige() != null) {
            you.prestige().forEach((skillId, times) -> prestige.put(content.skill(skillId), times));
        }
        if (you.bankedXp() != null) {
            you.bankedXp().forEach((skillId, banked) -> bankedXp.put(content.skill(skillId), banked));
        }
        you.inventory().forEach((productId, count) -> inventory.put(content.product(productId), count));
        you.goals().forEach(def -> goals.add(def.toGoal(content)));
        triedNodes.addAll(you.triedNodes());
        lastActionAt = you.lastActionAt();
        welcome.others().forEach(other -> others.put(other.id(), other));
        welcome.nodes().forEach(status -> nodeStatus.put(status.nodeId(), status));
        hp = you.hp();
        maxHp = you.maxHp();
        serverTick = welcome.tick();
        statsTick = serverTick;
        lastTickAt = System.currentTimeMillis();
        joined = true;
        GameLog.say(Event.MessageKind.NOTICE, "Welcome to " + worldName + ", " + name + "!");
    }

    private void onLevelUp(Skill skill, int from, int to) {
        GameLog.say(Event.MessageKind.NOTICE, skill.name + " is now Lv. " + to + "!");
        popups.add(Popup.following(this::position, " " + skill.name + " Lv. " + to + "! ",
                TextColor.ANSI.BLACK, SkillStyle.getColorForSkill(skill), LEVEL_UP_POPUP_FRAMES));
        List<String> unlocked = skill.productLines.stream()
                .flatMap(pl -> pl.recipes.stream())
                .filter(r -> r.levelNeeded > from && r.levelNeeded <= to)
                .map(r -> r.product.name)
                .toList();
        if (!unlocked.isEmpty()) {
            GameLog.say(Event.MessageKind.NOTICE, "Unlocked: " + String.join(", ", unlocked));
        }
        for (Goal goal : goals) {
            if (goal.skill().equals(skill) && goal.level() > from && goal.level() <= to) {
                GameLog.say(Event.MessageKind.NOTICE, "Goal complete: " + goal.describe());
            }
        }
    }

    /** One render frame. */
    public void frame() {
        frames++;
        if (pendingBuilding != null && ahead().equals(pendingBuilding)) {
            pendingBuilding = null;
            walkTarget = null;
            perform();
        } else if (pendingBuilding != null && walkTarget == null) {
            pendingBuilding = null;
        }
        popups.removeIf(Popup::advanceFrame);
        waypoints.update(ahead());
        if (walkTarget != null && (walkTarget.equals(new Position(x, y)) || walkTarget.equals(ahead())
                || serverTick - lastWalkProgress > WALK_STALL_TICKS)) {
            walkTarget = null;
        }
    }

    /** Walk (or run) to {@code target}, around nodes; it's shown on the map until you get there. */
    public void walkTo(Position target) {
        walkTarget = target;
        lastWalkProgress = serverTick;
        send(new Intent.WalkTo(target));
    }

    /** 0.0 right after a server tick, approaching 1.0 just before the next one. */
    public double partialTick() {
        return Math.min(1.0, Math.max(0.0, (double) (System.currentTimeMillis() - lastTickAt) / GameClock.TICK_MILLIS));
    }

    /**
     * The action bar shows full for this many frames before the next item (or the wait) ends, however long it is,
     * so you see it finish. 5 frames (300ms) is what a normal 2-tick item always had.
     */
    static final int FULL_FRAMES = 5;

    /** How full the action bar is, 0..1. The action repeats, so this wraps every durationTicks. */
    public double actionFraction() {
        if (currentAction == null) {
            return 0;
        }
        int duration = currentAction.durationTicks();
        long into = Math.floorMod(serverTick - currentAction.startTick(), duration);
        return barFraction(into + partialTick(), duration);
    }

    /**
     * {@code elapsed} ticks into a {@code duration}-tick action: it fills over the duration less
     * {@link #FULL_FRAMES}' worth (never less than half of it, for very short ones), then stays full.
     */
    static double barFraction(double elapsed, int duration) {
        double hold = FULL_FRAMES * (double) GameClock.FRAME_MILLIS / GameClock.TICK_MILLIS;
        double fill = Math.max(duration / 2.0, duration - hold);
        return Math.min(1.0, elapsed / fill);
    }

    /**
     * Which side of its tile a hit's popup goes: away from whoever hit, so the two fighters' popups don't overlap
     * (straight above / below each other: yours left, the other's right).
     */
    static int hitSide(Event.Hit hit, boolean me) {
        if (hit.from() == null) {
            return 0;
        }
        int away = Integer.signum(hit.at().x() - hit.from().x());
        return away != 0 ? away : me ? -1 : 1;
    }

    /** The monster you're fighting, if you can still see it. */
    public Event.EntityInfo fightingMonster() {
        return fightingId == null ? null : others.get(fightingId);
    }

    public List<Event.EntityInfo> othersAt(Position position) {
        return others.values().stream().filter(other -> other.position().equals(position)).toList();
    }

    public Position position() {
        return new Position(x, y);
    }

    /**
     * Turns you that way and steps there unless a node is in the way, on screen right away (prediction, using the
     * same rule the server does), and asks the server to do the same.
     */
    public void moveBy(int dx, int dy) {
        walkTarget = null;
        pendingBuilding = null;
        Direction direction = Direction.of(dx, dy);
        if (direction == null || predicted.size() >= Intent.MAX_QUEUED_STEPS) {
            return;
        }
        Position target = position().step(direction);
        predicted.addLast(new Pose(content.blocked(target) ? position() : target, direction));
        showPredicted();
        send(new Intent.Move(dx, dy));
    }

    private void showPredicted() {
        Pose shown = predicted.isEmpty() ? server : predicted.peekLast();
        x = shown.position().x();
        y = shown.position().y();
        facing = shown.facing();
    }

    /** Where the server last said you are (what actions and other players go by). */
    public Position serverPosition() {
        return server.position();
    }

    /** The tile you're facing: what q / g act on and what the Map tab shows by default. */
    public Position ahead() {
        return position().step(facing);
    }

    /** Shown right away (so quick presses add up) and confirmed by the server's ColorChanged. */
    public void setColor(int index) {
        colorIndex = Math.floorMod(index, Intent.PLAYER_COLORS);
        send(new Intent.SetColor(colorIndex));
    }

    /** Use an inventory item on the node at {@code target} (the server walks you over if needed). */
    /** Fight this monster: you walk up to it first (following it a while) if need be. */
    public void fight(int monsterId) {
        pendingBuilding = null;
        send(new Intent.Fight(monsterId));
    }

    /**
     * Walk up to the building at {@code tile} and use it as q would (the bank, station or church window opens) once you're facing it: the nearest of its tiles you can stand beside.
     */
    public void useBuilding(Building building) {
        Position best = null;
        int bestSteps = Integer.MAX_VALUE;
        for (Position tile : building.solidTiles()) {
            for (Direction d : Direction.values()) {
                Position beside = tile.step(d);
                if (!content.blocked(beside) && stepsTo(beside) < bestSteps) {
                    best = tile;
                    bestSteps = stepsTo(beside);
                }
            }
        }
        if (best == null) {
            return;
        }
        walkTo(best);
        pendingBuilding = best;
    }

    /** Walking up to use this building tile ({@link #useBuilding}), or null. */
    private Position pendingBuilding;

    /** Craft at the node at {@code target}, walking over if need be; {@code afk}: half the xp, waits out respawns. */
    public void craft(Position target, boolean afk) {
        send(new Intent.Craft(target, afk));
    }

    public void useItem(Product item, Position target) {
        send(new Intent.UseItem(item.id, target));
    }

    /** Step 1 of joining (the server answers LoginAccepted / LoginRefused). */
    public void login(String name, String password) {
        loginAccepted = null;
        loginRefused = null;
        send(new Intent.Login(Intent.PROTOCOL_VERSION, name, password));
    }

    /** Step 2: the world's password, or null if it doesn't need one. */
    public void enterWorld(String worldPassword) {
        loginRefused = null;
        send(new Intent.EnterWorld(worldPassword));
    }

    /** True once the server has let you in (Welcome). */
    public boolean inWorld() {
        return inWorld;
    }

    /** Time played so far, in ticks: the last count the server sent, plus the ticks since. */
    public long timePlayedTicks() {
        return stats.getOrDefault(Stat.TIME_PLAYED, 0L) + Math.max(0, serverTick - statsTick);
    }

    private void setStats(Map<String, Long> byName) {
        statsTick = serverTick;
        stats.clear();
        if (byName != null) {
            byName.forEach((name, value) -> {
                try {
                    stats.put(Stat.valueOf(name), value);
                } catch (IllegalArgumentException newer) {
                    // a stat from a newer server: nothing here shows it
                }
            });
        }
    }

    /** Say something to everyone in the world. */
    public void say(String text) {
        send(new Intent.Chat(text));
    }

    /** {@code step} 1 = up to the next of {@code Goal.LEVELS}, -1 = down, never to your level or below; the server confirms. */
    public void stepGoalLevel(int index, int step) {
        Goal goal = goals.get(index);
        int level = goal.stepped(step);
        if (level != goal.level() && level > getLevel(goal.skill())) {
            send(new Intent.SetGoalLevel(index, level));
        }
    }

    /** Fight with / wear {@code item} until every one of them is used up. */
    public void equip(EquipSlot slot, Product item) {
        send(new Intent.Equip(slot, item.id));
    }

    public void unequip(EquipSlot slot) {
        send(new Intent.Unequip(slot));
    }

    public Product equipped(EquipSlot slot) {
        return slot == EquipSlot.WEAPON ? weapon : armor;
    }

    /** How many of {@code item} make a stack for you ({@link Stacks}). */
    public int stackSize(Product item) {
        return Stacks.size(content, item, this::stackLevel);
    }

    /**
     * q: use the node ahead (the server decides). Facing a bank, its window opens right away: the bank's contents
     * are already here, so there's nothing to wait for.
     */
    public void perform() {
        Building building = content.solidBuildingAt(ahead());
        if (building != null && building.kind() == Building.Kind.BANK) {
            bankOpen = true;
        } else if (building != null && building.kind() == Building.Kind.STATION) {
            stationMenuOpen = true;
        } else if (building != null && building.kind() == Building.Kind.CHURCH) {
            churchMenuOpen = true;
        } else if (building != null && building.kind() == Building.Kind.MERCHANT && metMerchant) {
            merchantMenuOpen = true;
        }
        send(new Intent.Perform());
    }

    /** The church oracle's word, its minutes counted on since it came (down to the skill, then up past it); null: none. */
    public Event.OracleSaid oracle() {
        if (oracle == null || !oracle.known()) {
            return oracle;
        }
        return new Event.OracleSaid(true, oracle.minutes() - (System.currentTimeMillis() - oracleAt) / 60_000, oracle.fold());
    }

    /** At a church: give it {@code count} of {@code item} (a tiny new-skill roll). */
    public void tithe(Product item, int count) {
        send(new Intent.Tithe(item.id, count));
    }

    /** How many one craft of {@code recipe} makes at your level ({@link Tiers}): 1, 2 or 3. */
    public int madePerCraft(Recipe recipe) {
        return Tiers.yield(Tiers.of(recipe), getLevel(recipe.skill));
    }

    /**
     * The progress bar's words: what you're doing, with how many a craft makes now in front when it's more than one
     * ("2 x skill0{0}-0 (AFK)"; not while waiting for the node to come back).
     */
    public String actionLabel() {
        if (currentAction == null) {
            return null;
        }
        Node node = content.node(currentAction.nodeId());
        if (node == null || !currentAction.label().startsWith(node.label())) {
            return currentAction.label();
        }
        int made = madePerCraft(node.recipe);
        return (made > 1 ? made + " x " : "") + currentAction.label();
    }

    /** At the Merch Ant: {@code count} of a prestige item for 1 + its prestige of the plain one each. */
    public void trade(Product item, int count) {
        send(new Intent.Trade(item.id, count));
    }

    /** At a church: all your hp back. */
    public void heal() {
        send(new Intent.Heal());
    }

    /** At a church: a blessing on {@code recipe}, paid with {@code payment} (the server takes it). */
    public void pray(Recipe recipe, Product payment) {
        send(new Intent.Pray(recipe.id, payment.id));
    }

    /** Off to the station with this id (the server takes the fare). */
    public void rideTo(int stationId) {
        send(new Intent.Ride(stationId));
    }

    public void deposit(Product item, int count) {
        bankSentAt = serverTick;
        send(new Intent.Deposit(item.id, count));
    }

    public void withdraw(Product item, int count) {
        bankSentAt = serverTick;
        send(new Intent.Withdraw(item.id, count));
    }

    /** A deposit / withdrawal is on its way and the server hasn't had its tick yet. */
    public boolean bankBusy() {
        return serverTick <= bankSentAt;
    }

    /** Inventory slots in use, out of {@link Stacks#INVENTORY_SLOTS}. */
    public int slotsUsed() {
        return Stacks.slots(content, inventory, this::stackLevel);
    }

    public void toggleLightMode() {
        lightMode = !lightMode;
        onDisplaySettingsChanged.run();
    }

    public void toggleCompactChat() {
        compactChat = !compactChat;
        onDisplaySettingsChanged.run();
    }

    public void toggleRunning() {
        running = !running;
        send(new Intent.SetRunning(running));
    }

    public void toggleAutoInvestigate() {
        autoInvestigate = !autoInvestigate;
        send(new Intent.SetAutoInvestigate(autoInvestigate));
    }

    public void toggleShowProductTiers() {
        showProductTiers = !showProductTiers;
        onDisplaySettingsChanged.run();
    }

    public void toggleShowTimeEstimate() {
        showTimeEstimate = !showTimeEstimate;
        onDisplaySettingsChanged.run();
    }

    public int getLevel(Skill skill) {
        return Levels.levelFor(getExperience(skill));
    }

    public Long getExperience(Skill skill) {
        return xp.getOrDefault(skill, 0L);
    }

    public Long getExperienceToNextLevel(Skill skill) {
        return Levels.xpToFinish(getLevel(skill));
    }

    public boolean isLockedFor(Node node) {
        return !node.recipe.levelMet(getLevel(node.skill()));
    }

    /** Live state of a node (charges, empty, users); defaults to full and unused. */
    public Event.NodeStatus status(Node node) {
        return nodeStatus.getOrDefault(node.id, new Event.NodeStatus(node.id, node.maxCharges, false, 0));
    }

    public boolean hasTried(Node node) {
        return triedNodes.contains(node.id);
    }

    /**
     * Every skill's level added up, skills you haven't trained counting as 1 -- the same number others see for you
     * on hover (skills in the world + levels gained).
     */
    /** Every skill's level, plus 99 for each prestige (the levels it took): it never goes down. */
    public int totalLevel() {
        return content.skills.stream().mapToInt(s -> getLevel(s) + getPrestige(s) * (Levels.MAX_LEVEL - 1)).sum();
    }

    /** All the xp you've ever gained, banked prestiges included. */
    public long totalExperience() {
        return content.skills.stream().mapToLong(this::lifetimeExperience).sum();
    }

    public int getPrestige(Skill skill) {
        return prestige.getOrDefault(skill, 0);
    }

    /** What a skill's items stack to: its virtual level, from all its xp across prestiges ({@link Stacks}). */
    public int stackLevel(Skill skill) {
        return Levels.virtualLevelFor(lifetimeExperience(skill));
    }

    /** A skill's xp across all its prestiges: what's banked plus what it has now. */
    public long lifetimeExperience(Skill skill) {
        return bankedXp.getOrDefault(skill, 0L) + getExperience(skill);
    }

    /** At Lv. 100: start the skill over (see {@code GameCharacter.prestige}). */
    public void prestige(Skill skill) {
        send(new Intent.Prestige(skill.id));
    }

    public int highestLevel() {
        return content.skills.stream().mapToInt(this::getLevel).max().orElse(1);
    }

    /** One-of-a-kind monster drops you found (each drops once per world, so every one is a different item). */
    public long trophies() {
        return content.monsters.stream().flatMap(monster -> monster.drops.stream())
                .filter(drop -> name != null && name.equals(drop.foundBy())).count();
    }
}
