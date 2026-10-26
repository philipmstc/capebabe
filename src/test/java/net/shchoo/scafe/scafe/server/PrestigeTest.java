package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.client.GameLog;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.generation.WorldGenerator;
import net.shchoo.scafe.scafe.model.Levels;
import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.Recipe;
import net.shchoo.scafe.scafe.model.Skill;
import net.shchoo.scafe.scafe.model.SkillType;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.model.WorldContent;
import net.shchoo.scafe.scafe.persistence.AccountStore;
import net.shchoo.scafe.scafe.persistence.WorldStore;
import net.shchoo.scafe.scafe.protocol.Intent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class PrestigeTest {
    private final List<String> log = new ArrayList<>();
    private final Consumer<String> listener = log::add;
    private GameServer server;
    private Player player;
    private Skill primary;
    private Recipe raw;

    @BeforeEach
    void setUp() {
        GameLog.subscribe(listener);
        World world = new World(1, "w", 2);
        world.content.apply(WorldGenerator.newSkills(world, 3));
        primary = world.content.skills.stream().filter(s -> s.type == SkillType.PRIMARY).findFirst().orElseThrow();
        raw = primary.productLines.get(0).recipes.get(0);
        server = new GameServer(world);
        server.setDiscovery(Discovery.off());
        player = new Player(new LocalConnection(server.connect()));
        player.send(new Intent.Join("alice"));
        ticks(1);
    }

    @AfterEach
    void tearDown() {
        GameLog.unsubscribe(listener);
    }

    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            server.tick();
            player.receive();
        }
    }

    private Skill mine(Skill skill) {
        return player.content.skill(skill.id);
    }

    /** Lv. 100 in the primary skill, with a bit over. */
    private long toMax() {
        long xp = Skill.XP_PER_LEVEL[Levels.MAX_LEVEL - 1] + 1234;
        player.send(new Intent.Admin("5", List.of(primary.name, String.valueOf(xp))));
        ticks(1);
        assertEquals(Levels.MAX_LEVEL, player.getLevel(mine(primary)));
        return xp;
    }

    @Test
    void prestigeStartsTheSkillOverAndBanksItsXp() {
        long xp = toMax();
        int totalLevel = player.totalLevel();
        long totalXp = player.totalExperience();
        player.prestige(mine(primary));
        ticks(1);
        assertEquals(1, player.getLevel(mine(primary)), "back to Lv. 1");
        assertEquals(0L, player.getExperience(mine(primary)));
        assertEquals(Skill.XP_PER_LEVEL[1], player.getExperienceToNextLevel(mine(primary)), "and the next level's xp is Lv. 2's again");
        assertEquals(1, player.getPrestige(mine(primary)));
        assertEquals(xp, player.lifetimeExperience(mine(primary)), "nothing lost: banked");
        assertEquals(totalLevel, player.totalLevel(), "the total level never goes down");
        assertEquals(totalXp, player.totalExperience(), "nor the total xp");
        assertTrue(log.contains(primary.name + " prestige 1! Back to Lv. 1 -- what you make with it now hits harder."));

        toMax();
        player.prestige(mine(primary));
        ticks(1);
        assertEquals(2, player.getPrestige(mine(primary)), "as many times as you like");
        assertEquals(2 * xp, player.lifetimeExperience(mine(primary)));
    }

    @Test
    void stacksGoByTheVirtualLevel_soAPrestigeDoesntShrinkThem() {
        long xp = toMax();
        Product item = primary.productLines.get(0).recipes.get(0).product;
        assertEquals(Levels.MAX_LEVEL, player.stackSize(player.content.product(item.id)));
        player.prestige(mine(primary));
        ticks(1);
        assertEquals(1, player.getLevel(mine(primary)), "Lv. 1 again");
        assertEquals(Levels.virtualLevelFor(xp), player.stackSize(player.content.product(item.id)), "but stacks as before");
        assertEquals(Levels.virtualLevelFor(xp), server.online(player.id).stackLevel(primary), "on the server too");
        toMax();
        assertTrue(player.stackSize(player.content.product(item.id)) > Levels.MAX_LEVEL, "and past 100 with more");
    }

    @Test
    void onlyAtLevel100AndNotCombat() {
        player.prestige(mine(primary));
        ticks(1);
        assertEquals(0, player.getPrestige(mine(primary)));
        assertTrue(log.contains("Reach " + primary.name + " Lv. 100 to prestige it."));
    }

    private Node nodeAhead() {
        WorldContent scratch = server.world.content.copy();
        Node node = new Node(scratch.allocateId(), new Position(0, 1), scratch.recipe(raw.id), Node.UNLIMITED, Node.UNLIMITED, 0);
        scratch.addNode(node);
        server.applyContent(scratch.diff(server.world.content));
        ticks(1);
        return node;
    }

    @Test
    void whatAPrestigedSkillMakesIsThePrestigeVersion() {
        toMax();
        player.prestige(mine(primary));
        ticks(1);
        nodeAhead();
        player.send(new Intent.Perform());
        for (int i = 0; i < 2 * Node.DEFAULT_DURATION_TICKS + 1; i++) {
            ticks(1);
        }
        Product made = server.world.content.variant(raw.product, 1);
        assertNotNull(made, "made the first time it was needed");
        assertEquals(raw.product.name + "+1", made.name);
        assertEquals(raw.product, made.root());
        Product seen = player.content.product(made.id);
        assertNotNull(seen, "everyone's told about it");
        assertTrue(player.inventory.getOrDefault(seen, 0) > 0);
        assertNull(player.inventory.get(player.content.product(raw.product.id)), "not the plain one");
        assertEquals(raw, server.world.content.madeBy(made), "made by the same recipe");
    }

    @Test
    void prestigeVersionsCountAsTheirPlainItemInRecipesPlainOnesUsedFirst() {
        Product plain = raw.product;
        Product plus = server.prestigeProduct(plain, 1);
        GameCharacter alice = server.online(player.id);
        alice.changeInventory(plain, 1);
        alice.changeInventory(plus, 2);
        assertEquals(3, alice.available(plain));
        assertTrue(alice.has(java.util.Map.of(plain, 3)));
        assertTrue(alice.attemptToUseAll(java.util.Map.of(plain, 2)));
        assertEquals(0, alice.inventory.get(plain), "the plain one went first");
        assertEquals(1, alice.inventory.get(plus));
    }

    @Test
    void aPrestigeLevelOutdoesAWholeLineOfTiers() {
        Product tier0 = primary.productLines.get(0).recipes.get(0).product;
        Product tier4 = primary.productLines.get(0).recipes.get(4).product;
        Product tier0Prestige1 = server.prestigeProduct(tier0, 1);
        assertEquals(1, Combat.weaponBase(tier0));
        assertEquals(5, Combat.weaponBase(tier4));
        assertEquals(6, Combat.weaponBase(tier0Prestige1));
        assertTrue(Combat.weaponBase(tier0Prestige1) > Combat.weaponBase(tier4));
        assertEquals(1, Combat.weaponBase(null), "fists");
    }

    @Test
    void prestigeAndItsItemsAreKept(@TempDir Path dir) throws Exception {
        long xp;
        int itemId;
        try (AccountStore accounts = new AccountStore(dir.resolve("accounts.db"));
             WorldStore store = new WorldStore(dir.resolve("world.db"), accounts)) {
            World world = store.loadOrCreateWorld(1, "w", 2);
            GameServer saved = new GameServer(world, store);
            saved.applyContent(WorldGenerator.newSkills(world, 1));
            Skill skill = world.content.craftingSkills().get(0);
            Player bob = new Player(new LocalConnection(saved.connect()));
            bob.send(new Intent.Join("bob"));
            bob.send(new Intent.Admin("5", List.of(skill.name, String.valueOf(Skill.XP_PER_LEVEL[Levels.MAX_LEVEL - 1]))));
            saved.tick();
            bob.receive();
            bob.send(new Intent.Prestige(skill.id));
            saved.tick();
            Product plus = saved.prestigeProduct(skill.productLines.get(0).recipes.get(0).product, 1);
            saved.online(bob.id).changeInventory(plus, 3);
            itemId = plus.id;
            xp = Skill.XP_PER_LEVEL[Levels.MAX_LEVEL - 1];
            bob.send(new Intent.Leave());
            saved.tick();
        }
        try (AccountStore accounts = new AccountStore(dir.resolve("accounts.db"));
             WorldStore store = new WorldStore(dir.resolve("world.db"), accounts)) {
            GameServer saved = new GameServer(store.loadOrCreateWorld(1, "w", 2), store);
            Player bob = new Player(new LocalConnection(saved.connect()));
            bob.send(new Intent.Join("bob"));
            saved.tick();
            bob.receive();
            Skill skill = bob.content.craftingSkills().get(0);
            assertEquals(1, bob.getPrestige(skill));
            assertEquals(xp, bob.lifetimeExperience(skill));
            assertEquals(1, bob.getLevel(skill));
            Product plus = bob.content.product(itemId);
            assertEquals(1, plus.prestige, "the prestige version is part of the world");
            assertEquals(3, bob.inventory.get(plus));
        }
    }
}
