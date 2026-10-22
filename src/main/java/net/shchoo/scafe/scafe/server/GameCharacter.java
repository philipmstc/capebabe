package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.model.EquipSlot;
import net.shchoo.scafe.scafe.model.Direction;
import net.shchoo.scafe.scafe.model.Goal;
import net.shchoo.scafe.scafe.model.Levels;
import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.Recipe;
import net.shchoo.scafe.scafe.model.Skill;
import net.shchoo.scafe.scafe.model.SkillType;
import net.shchoo.scafe.scafe.protocol.Event;
import net.shchoo.scafe.scafe.model.Stat;

import java.util.*;
import java.util.function.Consumer;

/** A player's character as the server sees it: the authoritative state. Everything it does is reported as events. */
public class GameCharacter implements Walker.Mobile {
    public final int id;
    public final String name;
    public int x;
    public int y;
    public Direction facing = Direction.DOWN;
    /** Index into the clients' player palette ({@link net.shchoo.scafe.scafe.protocol.Intent#PLAYER_COLORS}). */
    public int colorIndex;
    /** Running = two steps per tick. */
    public boolean running;
    /** While moving, head for nodes that appear nearby. */
    public boolean autoInvestigate;
    public boolean showProductTiers;
    public boolean showTimeEstimate;
    public boolean compactChat;
    /** Lifetime counts (see {@link Stat}); sent to the client at the end of a tick in which they changed. */
    public final Map<Stat, Long> stats = new EnumMap<>(Stat.class);
    boolean statsChanged;
    /** The last tick this character took a step (or turned), for "is moving right now". */
    long lastSteppedTick = -1;
    /** Walking over to use an item on the node here; its action starts on arrival. */
    Position pendingUse;
    public final Map<Skill, Long> xp = new HashMap<>();
    public final LinkedHashMap<Product, Integer> inventory = new LinkedHashMap<>();
    /** What you fight with (null = fists) and wear (null = none); used up from the inventory, one at a time. */
    public Product weapon;
    public Product armor;
    public final List<Goal> goals = new ArrayList<>();
    /** Nodes this character has tried while too low level for them. */
    public final Set<Integer> triedNodes = new HashSet<>();
    public Position lastActionAt;
    final Walker walker = new Walker(this);
    ActionProgress currentAction;
    Combat.Fight currentFight;
    /** Not saved: you log in at full hp. */
    int hp = Combat.maxHp(1);
    /** Set on milestones (level ups) so the server saves this character at the end of the tick. */
    boolean saveSoon;
    /** Set when something others can see about this character changed (level ups). */
    boolean infoChanged;
    /** Things this character did this tick that might lead to discoveries; the server drains them. */
    final List<Discovery.Trigger> triggers = new ArrayList<>();
    /** Other characters and monsters this one has been told about (within view range). */
    final Set<Integer> seen = new HashSet<>();
    private Consumer<Event> outbox = event -> {};
    private NodeStates nodes = new NodeStates(event -> {});

    public GameCharacter(int id, String name) {
        this.id = id;
        this.name = name;
    }

    void connect(Consumer<Event> outbox, NodeStates nodes) {
        this.outbox = outbox;
        this.nodes = nodes;
    }

    void disconnect() {
        this.outbox = event -> {};
    }

    void emit(Event event) {
        outbox.accept(event);
    }

    void say(String text) {
        say(Event.MessageKind.INFO, text);
    }

    void say(Event.MessageKind kind, String text) {
        emit(new Event.Message(text, kind));
    }

    void count(Stat stat) {
        stats.merge(stat, 1L, Long::sum);
        statsChanged = true;
    }

    Map<String, Long> statsByName() {
        Map<String, Long> named = new LinkedHashMap<>();
        stats.forEach((stat, value) -> named.put(stat.name(), value));
        return named;
    }

    @Override
    public Position position() {
        return new Position(x, y);
    }

    @Override
    public void moveTo(Position position) {
        x = position.x();
        y = position.y();
    }

    @Override
    public void face(Direction direction) {
        facing = direction;
    }

    /** The tile you're facing: what you act on. */
    public Position ahead() {
        return position().step(facing);
    }

    public int getLevel(Skill skill) {
        return Levels.levelFor(getExperience(skill));
    }

    public long getExperience(Skill skill) {
        return xp.getOrDefault(skill, 0L);
    }

    /** Your combat skill's level (1 until the world has one / you've trained it). */
    public int combatLevel() {
        return xp.keySet().stream().filter(s -> s.type == SkillType.COMBAT).mapToInt(this::getLevel).max().orElse(1);
    }

    public int maxHp() {
        return Combat.maxHp(combatLevel());
    }

    public boolean isLockedFor(Node node) {
        return !node.recipe.levelMet(getLevel(node.skill()));
    }

