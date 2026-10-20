package net.shchoo.scafe.scafe.client;

import com.googlecode.lanterna.TextColor;
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

    /** What the progress bar shows. */
    public record ActionView(String label, long startTick, int durationTicks) {}

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
    public boolean showProductTiers;
    public boolean showTimeEstimate;

    /** Where you are and which way you face. */
    record Pose(Position position, Direction facing) {}

    private Pose server = new Pose(new Position(0, 0), Direction.DOWN);
    /** Steps (or turns) sent but not yet confirmed, each with where it should leave you. */
    private final Deque<Pose> predicted = new ArrayDeque<>();
    private final Map<Skill, Long> xp = new HashMap<>();
    public final LinkedHashMap<Product, Integer> inventory = new LinkedHashMap<>();
    public final List<Goal> goals = new ArrayList<>();
    private final Set<Integer> triedNodes = new HashSet<>();
    public Position lastActionAt;
    public final Waypoints waypoints = new Waypoints();
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
            currentAction = new ActionView(started.label(), started.startTick(), started.durationTicks());
            xpTracker.setActive(true);
        } else if (event instanceof Event.ActionStopped) {
            currentAction = null;
            xpTracker.setActive(false);
        } else if (event instanceof Event.XpGained gained) {
            Skill skill = content.skill(gained.skillId());
            xp.put(skill, gained.totalXp());
            xpTracker.record(skill, gained.amount());
            popups.add(new Popup(gained.at(), "+" + gained.amount() + "xp", SkillStyle.getColorForSkill(skill)));
        } else if (event instanceof Event.LevelUp levelUp) {
            onLevelUp(content.skill(levelUp.skillId()), levelUp.from(), levelUp.to());
        } else if (event instanceof Event.InventoryChanged changed) {
            inventory.put(content.product(changed.productId()), changed.count());
        } else if (event instanceof Event.GoalAdded added) {
            goals.add(added.goal().toGoal(content));
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
        } else if (event instanceof Event.ShowProductTiersChanged changed) {
            showProductTiers = changed.on();
        } else if (event instanceof Event.ShowTimeEstimateChanged changed) {
            showTimeEstimate = changed.on();
        } else if (event instanceof Event.HpChanged changed) {
            hp = changed.hp();
            maxHp = changed.maxHp();
        } else if (event instanceof Event.Hit hit) {
            boolean me = hit.targetId() == id;
            popups.add(new Popup(hit.at(), hit.damage() == 0 ? "miss" : "-" + hit.damage(),
                    hit.damage() == 0 ? TextColor.ANSI.BLACK_BRIGHT : me ? TextColor.ANSI.RED_BRIGHT : TextColor.ANSI.YELLOW_BRIGHT));
        } else if (event instanceof Event.HomeChanged home) {
            lastActionAt = home.lastActionAt();
        } else if (event instanceof Event.Message message) {
            GameLog.say(message.text());
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
        showProductTiers = you.showProductTiers();
        showTimeEstimate = you.showTimeEstimate();
        you.xp().forEach((skillId, amount) -> xp.put(content.skill(skillId), amount));
        you.inventory().forEach((productId, count) -> inventory.put(content.product(productId), count));
        you.goals().forEach(def -> goals.add(def.toGoal(content)));
        triedNodes.addAll(you.triedNodes());
        lastActionAt = you.lastActionAt();
        welcome.others().forEach(other -> others.put(other.id(), other));
        welcome.nodes().forEach(status -> nodeStatus.put(status.nodeId(), status));
        hp = you.hp();
        maxHp = you.maxHp();
        serverTick = welcome.tick();
        lastTickAt = System.currentTimeMillis();
        joined = true;
        GameLog.say("Welcome to " + worldName + ", " + name + "!");
    }

    private void onLevelUp(Skill skill, int from, int to) {
        GameLog.say(skill.name + " is now Lv. " + to + "!");
        popups.add(Popup.following(this::position, " " + skill.name + " Lv. " + to + "! ",
                TextColor.ANSI.BLACK, SkillStyle.getColorForSkill(skill), LEVEL_UP_POPUP_FRAMES));
        List<String> unlocked = skill.productLines.stream()
                .flatMap(pl -> pl.recipes.stream())
                .filter(r -> r.levelNeeded > from && r.levelNeeded <= to)
                .map(r -> r.product.name)
                .toList();
        if (!unlocked.isEmpty()) {
            GameLog.say("Unlocked: " + String.join(", ", unlocked));
        }
        for (Goal goal : goals) {
            if (goal.skill().equals(skill) && goal.level() > from && goal.level() <= to) {
                GameLog.say("Goal complete: " + goal.describe());
            }
        }
    }

    /** One render frame. */
    public void frame() {
        popups.removeIf(Popup::advanceFrame);
        waypoints.update(ahead());
    }

    /** 0.0 right after a server tick, approaching 1.0 just before the next one. */
    public double partialTick() {
        return Math.min(1.0, Math.max(0.0, (double) (System.currentTimeMillis() - lastTickAt) / GameClock.TICK_MILLIS));
    }

    /** How full the progress bar is, 0..1. The action repeats, so this wraps every durationTicks. */
    public double actionFraction() {
        if (currentAction == null) {
            return 0;
        }
        long into = Math.floorMod(serverTick - currentAction.startTick(), currentAction.durationTicks());
        return Math.min(1.0, (into + partialTick()) / currentAction.durationTicks());
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
        Direction direction = Direction.of(dx, dy);
        if (direction == null || predicted.size() >= Intent.MAX_QUEUED_STEPS) {
            return;
        }
        Position target = position().step(direction);
        predicted.addLast(new Pose(content.nodeAt(target) == null ? target : position(), direction));
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

    public void toggleRunning() {
        running = !running;
        send(new Intent.SetRunning(running));
    }

    public void toggleAutoInvestigate() {
        autoInvestigate = !autoInvestigate;
        send(new Intent.SetAutoInvestigate(autoInvestigate));
    }

    public void toggleShowProductTiers() {
        showProductTiers = ! showProductTiers;
        send(new Intent.SetShowProductTiers(showProductTiers));
    }

    public void toggleShowTimeEstimate() {
        showTimeEstimate = !showTimeEstimate;
        send(new Intent.SetShowTimeEstimate(showTimeEstimate));
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
}
