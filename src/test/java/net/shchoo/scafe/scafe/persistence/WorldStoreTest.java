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
            assertEquals(25, one.schemaVersion(), "v1 base, v2 monsters, v3 drop finders, v4 roam + speed, v5 facing/color/run, "
                    + "v6 auto-investigate, v7 content origins, v8 product tiers, v9 contrast tiers, v10 time estimate, v11 compact chat + stats, v12 equipped stack, v13 weapon + armor, v14 light mode, v15 buildings + banks, v16 hp, v17 prestige, v18 display settings moved to the client, v19 church blessings, v20 the oracle, v21 the Merch Ant, v22 drops found more than once, v23 blessings as items, v24 stacked blessings, v25 xp for split-off skills");
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
            save(store, world, WorldGenerator.newSkills(world, 2));
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
    void xpAndPrestigeInASkillSplitOffAnOldWorldsExtraLineAreSaved() throws Exception {
        int splitId;
        try (WorldStore store = open(1)) {
            World world = store.loadOrCreateWorld(1, "w", 9);
            // an old world's skill with two product lines: the second becomes a skill of its own, with no skill row
            WorldContent made = world.content.copy();
            java.util.Random rng = new java.util.Random(1);
            Skill old = net.shchoo.scafe.scafe.generation.SkillFactory.createSkill(made, rng, "skill0", SkillType.PRIMARY);
            net.shchoo.scafe.scafe.generation.SkillFactory.addNewProductLineToSkill(old, made, rng);
            save(store, world, made.diff(world.content));
            Skill split = world.content.craftingSkills().get(1);
            assertNotNull(world.content.splitFrom(split));
            splitId = split.id;

            GameCharacter alice = store.loadOrCreateCharacter("alice");
            alice.xp.put(split, 1234L);
            alice.prestige.put(split, 1);
            alice.bankedXp.put(split, 99L);
            store.saveCharacter(alice);
        }
        try (WorldStore store = open(1)) {
            World world = store.loadOrCreateWorld(1, "w", 9);
            GameCharacter alice = store.loadCharacter(accounts.idFor("alice"));
            Skill split = world.content.skill(splitId);
            assertEquals(1234L, alice.getExperience(split));
            assertEquals(1, alice.prestige.get(split));
            assertEquals(99L, alice.bankedXp.get(split));
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
            assertTrue(alice.running, "new characters run");
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
            alice.kills.put(77, 12L);
            alice.stats.put(net.shchoo.scafe.scafe.model.Stat.STEPS, 9L);
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
            assertEquals(java.util.Map.of(77, 12L), alice.kills, "kills by kind, kept with the stats");
            assertEquals(9L, alice.stats.get(net.shchoo.scafe.scafe.model.Stat.STEPS));
        }
    }

    @Test
    void theBankAndWhatsInItAreKept() throws Exception {
        Position below;
        try (WorldStore store = open(1)) {
            GameServer server = new GameServer(store.loadOrCreateWorld(1, "w", 5), store);
            server.setDiscovery(Discovery.off());
            server.ensureBuildings();
            Building bank = server.world.content.buildings().stream().filter(b -> b.kind() == Building.Kind.BANK).findFirst().orElseThrow();
            below = new Position(bank.corner().x() + 1, bank.corner().y() + bank.kind().height());
            Player alice = new Player(new LocalConnection(server.connect()));
            alice.send(new Intent.Join("alice"));
            alice.send(new Intent.Admin("1", List.of("1")));
            alice.send(new Intent.Admin("3", List.of()));
            server.tick();
            alice.receive();
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
            Product made = alice.content.nodeAt(new Position(0, 1)).recipe.product;
            alice.send(new Intent.SetRunning(true));
            alice.send(new Intent.WalkTo(below));
            for (int i = 0; i < 80 && !alice.position().equals(below); i++) {
                server.tick();
                alice.receive();
            }
            assertEquals(below, alice.position());
            alice.send(new Intent.Deposit(made.id, 1));
            server.tick();
            alice.receive();
            assertEquals(1, alice.bank.get(made));
            server.stop();
        }
        try (WorldStore store = open(1)) {
            GameServer server = new GameServer(store.loadOrCreateWorld(1, "w", 0), store);
            server.ensureBuildings();
            assertEquals(Building.Kind.values().length, server.world.content.buildings().size(), "still one of each");
            Player alice = new Player(new LocalConnection(server.connect()));
            alice.send(new Intent.Join("alice"));
            server.tick();
            alice.receive();
            assertEquals(1, alice.bank.values().stream().mapToInt(Integer::intValue).sum(), "what's in it");
            assertEquals(below.y() - Building.Kind.BANK.height(), alice.content.buildings().stream().filter(b -> b.kind() == Building.Kind.BANK).findFirst().orElseThrow().corner().y());
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
            alice.send(new Intent.SetAutoInvestigate(true));

            int made = alice.inventory.entrySet().stream().filter(e -> e.getValue() > 0).findFirst().orElseThrow().getKey().id;
            alice.send(new Intent.Equip(EquipSlot.ARMOR, made));
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
            assertTrue(alice.autoInvestigate);

            assertNotNull(alice.armor, "what you wear");
            assertNull(alice.weapon);
            assertEquals(1L, alice.stats.get(Stat.STEPS), "both steps were in one tick");
            assertTrue(alice.stats.get(Stat.ITEMS_CRAFTED) > 0, "and the items made");
            ContentBatch.Origin origin = alice.content.origin(alice.content.skills.get(0).id);
            assertEquals("alice", origin.by(), "whose action made the skill is saved with it");
            assertTrue(origin.at() > 0);
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
            assertEquals(25, store.schemaVersion());
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

    private Player logIn(GameServer server, String name, String password) {
        Player player = new Player(new LocalConnection(server.connect()));
        player.login(name, password);
        server.tick();
        player.receive();
        if (player.loginAccepted != null) {
            player.enterWorld(null);
            server.tick();
            player.receive();
        }
        return player;
    }

    @Test
    void passwordsAreSavedHashedAndOldAccountsGetOneOnTheirFirstLogin() throws Exception {
        try (WorldStore store = open(1)) {
            GameServer server = new GameServer(store.loadOrCreateWorld(1, "w", 0), store);
            server.setDiscovery(Discovery.off());
            store.loadOrCreateCharacter("oldtimer");
            assertNull(store.passwordHash("oldtimer"), "an account from before logins");

            assertTrue(logIn(server, "oldtimer", "claimed").inWorld(), "first login sets it");
            assertTrue(logIn(server, "newbie", "brand-new").inWorld());
            String stored = store.passwordHash("newbie");
            assertTrue(stored.startsWith("pbkdf2$") && !stored.contains("brand-new"), "hashed, not the password");
            server.stop();
        }
        try (WorldStore store = open(1)) {
            GameServer server = new GameServer(store.loadOrCreateWorld(1, "w", 0), store);
            server.setDiscovery(Discovery.off());
            Player wrong = logIn(server, "oldtimer", "something else");
            assertFalse(wrong.inWorld());
            assertEquals("Wrong name or password.", wrong.loginRefused);
            assertTrue(logIn(server, "oldtimer", "claimed").inWorld(), "kept across restarts");
            server.stop();
        }
    }

    @Test
    void productTiersAreSavedAndOldSavesGetThemFromTheNames() throws Exception {
        try (WorldStore store = open(1)) {
            World world = store.loadOrCreateWorld(1, "w", 6);
            save(store, world, WorldGenerator.newSkills(world, 3));
            save(store, world, WorldGenerator.newMonsterType(world));
            save(store, world, WorldGenerator.newDrop(world, world.content.monsters.get(0).id));
        }
        try (WorldStore store = open(1)) {
            World world = store.loadOrCreateWorld(1, "w", 0);
            assertTiersMatchTheirPlaceInTheirLine(world);
        }
        try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + WorldStore.fileFor(dir, 1).toAbsolutePath());
             Statement statement = raw.createStatement()) {
            statement.execute("update product set tier = null");
        }
        try (WorldStore store = open(1)) {
            assertTiersMatchTheirPlaceInTheirLine(store.loadOrCreateWorld(1, "w", 0));
        }
    }

    private static void assertTiersMatchTheirPlaceInTheirLine(World world) {
        for (Skill skill : world.content.skills) {
            for (ProductLine line : skill.productLines) {
                for (int tier = 0; tier < line.recipes.size(); tier++) {
                    assertEquals(tier, line.recipes.get(tier).product.tier, line.recipes.get(tier).product.name);
                }
            }
        }
        Product drop = world.content.monsters.get(0).drops.get(0).product();
        assertEquals(Product.NO_TIER, drop.tier, "one-of-a-kinds have no tier");
    }

    @Test
    void worldsCanBePickedByName() throws Exception {
        assertEquals("gabes-world", WorldStore.fileName("Gabe's World"));
        assertEquals("named-2", WorldStore.fileName("2!"), "never mistaken for a numbered world");
        Path file = WorldStore.fileFor(dir, "Gabe's World");
        assertEquals("world-gabes-world.db", file.getFileName().toString());
        try (WorldStore store = new WorldStore(file, accounts)) {
            assertEquals("Gabe's World", store.loadOrCreateWorld(0, "Gabe's World", 4).name);
        }
        try (WorldStore store = new WorldStore(WorldStore.fileFor(dir, "gabe's world"), accounts)) {
            World again = store.loadOrCreateWorld(0, "gabe's world", 99);
            assertEquals("Gabe's World", again.name, "same file, and it keeps the name it was made with");
            assertEquals(4, again.seed);
        }
    }

    @Test
    void aDropFoundTwiceIsSavedTwice() throws Exception {
        try (WorldStore store = open(1)) {
            World world = store.loadOrCreateWorld(1, "w", 3);
            save(store, world, WorldGenerator.newMonsterType(world));
            int monster = world.content.monsters.get(0).id;
            save(store, world, WorldGenerator.newDrop(world, monster, "alice"));
            save(store, world, WorldGenerator.newDrop(world, monster, "alice"));
            save(store, world, WorldGenerator.newDrop(world, monster, "bob"));
        }
        try (WorldStore store = open(1)) {
            World world = store.loadOrCreateWorld(1, "w", 3);
            MonsterType monster = world.content.monsters.get(0);
            assertEquals(3, monster.drops.size(), "drop0, then drop1 twice");
            assertEquals(2, monster.found(monster.dropProducts().get(1)));
            assertEquals(2, world.content.trophies("alice"));
            assertEquals(1, world.content.trophies("bob"));
        }
    }

    @Test
    void aBlessingItemIsSavedWithItsRecipe() throws Exception {
        int recipeId;
        try (WorldStore store = open(1)) {
            World world = store.loadOrCreateWorld(1, "w", 3);
            save(store, world, WorldGenerator.newSkills(world, 1));
            Recipe recipe = world.content.craftingSkills().get(0).productLines.get(0).recipes.get(2);
            recipeId = recipe.id;
            save(store, world, WorldGenerator.newBlessingProduct(world, recipe));
        }
        try (WorldStore store = open(1)) {
            World world = store.loadOrCreateWorld(1, "w", 3);
            Recipe recipe = world.content.recipe(recipeId);
            Product blessing = world.content.blessing(recipe);
            assertNotNull(blessing);
            assertEquals("blessing of " + recipe.product.name, blessing.name);
        }
    }
}
