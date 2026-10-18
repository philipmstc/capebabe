package net.shchoo.scafe.scafe.persistence;

import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.generation.WorldGenerator;
import net.shchoo.scafe.scafe.model.*;
import net.shchoo.scafe.scafe.protocol.Intent;
import net.shchoo.scafe.scafe.server.Discovery;
import net.shchoo.scafe.scafe.server.GameCharacter;
import net.shchoo.scafe.scafe.server.GameServer;
import net.shchoo.scafe.scafe.server.LocalConnection;

import java.sql.Statement;
import java.sql.DriverManager;
import java.sql.Connection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorldStoreTest {
    @TempDir
    Path dir;
    private AccountStore accounts;

    @BeforeEach
    void setUp() throws Exception {
        accounts = new AccountStore(dir.resolve("accounts.db"));
    }

    @AfterEach
    void tearDown() throws SQLException {
        accounts.close();
    }

    private WorldStore open(int worldId) throws Exception {
        return new WorldStore(WorldStore.fileFor(dir, worldId), accounts);
    }

    private static void save(WorldStore store, World world, ContentBatch batch) throws SQLException {
        store.saveContent(batch);
        world.content.apply(batch);
    }

    @Test
    void eachWorldIsItsOwnFileAndRemembersItsSeed() throws Exception {
        try (WorldStore one = open(1); WorldStore two = open(2)) {
            assertEquals(5, one.schemaVersion(), "v1 base, v2 monsters, v3 drop finders, v4 roam + speed, v5 facing/color/run");
            assertEquals(777, one.loadOrCreateWorld(1, "w1", 777).seed);
            assertEquals(888, two.loadOrCreateWorld(2, "w2", 888).seed);
        }
        assertTrue(Files.exists(dir.resolve("worlds").resolve("world-1.db")));
        assertTrue(Files.exists(dir.resolve("worlds").resolve("world-2.db")));
        try (WorldStore one = open(1)) {
            World world = one.loadOrCreateWorld(1, "ignored", 123);
            assertEquals(777, world.seed);
            assertEquals("w1", world.name);
        }
    }

    @Test
    void contentBatchesRoundTrip() throws Exception {
        List<ContentBatch> history;
        try (WorldStore store = open(1)) {
            World world = store.loadOrCreateWorld(1, "w", 42);
            save(store, world, WorldGenerator.newSkills(world, 6));
            save(store, world, WorldGenerator.newProductLines(world, 2));
            save(store, world, WorldGenerator.newNode(world, new Position(-3, 4), 2, 10, 5));
            history = List.copyOf(world.content.history());
        }
        try (WorldStore store = open(1)) {
            World reloaded = store.loadOrCreateWorld(1, "w", 0);
            assertEquals(history, reloaded.content.history());
            Node node = reloaded.content.nodeAt(new Position(-3, 4));
            assertEquals(2, node.capacity);
            assertEquals(10, node.maxCharges);
        }
    }

    @Test
    void aBatchOutOfOrderWritesNothing() throws Exception {
        try (WorldStore store = open(1)) {
            World world = store.loadOrCreateWorld(1, "w", 1);
            ContentBatch first = WorldGenerator.newSkills(world, 2);
            ContentBatch skipped = new ContentBatch(5, first.skills(), first.products(), first.productLines(), first.recipes(), first.nodes(),
                    first.monsters(), first.drops());
            assertThrows(SQLException.class, () -> store.saveContent(skipped));
            store.saveContent(first);
        }
        try (WorldStore store = open(1)) {
            assertEquals(1, store.loadOrCreateWorld(1, "w", 1).content.version());
        }
    }

    @Test
    void accountsAreSharedButCharactersBelongToAWorld() throws Exception {
        try (WorldStore one = open(1); WorldStore two = open(2)) {
            one.loadOrCreateWorld(1, "w1", 1);
            two.loadOrCreateWorld(2, "w2", 2);
            GameCharacter inOne = one.loadOrCreateCharacter("alice");
            inOne.x = 5;
            one.saveCharacter(inOne);
            GameCharacter inTwo = two.loadOrCreateCharacter("alice");
            assertEquals(0, inTwo.x, "a fresh character in the other world");
            assertEquals(accounts.find("alice"), accounts.idFor("alice"));
            assertEquals(5, one.loadOrCreateCharacter("alice").x);
            assertNull(accounts.find("bob"));
        }
    }

    @Test
    void charactersRoundTrip() throws Exception {
        int nodeId;
        try (WorldStore store = open(1)) {
            World world = store.loadOrCreateWorld(1, "w", 9);
            save(store, world, WorldGenerator.newSkills(world, 2));
            ContentBatch node = WorldGenerator.newNode(world, new Position(1, 1), 0, 0, 0);
            save(store, world, node);
            nodeId = node.nodes().get(0).id();

            GameCharacter alice = store.loadOrCreateCharacter("alice");
            Skill skill = world.content.skills.get(0);
            Recipe recipe = skill.productLines.get(0).recipes.get(1);
            alice.x = 4;
            alice.y = -2;
            alice.lastActionAt = new Position(1, 1);
            alice.xp.put(skill, 5000L);
            alice.inventory.put(recipe.product, 3);
            alice.inventory.put(skill.productLines.get(0).recipes.get(0).product, 0);
            alice.goals.add(new Goal(skill, recipe.levelNeeded, recipe.product));
            alice.triedNodes.add(nodeId);
            store.saveCharacter(alice);
        }
        try (WorldStore store = open(1)) {
            World world = store.loadOrCreateWorld(1, "w", 0);
            GameCharacter alice = store.loadCharacter(accounts.idFor("alice"));
            Skill skill = world.content.skills.get(0);
            Recipe recipe = skill.productLines.get(0).recipes.get(1);
            assertEquals(new Position(4, -2), alice.position());
            assertEquals(new Position(1, 1), alice.lastActionAt);
            assertEquals(5000L, alice.getExperience(skill));
            assertEquals(List.of(recipe.product, skill.productLines.get(0).recipes.get(0).product), List.copyOf(alice.inventory.keySet()));
            assertEquals(3, alice.inventory.get(recipe.product));
            assertEquals(List.of(new Goal(skill, recipe.levelNeeded, recipe.product)), alice.goals);
            assertTrue(alice.triedNodes.contains(nodeId));
        }
    }

    @Test
    void aServerRestartKeepsTheWorldAndYourProgress() throws Exception {
        long xpBefore;
        int skills;
        try (WorldStore store = open(1)) {
            GameServer server = new GameServer(store.loadOrCreateWorld(1, "w", 5), store);
            server.setDiscovery(Discovery.off());
            Player alice = new Player(new LocalConnection(server.connect()));
            alice.send(new Intent.Join("alice"));
            alice.send(new Intent.Admin("1", List.of("2")));
            alice.send(new Intent.Admin("3", List.of()));
            server.tick();
            alice.receive();
            // the random node may be locked for a fresh character; reroll until it isn't
            // "/3" puts the node in front of you (you start at the origin facing down)
            for (int i = 0; i < 50 && !alice.content.nodeAt(new Position(0, 1)).recipe.levelMet(1); i++) {
                alice.send(new Intent.Admin("3", List.of()));
                server.tick();
                alice.receive();
            }
            alice.send(new Intent.Perform());
            for (int i = 0; i < 6; i++) {
                server.tick();
                alice.receive();
            }
            alice.moveBy(1, 0);
            alice.moveBy(1, 0);
            alice.send(new Intent.SetColor(4));
            alice.send(new Intent.SetRunning(true));
            server.tick();
            server.tick();
            alice.receive();
            xpBefore = alice.content.skills.stream().mapToLong(alice::getExperience).sum();
            skills = alice.content.skills.size();
            assertTrue(xpBefore > 0);
            server.stop();
        }
        try (WorldStore store = open(1)) {
            GameServer server = new GameServer(store.loadOrCreateWorld(1, "w", 0), store);
            server.setDiscovery(Discovery.off());
            Player alice = new Player(new LocalConnection(server.connect()));
            alice.send(new Intent.Join("alice"));
            server.tick();
            alice.receive();
            assertEquals(skills, alice.content.skills.size());
            assertEquals(xpBefore, alice.content.skills.stream().mapToLong(alice::getExperience).sum());
            assertEquals(new Position(2, 0), alice.position());
            assertEquals(Direction.RIGHT, alice.facing);
            assertEquals(4, alice.colorIndex);
            assertTrue(alice.running);
            assertFalse(alice.inventory.isEmpty());
        }
    }

    @Test
    void monstersAndTheirDropsRoundTrip() throws Exception {
        List<ContentBatch> history;
        try (WorldStore store = open(1)) {
            World world = store.loadOrCreateWorld(1, "w", 12);
            save(store, world, WorldGenerator.newSkills(world, 2));
            save(store, world, WorldGenerator.newMonsterType(world));
            save(store, world, WorldGenerator.newMonsterType(world));
            save(store, world, WorldGenerator.newDrop(world, world.content.monsters.get(0).id, "alice"));
            save(store, world, WorldGenerator.newDrop(world, world.content.monsters.get(0).id, "bob"));
            history = List.copyOf(world.content.history());
        }
        try (WorldStore store = open(1)) {
            World reloaded = store.loadOrCreateWorld(1, "w", 0);
            assertEquals(history, reloaded.content.history());
            MonsterType first = reloaded.content.monsters.get(0);
            assertEquals(List.of("monster0-drop0", "monster0-drop1"), first.drops.stream().map(d -> d.product().name).toList());
            assertEquals(List.of("alice", "bob"), first.drops.stream().map(MonsterType.Drop::foundBy).toList());
            MonsterType saved = history.stream().flatMap(b -> b.monsters().stream())
                    .filter(d -> d.id() == first.id).findFirst()
                    .map(d -> new MonsterType(d.id(), d.name(), d.level(), d.maxHp(), d.maxHit(), d.colorIndex(), d.symbol(),
                            d.roamRadius(), d.moveTicks())).orElseThrow();
            assertEquals(saved.roamRadius, first.roamRadius);
            assertEquals(saved.moveTicks, first.moveTicks);
            assertNotNull(reloaded.content.combatSkill());
        }
    }

    @Test
    void anOldVersionOneWorldUpgradesInPlace() throws Exception {
        Path file = WorldStore.fileFor(dir, 7);
        Files.createDirectories(file.getParent());
        java.lang.reflect.Field field = WorldStore.class.getDeclaredField("MIGRATIONS");
        field.setAccessible(true);
        String v1 = ((List<?>) field.get(null)).get(0).toString();
        try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement statement = raw.createStatement()) {
            for (String sql : v1.split(";")) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
            statement.execute("pragma user_version = 1");
            statement.execute("insert into world_meta (id, name, seed) values (1, 'old', 55)");
        }
        try (WorldStore store = open(7)) {
            assertEquals(5, store.schemaVersion());
            World world = store.loadOrCreateWorld(7, "ignored", 0);
            assertEquals("old", world.name);
            save(store, world, WorldGenerator.newSkills(world, 1));
            save(store, world, WorldGenerator.newMonsterType(world));
        }
        try (WorldStore store = open(7)) {
            assertEquals(1, store.loadOrCreateWorld(7, "ignored", 0).content.monsters.size());
        }
    }

    @Test
    void aOneOfAKindDropAndItsOwnerAreSavedTogether() throws Exception {
        int productId;
        try (WorldStore store = open(1)) {
            World world = store.loadOrCreateWorld(1, "w", 3);
            save(store, world, WorldGenerator.newMonsterType(world));
            GameCharacter alice = store.loadOrCreateCharacter("alice");
            ContentBatch drop = WorldGenerator.newDrop(world, world.content.monsters.get(0).id, "alice");
            productId = drop.drops().get(0).productId();
            store.saveContent(drop, new net.shchoo.scafe.scafe.server.Storage.Grant(alice.id, productId, 1));
            world.content.apply(drop);
        }
        try (WorldStore store = open(1)) {
            World world = store.loadOrCreateWorld(1, "w", 0);
            GameCharacter alice = store.loadCharacter(accounts.idFor("alice"));
            assertEquals(1, alice.inventory.get(world.content.product(productId)), "saved with the drop, before any character save");
        }
    }

    @Test
    void aDropWhoseOwnerCantBeSavedIsntSavedEither() throws Exception {
        try (WorldStore store = open(1)) {
            World world = store.loadOrCreateWorld(1, "w", 3);
            save(store, world, WorldGenerator.newMonsterType(world));
            ContentBatch drop = WorldGenerator.newDrop(world, world.content.monsters.get(0).id, "ghost");
            int noSuchCharacter = 999;
            assertThrows(SQLException.class, () -> store.saveContent(drop,
                    new net.shchoo.scafe.scafe.server.Storage.Grant(noSuchCharacter, drop.drops().get(0).productId(), 1)));
        }
        try (WorldStore reopened = open(1)) {
            assertEquals(1, reopened.loadOrCreateWorld(1, "w", 0).content.version(), "only the monster batch, not the drop");
        }
    }
}
