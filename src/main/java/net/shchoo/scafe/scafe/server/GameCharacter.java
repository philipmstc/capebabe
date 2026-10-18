package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.model.Goal;
import net.shchoo.scafe.scafe.model.Levels;
import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.Recipe;
import net.shchoo.scafe.scafe.model.Skill;
import net.shchoo.scafe.scafe.model.SkillType;
import net.shchoo.scafe.scafe.protocol.Event;

import java.util.*;
import java.util.function.Consumer;

/** A player's character as the server sees it: the authoritative state. Everything it does is reported as events. */
public class GameCharacter implements Walker.Mobile {
    public final int id;
    public final String name;
    public int x;
    public int y;
    public final Map<Skill, Long> xp = new HashMap<>();
    public final LinkedHashMap<Product, Integer> inventory = new LinkedHashMap<>();
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
        emit(new Event.Message(text));
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
        if (currentAction != null) {
            stopAction("Stopped.");
        } else if (node == null) {
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

    void queueStep(int dx, int dy) {
        if (!walker.queueStep(dx, dy)) {
            emit(new Event.StepsCleared(position()));
        }
    }

    void walkTo(Position target) {
        if (walker.walkTo(target)) {
            emit(new Event.StepsCleared(position()));
        }
    }

    void stopWalking() {
        if (walker.stop()) {
            emit(new Event.StepsCleared(position()));
        }
    }

    void stopAction(String reason) {
        if (currentAction == null) {
            return;
        }
        nodes.leave(currentAction.node, id);
        currentAction = null;
        if (reason != null) {
            say(reason);
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
        say("Made " + recipe.product.name);
        triggers.add(Discovery.Trigger.MADE);
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
                say("New goal: " + goal.describe());
            }
        }
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

    void changeInventory(Product product, int delta) {
        int count = inventory.merge(product, delta, Integer::sum);
        emit(new Event.InventoryChanged(product.id, count));
    }

    Event.CharacterSnapshot snapshot() {
        Map<Integer, Long> xpById = new HashMap<>();
        xp.forEach((skill, amount) -> xpById.put(skill.id, amount));
        Map<Integer, Integer> inventoryById = new LinkedHashMap<>();
        inventory.forEach((product, count) -> inventoryById.put(product.id, count));
        return new Event.CharacterSnapshot(id, name, position(), xpById, inventoryById,
                goals.stream().map(Event.GoalDef::of).toList(), new HashSet<>(triedNodes), lastActionAt, hp, maxHp());
    }

    Event.EntityInfo info() {
        int levelsGained = xp.keySet().stream().mapToInt(skill -> getLevel(skill) - 1).sum();
        return Event.EntityInfo.player(id, name, position(), levelsGained, hp, maxHp());
    }
}
