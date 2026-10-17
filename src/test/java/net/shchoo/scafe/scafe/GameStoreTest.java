package net.shchoo.scafe.scafe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GameStoreTest {
    @TempDir
    Path dir;

    private GameStore open() throws Exception {
        return GameStore.openFile(dir.resolve("test.db"));
    }

    @Test
    void migratesOnceAndRemembersTheSeed() throws Exception {
        try (GameStore store = open()) {
            assertEquals(1, store.schemaVersion());
            assertEquals(777, store.loadOrCreateWorld(1, "w", 777).seed);
        }
        try (GameStore store = open()) {
            assertEquals(1, store.schemaVersion());
            assertEquals(777, store.loadOrCreateWorld(1, "w", 123).seed);
        }
    }

    @Test
    void contentBatchesRoundTrip() throws Exception {
        List<ContentBatch> history;
        try (GameStore store = open()) {
            World world = store.loadOrCreateWorld(1, "w", 42);
            for (ContentBatch batch : List.of(WorldGenerator.newSkills(world, 6))) {
                store.saveBatch(world.id, batch);
                world.content.apply(batch);
            }
            ContentBatch lines = WorldGenerator.newProductLines(world, 2);
            store.saveBatch(world.id, lines);
            world.content.apply(lines);
            ContentBatch node = WorldGenerator.newNode(world, new Board.Position(-3, 4), 2, 10, 5);
            store.saveBatch(world.id, node);
            world.content.apply(node);
            history = List.copyOf(world.content.history());
        }
        try (GameStore store = open()) {
            World reloaded = store.loadOrCreateWorld(1, "w", 0);
            assertEquals(history, reloaded.content.history());
            Node node = reloaded.content.nodeAt(new Board.Position(-3, 4));
            assertEquals(2, node.capacity);
            assertEquals(10, node.maxCharges);
        }
    }

    @Test
    void aBatchOutOfOrderWritesNothing() throws Exception {
        try (GameStore store = open()) {
            World world = store.loadOrCreateWorld(1, "w", 1);
            ContentBatch first = WorldGenerator.newSkills(world, 2);
            ContentBatch skipped = new ContentBatch(5, first.skills(), first.products(), first.productLines(), first.recipes(), first.nodes());
            assertThrows(SQLException.class, () -> store.saveBatch(world.id, skipped));
            store.saveBatch(world.id, first);
        }
        try (GameStore store = open()) {
            assertEquals(1, store.loadOrCreateWorld(1, "w", 1).content.version());
        }
    }

    @Test
    void charactersRoundTrip() throws Exception {
        int nodeId;
        try (GameStore store = open()) {
            World world = store.loadOrCreateWorld(1, "w", 9);
            ContentBatch skills = WorldGenerator.newSkills(world, 2);
            store.saveBatch(world.id, skills);
            world.content.apply(skills);
            ContentBatch node = WorldGenerator.newNode(world, new Board.Position(1, 1), 0, 0, 0);
            store.saveBatch(world.id, node);
            world.content.apply(node);
            nodeId = node.nodes().get(0).id();

            assertNull(store.loadCharacter(world, "alice"));
            GameCharacter alice = store.createCharacter(world, "alice");
            Skill skill = world.content.skills.get(0);
            Recipe recipe = skill.productLines.get(0).recipes.get(1);
            alice.x = 4;
            alice.y = -2;
            alice.lastActionAt = new Board.Position(1, 1);
            alice.xp.put(skill, 5000L);
            alice.inventory.put(recipe.product, 3);
            alice.inventory.put(skill.productLines.get(0).recipes.get(0).product, 0);
            alice.goals.add(new Goal(skill, recipe.levelNeeded, recipe.product));
            alice.triedNodes.add(nodeId);
            store.saveCharacter(alice);
        }
        try (GameStore store = open()) {
            World world = store.loadOrCreateWorld(1, "w", 0);
            GameCharacter alice = store.loadCharacter(world, "alice");
            Skill skill = world.content.skills.get(0);
            Recipe recipe = skill.productLines.get(0).recipes.get(1);
            assertEquals(new Board.Position(4, -2), alice.position());
            assertEquals(new Board.Position(1, 1), alice.lastActionAt);
            assertEquals(5000L, alice.getExperience(skill));
            assertEquals(List.of(recipe.product, skill.productLines.get(0).recipes.get(0).product), List.copyOf(alice.inventory.keySet()));
            assertEquals(3, alice.inventory.get(recipe.product));
            assertEquals(List.of(new Goal(skill, recipe.levelNeeded, recipe.product)), alice.goals);
            assertTrue(alice.triedNodes.contains(nodeId));
            assertNull(store.loadCharacter(world, "bob"));
        }
    }

    @Test
    void aServerRestartKeepsTheWorldAndYourProgress() throws Exception {
        Path db = dir.resolve("restart.db");
        long xpBefore;
        int skills;
        try (GameStore store = GameStore.openFile(db)) {
            GameServer server = new GameServer(store.loadOrCreateWorld(1, "w", 5), store);
            server.setDiscovery(Discovery.off());
            Player alice = new Player(new LocalConnection(server.connect()));
            alice.send(new Intent.Join("alice"));
            alice.send(new Intent.Admin("1", List.of("2")));
            alice.send(new Intent.Admin("3", List.of()));
            server.tick();
            alice.receive();
            // the random node may be locked for a fresh character; reroll until it isn't
            for (int i = 0; i < 50 && !alice.content.nodeAt(new Board.Position(0, 0)).recipe.levelMet(1); i++) {
                alice.send(new Intent.Admin("3", List.of()));
                server.tick();
                alice.receive();
            }
            alice.send(new Intent.Perform());
            for (int i = 0; i < 6; i++) {
                server.tick();
                alice.receive();
            }
            alice.moveBy(2, 0);
            server.tick();
            alice.receive();
            xpBefore = alice.content.skills.stream().mapToLong(alice::getExperience).sum();
            skills = alice.content.skills.size();
            assertTrue(xpBefore > 0);
            server.stop();
        }
        try (GameStore store = GameStore.openFile(db)) {
            GameServer server = new GameServer(store.loadOrCreateWorld(1, "w", 0), store);
            server.setDiscovery(Discovery.off());
            Player alice = new Player(new LocalConnection(server.connect()));
            alice.send(new Intent.Join("alice"));
            server.tick();
            alice.receive();
            assertEquals(skills, alice.content.skills.size());
            assertEquals(xpBefore, alice.content.skills.stream().mapToLong(alice::getExperience).sum());
            assertEquals(new Board.Position(2, 0), alice.position());
            assertFalse(alice.inventory.isEmpty());
        }
    }
}
