package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.model.WorldContent;
import net.shchoo.scafe.scafe.model.Stacks;
import net.shchoo.scafe.scafe.model.Building;
import net.shchoo.scafe.scafe.model.EquipSlot;
import net.shchoo.scafe.scafe.model.Direction;
import net.shchoo.scafe.scafe.model.Goal;
import net.shchoo.scafe.scafe.model.Levels;
import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.Prayer;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.Recipe;
import net.shchoo.scafe.scafe.model.Skill;
import net.shchoo.scafe.scafe.model.SkillType;
import net.shchoo.scafe.scafe.model.Tiers;
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
    /** Lifetime counts (see {@link Stat}); sent to the client at the end of a tick in which they changed. */
    public final Map<Stat, Long> stats = new EnumMap<>(Stat.class);
    boolean statsChanged;
    /** The last tick this character took a step (or turned), for "is moving right now". */
    long lastSteppedTick = -1;
    /** Walking over to use an item on the node here; its action starts on arrival. */
    Position pendingUse;
    /** ...AFK (see {@link ActionProgress#afk}). */
    boolean pendingAfk;
    public final Map<Skill, Long> xp = new HashMap<>();
    /** Times each skill's been prestiged (absent = 0), and the xp it had each time, all added up. */
    public final Map<Skill, Integer> prestige = new HashMap<>();
    public final Map<Skill, Long> bankedXp = new HashMap<>();
    /** Prayed for at a church ({@link Prayer}): this recipe's node is coming, for this many more steps / actions. Saved. */
    public Recipe blessing;
    public int blessingLeft;
    /** Training at a gym ({@link Combat#train}), or null. */
    Combat.Training training;
    /** The Merch Ant has asked for you (your first prestige): from then on it trades with you. Saved. */
    public boolean metMerchant;
    public final LinkedHashMap<Product, Integer> inventory = new LinkedHashMap<>();
    /** What's in your bank (unlimited), in the order you first put things in. */
    public final LinkedHashMap<Product, Integer> bank = new LinkedHashMap<>();
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
    /** Doesn't come back by itself: a church (q) or getting knocked out fills it up. Saved. */
    int hp = Combat.maxHp(1);
    /** On the train until this tick (-1: not riding), getting off at {@link #rideTo}; see {@link GameServer#ride}. */
    long ridingUntil = -1;
    Building rideTo;

    boolean isRiding() {
        return ridingUntil >= 0;
    }

    /** Walking over to fight this monster (null: not -- monster ids are negative), until this tick; see {@link Combat#fightAt}. */
    Integer pendingFight;
    long pendingFightUntil;

    /** Knocked out until this tick (-1: up and about); see {@link Combat#knockOut}. */
    long downUntil = -1;

    boolean isDown() {
        return downUntil >= 0;
    }

    /** The hp it was saved with (null: never saved = full), used when it joins. */
    public Integer savedHp;

    public int currentHp() {
        return hp;
    }
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
    /** The world's content (for stack sizes), once connected. */
    private WorldContent content = new WorldContent();

    public GameCharacter(int id, String name) {
        this.id = id;
        this.name = name;
    }

    void connect(Consumer<Event> outbox, NodeStates nodes, WorldContent content,
                 java.util.function.BiFunction<Product, Integer, Product> prestigeProduct) {
        this.outbox = outbox;
        this.nodes = nodes;
        this.content = content;
        this.prestigeProduct = prestigeProduct;
    }

    /** The prestige version of a product, made (as new world content) the first time anyone needs it. */
    private java.util.function.BiFunction<Product, Integer, Product> prestigeProduct = (base, level) -> base;

    public int getPrestige(Skill skill) {
        return prestige.getOrDefault(skill, 0);
    }

    /** What {@code recipe} makes for you: its product, or the prestige version of it if you've prestiged the skill. */
    Product made(Recipe recipe) {
        int level = getPrestige(recipe.skill);
        return level <= 0 ? recipe.product : prestigeProduct.apply(recipe.product, level);
    }

    /**
     * At Lv. 100 in a crafting skill: start it over -- its xp banked and back to 0 (Lv. 1), the prestige count up
     * one. What it makes from now on is the prestige version, which hits harder.
     */
    void prestige(Skill skill) {
        if (skill == null) {
            return;
        }
        if (skill.type == SkillType.COMBAT) {
            say("Combat can't be prestiged (yet).");
            return;
        }
        if (getLevel(skill) < Levels.MAX_LEVEL) {
            say("Reach " + skill.name + " Lv. " + Levels.MAX_LEVEL + " to prestige it.");
            return;
        }
        long banked = bankedXp.merge(skill, getExperience(skill), Long::sum);
        int times = prestige.merge(skill, 1, Integer::sum);
        xp.put(skill, 0L);
        infoChanged = true;
        saveSoon = true;
        emit(new Event.Prestiged(skill.id, times, banked));
        say(Event.MessageKind.NOTICE, skill.name + " prestige " + times + "! Back to Lv. 1 -- what you make with it now hits harder.");
        meetMerchantIfDue();
    }

    /** With a prestige to your name and the Merch Ant not met yet: it asks for you (once, ever). */
    void meetMerchantIfDue() {
        if (metMerchant || prestige.values().stream().noneMatch(times -> times > 0)) {
            return;
        }
        metMerchant = true;
        saveSoon = true;
        emit(new Event.MerchantCalls());
    }

    /**
     * At the Merch Ant: up to {@code count} of a prestige item for 1 + its prestige of the plain one each
     * (skill0{1}-2+3: 4 x skill0{1}-2).
     */
    void trade(Product item, int count) {
        if (!metMerchant) {
            say("The ant waves its antennae: not open for business yet.");
            return;
        }
        if (item == null || item.prestige <= 0) {
            say("The Merch Ant only deals in prestige items.");
            return;
        }
        int n = Math.min(count, inventory.getOrDefault(item, 0));
        if (n <= 0) {
            return;
        }
        int back = n * (1 + item.prestige);
        Map<Product, Integer> changes = new HashMap<>();
        changes.put(item, -n);
        changes.merge(item.root(), back, Integer::sum);
        if (!Stacks.fits(content, inventory, changes, this::stackLevel)) {
            say("Your inventory is full.");
            return;
        }
        changeInventory(item, -n);
        changeInventory(item.root(), back);
        saveSoon = true;
        say("The Merch Ant takes " + n + " x " + item.name + " and gives you " + back + " x " + item.root().name + ".");
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
        count(stat, 1);
    }

    void count(Stat stat, long n) {
        stats.merge(stat, n, Long::sum);
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

    /** What a skill's items stack to: its virtual level, from all its xp across prestiges ({@link Stacks}). */
    int stackLevel(Skill skill) {
        return Levels.virtualLevelFor(bankedXp.getOrDefault(skill, 0L) + getExperience(skill));
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
        return !isLockedFor(node) && has(node.recipe.components) && roomToMake(node.recipe);
    }

    /** Making it (its components gone, the product in) wouldn't overfill the inventory. */
    boolean roomToMake(Recipe recipe) {
        Map<Product, Integer> changes = new HashMap<>();
        recipe.components.forEach((part, count) -> changes.merge(part, -count, Integer::sum));
        changes.merge(made(recipe), madePerCraft(recipe), Integer::sum);
        return Stacks.fits(content, inventory, changes, this::stackLevel);
    }

    /** Inventory slots in use (stacks: see {@link Stacks}). */
    public int slotsUsed() {
        return Stacks.slots(content, inventory, this::stackLevel);
    }

    /** How many one craft of {@code recipe} makes at your level ({@link Tiers}). */
    int madePerCraft(Recipe recipe) {
        return Tiers.yield(Tiers.of(recipe), getLevel(recipe.skill));
    }

    /** At least one slot free (for something new, like a drop). */
    boolean hasFreeSlot() {
        return slotsUsed() < Stacks.INVENTORY_SLOTS;
    }

    /** At a church: all your hp back. */
    void heal() {
        if (hp >= maxHp()) {
            say("You're already at full health.");
            return;
        }
        hp = maxHp();
        infoChanged = true;
        saveSoon = true;
        emit(new Event.HpChanged(hp, maxHp()));
        say(Event.MessageKind.NOTICE, "You pray, and feel restored (" + hp + "/" + hp + " hp).");
    }

    /** At a church: pay for a blessing on {@code recipe} ({@link Prayer}); it replaces any you had. */
    void pray(Recipe recipe, Product payment) {
        if (recipe == null || payment == null) {
            return;
        }
        int cost = Prayer.cost(recipe, payment);
        if (inventory.getOrDefault(payment, 0) < cost) {
            say("Praying for that takes " + cost + " x " + payment.name + ".");
            return;
        }
        changeInventory(payment, -cost);
        setBlessing(recipe, Prayer.STEPS);
        count(Stat.TIMES_PRAYED);
        saveSoon = true;
        say(Event.MessageKind.NOTICE, "You pray for " + recipe.product.name + " (" + cost + " x " + payment.name
                + "). Nodes are likelier near you, and the next is " + recipe.product.name + " -- for " + Prayer.STEPS + " steps.");
    }

    /** At a church: gives up to {@code count} of {@code item}; returns how many went. */
    int tithe(Product item, int count) {
        int n = Math.min(count, item == null ? 0 : inventory.getOrDefault(item, 0));
        if (n <= 0) {
            return 0;
        }
        changeInventory(item, -n);
        count(Stat.ITEMS_TITHED, n);
        saveSoon = true;
        say("You leave " + n + " x " + item.name + " at the altar.");
        return n;
    }

    /** One step / item made / kill used up; the blessing fades when there are none left. */
    void blessingStep() {
        if (blessing == null) {
            return;
        }
        if (blessingLeft <= 1) {
            setBlessing(null, 0);
            say("Your blessing fades.");
        } else {
            setBlessing(blessing, blessingLeft - 1);
        }
    }

    void setBlessing(Recipe recipe, int left) {
        blessing = recipe;
        blessingLeft = recipe == null ? 0 : left;
        emit(new Event.BlessingChanged(recipe == null ? -1 : recipe.id, blessingLeft));
    }

    /** Up to {@code count} from the inventory into the bank. */
    void deposit(Product item, int count) {
        int n = Math.min(count, item == null ? 0 : inventory.getOrDefault(item, 0));
        if (n <= 0) {
            say("You don't have that.");
            return;
        }
        changeInventory(item, -n);
        changeBank(item, n);
    }

    /** Up to {@code count} from the bank, as much as fits in the inventory. */
    void withdraw(Product item, int count) {
        int stored = item == null ? 0 : bank.getOrDefault(item, 0);
        if (stored <= 0) {
            say("That's not in your bank.");
            return;
        }
        int n = Math.min(Math.min(count, stored), Stacks.room(content, inventory, item, this::stackLevel));
        if (n <= 0) {
            say("Your inventory is full.");
            return;
        }
        changeBank(item, -n);
        changeInventory(item, n);
        if (n < Math.min(count, stored)) {
            say("Your inventory is full.");
        }
    }

    private void changeBank(Product product, int delta) {
        int count = bank.merge(product, delta, Integer::sum);
        if (count <= 0) {
            bank.remove(product);
        }
        saveSoon = true;
        emit(new Event.BankChanged(product.id, Math.max(0, count)));
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
        } else if (!node.recipe.components.containsKey(item.root())) {
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

    /**
     * Craft at the node at {@code target} (the map's node menu): facing it, the action starts (instead of whatever
     * you were doing); otherwise you walk over and it starts on arrival. {@code afk}: see {@link ActionProgress}.
     */
    void craftAt(Node node, Position target, boolean afk, long tick) {
        pendingUse = null;
        if (node == null) {
            say("There's nothing there to craft at.");
        } else if (ahead().equals(target)) {
            if (currentAction != null && currentAction.node.equals(node) && currentAction.afk == afk) {
                say("You're already on it.");
                return;
            }
            if (currentAction != null) {
                stopAction(null);
            }
            startAction(node, tick, afk);
        } else {
            Position at = position();
            if (Math.abs(target.x() - at.x()) + Math.abs(target.y() - at.y()) == 1) {
                queueStep(Direction.of(target.x() - at.x(), target.y() - at.y()));
            } else {
                walkTo(target);
            }
            if (walker.isWalking()) {
                pendingUse = target;
                pendingAfk = afk;
            }
        }
    }

    /** After walking: start the use that was waiting for us to arrive, if we got there. */
    void arrive(Node node, long tick) {
        if (pendingUse == null || walker.isWalking()) {
            return;
        }
        Position target = pendingUse;
        boolean afk = pendingAfk;
        pendingUse = null;
        pendingAfk = false;
        if (!ahead().equals(target) || node == null) {
            say("Couldn't get to it.");
            return;
        }
        if (currentAction != null) {
            stopAction(null);
        }
        startAction(node, tick, afk);
    }

    private void startAction(Node node, long tick) {
        startAction(node, tick, false);
    }

    /** {@code afk}: an empty node isn't a reason not to start -- you wait for it. */
    private void startAction(Node node, long tick, boolean afk) {
        if (node == null) {
            say("Nothing to do here...");
        } else if (!canPerform(node)) {
            perform(node);
        } else {
            String busy = afk && nodes.isDepleted(node) ? null : nodes.whyNot(node, id);
            if (busy != null) {
                say(busy);
                return;
            }
            stopWalking();
            nodes.join(node, id);
            currentAction = new ActionProgress(node, this, tick, afk);
            emit(new Event.ActionStarted(node.id, ActionProgress.label(node, afk), tick, node.durationTicks()));
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
        return perform(node, false);
    }

    /** {@code afk}: half the xp (at least 1). */
    boolean perform(Node node, boolean afk) {
        Recipe recipe = node.recipe;
        if (isLockedFor(node)) {
            if (triedNodes.add(node.id)) {
                emit(new Event.NodeTried(node.id));
            }
            say("Need " + recipe.skill.name + " Lv. " + recipe.levelNeeded + " (you're Lv. " + getLevel(recipe.skill) + ")");
            say("(press g to make it a goal)");
            return false;
        }
        if (!roomToMake(recipe)) {
            say("Your inventory is full.");
            return false;
        }
        if (!attemptToUseAll(recipe.components)) {
            return false;
        }
        int made = madePerCraft(recipe);
        changeInventory(made(recipe), made);
        lastActionAt = position();
        emit(new Event.HomeChanged(lastActionAt));
        say(Event.MessageKind.ROUTINE, "Made " + (made > 1 ? made + " x " : "") + recipe.product.name);
        triggers.add(Discovery.Trigger.MADE);
        count(Stat.ITEMS_CRAFTED, made);
        if (afk) {
            count(Stat.ITEMS_CRAFTED_AFK, made);
        }
        gainExperience(recipe.skill, afk ? Math.max(1, recipe.expGained / 2) : recipe.expGained);
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
        return components.entrySet().stream().allMatch(c -> available(c.getKey()) >= c.getValue());
    }

    /** How many of a component you have: the plain item and its prestige versions all count. */
    int available(Product part) {
        return inventory.entrySet().stream().filter(e -> e.getKey().root().equals(part.root()))
                .mapToInt(Map.Entry::getValue).sum();
    }

    boolean attemptToUseAll(Map<Product, Integer> components) {
        for (Product part : components.keySet()) {
            if (available(part) < components.get(part)) {
                say(part.name + " x " + components.get(part) + " are not available");
                return false;
            }
        }
        components.forEach(this::useUp);
        return true;
    }

    /** {@code count} of a component: the plain ones first, then the prestige versions, lowest first. */
    private void useUp(Product part, int count) {
        List<Product> kinds = inventory.keySet().stream().filter(p -> p.root().equals(part.root()))
                .sorted(java.util.Comparator.comparingInt(p -> p.prestige)).toList();
        for (Product kind : kinds) {
            int take = Math.min(count, inventory.getOrDefault(kind, 0));
            if (take > 0) {
                changeInventory(kind, -take);
                count -= take;
            }
        }
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
                facing, colorIndex, running, autoInvestigate, statsByName(),
                weapon == null ? -1 : weapon.id, armor == null ? -1 : armor.id, bankById(), byId(prestige), byId(bankedXp),
                blessing == null ? -1 : blessing.id, blessingLeft, metMerchant);
    }

    private static <V> Map<Integer, V> byId(Map<Skill, V> bySkill) {
        Map<Integer, V> byId = new HashMap<>();
        bySkill.forEach((skill, value) -> byId.put(skill.id, value));
        return byId;
    }

    private Map<Integer, Integer> bankById() {
        Map<Integer, Integer> byId = new LinkedHashMap<>();
        bank.forEach((product, count) -> byId.put(product.id, count));
        return byId;
    }

    Event.EntityInfo info() {
        // a prestige keeps the 99 levels it took: the total never goes down
        int levelsGained = xp.keySet().stream().mapToInt(skill -> getLevel(skill) - 1).sum()
                + prestige.values().stream().mapToInt(times -> times * (Levels.MAX_LEVEL - 1)).sum();
        return Event.EntityInfo.player(id, name, position(), facing, levelsGained, hp, maxHp(), colorIndex, combatLevel());
    }
}