    public boolean hasTried(Node node) {
        return triedNodes.contains(node.id);
    }

    public boolean canPerform(Node node) {
        return !isLockedFor(node) && has(node.recipe.components);
    }

    /** Start using the node, or stop if busy. */
    void performOrStop(Node node, long tick) {
        pendingUse = null;
        if (currentAction != null) {
            stopAction("Stopped.");
        } else {
            startAction(node, tick);
        }
    }

    /**
     * Use {@code item} on the node at {@code target}: it has to be one of that node's components. Facing it, the
     * node's action starts (instead of whatever you were doing); otherwise you walk over and it starts on arrival.
     */
    void useItem(Product item, Node node, Position target, long tick) {
        pendingUse = null;
        if (item == null || inventory.getOrDefault(item, 0) <= 0) {
            say("You don't have that.");
        } else if (node == null) {
            say("There's nothing there to use it on.");
        } else if (!node.recipe.components.containsKey(item)) {
            say("Nothing interesting happens.");
        } else if (ahead().equals(target)) {
            if (currentAction != null && currentAction.node.equals(node)) {
                say("You're already on it.");
                return;
            }
            if (currentAction != null) {
                stopAction(null);
            }
            startAction(node, tick);
        } else {
            Position at = position();
            if (Math.abs(target.x() - at.x()) + Math.abs(target.y() - at.y()) == 1) {
                queueStep(Direction.of(target.x() - at.x(), target.y() - at.y()));
            } else {
                walkTo(target);
            }
            if (walker.isWalking()) {
                pendingUse = target;
            }
        }
    }

    /** After walking: start the use that was waiting for us to arrive, if we got there. */
    void arrive(Node node, long tick) {
        if (pendingUse == null || walker.isWalking()) {
            return;
        }
        Position target = pendingUse;
        pendingUse = null;
        if (!ahead().equals(target) || node == null) {
            say("Couldn't get to it.");
            return;
        }
        if (currentAction != null) {
            stopAction(null);
        }
        startAction(node, tick);
    }

    private void startAction(Node node, long tick) {
        if (node == null) {
            say("Nothing to do here...");
        } else if (!canPerform(node)) {
            perform(node);
        } else {
            String busy = nodes.whyNot(node, id);
            if (busy != null) {
                say(busy);
                return;
            }
            stopWalking();
            nodes.join(node, id);
            currentAction = new ActionProgress(node, this, tick);
            emit(new Event.ActionStarted(node.id, node.label(), tick, node.durationTicks()));
        }
    }

    void queueStep(Direction direction) {
        pendingUse = null;
        if (!walker.queueStep(direction)) {
            emit(new Event.StepsCleared(position(), facing));
        }
    }

    void walkTo(Position target) {
        pendingUse = null;
        if (walker.walkTo(target)) {
            emit(new Event.StepsCleared(position(), facing));
        }
        if (!walker.isWalking() && !target.equals(position())) {
            say("Can't find a way there.");
        }
    }

    void stopWalking() {
        if (walker.stop()) {
            emit(new Event.StepsCleared(position(), facing));
        }
    }

    void stopAction(String reason) {
        if (currentAction == null) {
            return;
        }
        nodes.leave(currentAction.node, id);
        currentAction = null;
        if (reason != null) {
            say(Event.MessageKind.ROUTINE, reason);
        }
        emit(new Event.ActionStopped());
    }

    /** Makes the node's product once, or says why not. Returns whether something was made. */
    boolean perform(Node node) {
        Recipe recipe = node.recipe;
        if (isLockedFor(node)) {
            if (triedNodes.add(node.id)) {
                emit(new Event.NodeTried(node.id));
            }
            say("Need " + recipe.skill.name + " Lv. " + recipe.levelNeeded + " (you're Lv. " + getLevel(recipe.skill) + ")");
            say("(press g to make it a goal)");
            return false;
        }
        if (!attemptToUseAll(recipe.components)) {
            return false;
        }
        changeInventory(recipe.product, 1);
        lastActionAt = position();
        emit(new Event.HomeChanged(lastActionAt));
        say(Event.MessageKind.ROUTINE, "Made " + recipe.product.name);
        triggers.add(Discovery.Trigger.MADE);
        count(Stat.ITEMS_CRAFTED);
        gainExperience(recipe.skill, recipe.expGained);
        return true;
    }

    NodeStates nodes() {
        return nodes;
    }

    void gainExperience(Skill skill, long gained) {
        int before = getLevel(skill);
        long total = getExperience(skill) + gained;
        xp.put(skill, total);
        emit(new Event.XpGained(skill.id, gained, total, position()));
        int after = getLevel(skill);
        if (after > before) {
            saveSoon = true;
            infoChanged = true;
            triggers.add(Discovery.Trigger.LEVELLED);
            emit(new Event.LevelUp(skill.id, before, after));
            if (skill.type == SkillType.COMBAT) {
                hp += Combat.maxHp(after) - Combat.maxHp(before);
                emit(new Event.HpChanged(hp, maxHp()));
            }
        }
    }

    void addGoal(Node node) {
        if (node == null) {
            say("Nothing here to aim for.");
        } else if (!isLockedFor(node)) {
            say("You can already do that!");
        } else if (!hasTried(node)) {
            say("Try it first (q).");
        } else {
            Goal goal = new Goal(node.skill(), node.recipe.levelNeeded, node.recipe.product);
            if (goals.contains(goal)) {
                say("Already a goal.");
            } else {
                goals.add(goal);
                emit(new Event.GoalAdded(Event.GoalDef.of(goal)));
                say(Event.MessageKind.NOTICE, "New goal: " + goal.describe());
            }
        }
    }

    void setGoalLevel(int index, int level) {
        if (index < 0 || index >= goals.size() || !Goal.allowed(level)) {
            return;
        }
        Goal goal = goals.get(index).withLevel(level);
        if (level <= getLevel(goal.skill())) {
            say("You're already past Lv. " + level + ".");
            return;
        }
        if (goals.contains(goal)) {
            say("Already a goal.");
            return;
        }
        goals.set(index, goal);
        saveSoon = true;
        emit(new Event.GoalChanged(index, Event.GoalDef.of(goal)));
    }

    public boolean has(Map<Product, Integer> components) {
        return components.entrySet().stream().allMatch(c -> inventory.getOrDefault(c.getKey(), 0) >= c.getValue());
    }

    boolean attemptToUseAll(Map<Product, Integer> components) {
        for (Product part : components.keySet()) {
            if (inventory.getOrDefault(part, 0) < components.get(part)) {
                say(part.name + " x " + components.get(part) + " are not available");
                return false;
            }
        }
        components.forEach((part, count) -> changeInventory(part, -count));
        return true;
    }

    public Product equipped(EquipSlot slot) {
        return slot == EquipSlot.WEAPON ? weapon : armor;
    }

    void equip(EquipSlot slot, Product item) {
        if (item == null || inventory.getOrDefault(item, 0) <= 0) {
            say("You don't have that.");
            return;
        }
        setEquipped(slot, item);
        say("You equip " + item.name + " as your " + slot.label + ".");
    }

    void unequip(EquipSlot slot) {
        Product item = equipped(slot);
        if (item == null) {
            say("You've no " + slot.label + " equipped.");
            return;
        }
        setEquipped(slot, null);
        say("You put away the " + item.name + ".");
    }

    /** One swing (weapon) or one hit taken (armour): one fewer in the inventory. */
    void useUp(EquipSlot slot) {
        Product item = equipped(slot);
        if (item != null) {
            changeInventory(item, -1);
        }
    }

    private void setEquipped(EquipSlot slot, Product item) {
        if (slot == EquipSlot.WEAPON) {
            weapon = item;
        } else {
            armor = item;
        }
        saveSoon = true;
        emit(new Event.EquipmentChanged(slot, item == null ? -1 : item.id));
    }

    void changeInventory(Product product, int delta) {
        int count = inventory.merge(product, delta, Integer::sum);
        emit(new Event.InventoryChanged(product.id, count));
        if (count <= 0) {
            for (EquipSlot slot : EquipSlot.values()) {
                if (product.equals(equipped(slot))) {
                    setEquipped(slot, null);
                    say(Event.MessageKind.NOTICE, "You've used up your " + product.name + " (" + slot.label + ").");
                }
            }
        }
    }

    Event.CharacterSnapshot snapshot() {
        Map<Integer, Long> xpById = new HashMap<>();
        xp.forEach((skill, amount) -> xpById.put(skill.id, amount));
        Map<Integer, Integer> inventoryById = new LinkedHashMap<>();
        inventory.forEach((product, count) -> inventoryById.put(product.id, count));
        return new Event.CharacterSnapshot(id, name, position(), xpById, inventoryById,
                goals.stream().map(Event.GoalDef::of).toList(), new HashSet<>(triedNodes), lastActionAt, hp, maxHp(),
                facing, colorIndex, running, autoInvestigate, showProductTiers, showTimeEstimate, compactChat, statsByName(),
                weapon == null ? -1 : weapon.id, armor == null ? -1 : armor.id);
    }

    Event.EntityInfo info() {
        int levelsGained = xp.keySet().stream().mapToInt(skill -> getLevel(skill) - 1).sum();
        return Event.EntityInfo.player(id, name, position(), facing, levelsGained, hp, maxHp(), colorIndex);
    }
}
