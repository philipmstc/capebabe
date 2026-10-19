package net.shchoo.scafe.scafe.persistence;

import net.shchoo.scafe.scafe.model.ContentBatch;
import net.shchoo.scafe.scafe.model.Direction;
import net.shchoo.scafe.scafe.model.Goal;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.Skill;
import net.shchoo.scafe.scafe.model.SkillType;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.model.WorldContent;
import net.shchoo.scafe.scafe.server.GameCharacter;
import net.shchoo.scafe.scafe.server.Storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.*;
import java.util.*;

/**
 * One world's database (its own SQLite file): the world's seed, its content (stored per {@link ContentBatch},
 * one transaction each, reloaded by replaying the batches) and the characters living in it. Characters point
 * at accounts in the shared {@link AccountStore}. Only that world's server thread uses it, and only one process
 * may have it open at a time (a lock on {@code world-<id>.db.lock}, released by the OS even if the process dies).
 */
public class WorldStore extends SqliteStore implements Storage {
    private static final List<String> MIGRATIONS = List.of(
            // v1
            """
            create table world_meta (id integer primary key check (id = 1), name text not null, seed integer not null,
                                     content_version integer not null default 0);
            create table skill (id integer primary key, content_version integer not null, ordinal integer not null,
                                name text not null, type text not null, color_index integer not null, symbol text not null);
            create table product (id integer primary key, content_version integer not null, ordinal integer not null,
                                  name text not null);
            create table product_line (id integer primary key, content_version integer not null, ordinal integer not null,
                                       skill_id integer not null references skill(id));
            create table recipe (id integer primary key, content_version integer not null, ordinal integer not null,
                                 product_line_id integer not null references product_line(id),
                                 product_id integer not null references product(id),
                                 level_needed integer not null, exp_gained integer not null);
            create table recipe_component (recipe_id integer not null references recipe(id), ordinal integer not null,
                                           product_id integer not null references product(id), count integer not null,
                                           primary key (recipe_id, product_id));
            create table node (id integer primary key, content_version integer not null, ordinal integer not null,
                               x integer not null, y integer not null, recipe_id integer not null references recipe(id),
                               capacity integer not null, max_charges integer not null, respawn_ticks integer not null);
            create table game_character (id integer primary key autoincrement, account_id integer not null unique,
                                         name text not null, x integer not null, y integer not null,
                                         last_action_x integer, last_action_y integer);
            create table character_skill_xp (character_id integer not null references game_character(id),
                                             skill_id integer not null references skill(id), xp integer not null,
                                             primary key (character_id, skill_id));
            create table inventory (character_id integer not null references game_character(id), ordinal integer not null,
                                    product_id integer not null references product(id), count integer not null,
                                    primary key (character_id, product_id));
            create table goal (character_id integer not null references game_character(id), ordinal integer not null,
                               skill_id integer not null, level integer not null, product_id integer not null,
                               primary key (character_id, skill_id, level, product_id));
            create table character_tried_node (character_id integer not null references game_character(id),
                                               node_id integer not null references node(id),
                                               primary key (character_id, node_id));
            """,
            // v2: monsters
            """
            create table monster_type (id integer primary key, content_version integer not null, ordinal integer not null,
                                       name text not null, level integer not null, max_hp integer not null,
                                       max_hit integer not null, color_index integer not null, symbol text not null);
            create table monster_drop (monster_id integer not null references monster_type(id),
                                       product_id integer not null references product(id),
                                       content_version integer not null, ordinal integer not null, one_in integer not null,
                                       primary key (monster_id, product_id));
            """,
            // v3: one-of-a-kind drops remember who found them
            """
            alter table monster_drop add column found_by text;
            """,
            // v4: monsters roam their own radius at their own speed
            """
            alter table monster_type add column roam_radius integer not null default 3;
            alter table monster_type add column move_ticks integer not null default 2;
            """,
            // v5: which way you face, your color, whether you're running
            """
            alter table game_character add column facing text not null default 'DOWN';
            alter table game_character add column color_index integer not null default 0;
            alter table game_character add column running integer not null default 0;
            """,
            // v6: auto-investigate setting
            """
            alter table game_character add column auto_investigate integer not null default 0;
            """,
            // v7: whose action made each content batch, and when (older batches have no row)
            """
            create table content_batch (content_version integer primary key, created_by text not null,
                                        created_at integer not null);
            """
    );

    private final AccountStore accounts;
    private World world;

    public WorldStore(Path file, AccountStore accounts) throws Exception {
        super(file, MIGRATIONS);
        this.accounts = accounts;
        try {
            lockChannel = FileChannel.open(Path.of(file.toAbsolutePath() + ".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock acquired;
            try {
                acquired = lockChannel.tryLock();
            } catch (OverlappingFileLockException e) {
                acquired = null;
            }
            if (acquired == null) {
                throw new WorldInUseException(file.getFileName() + " is already open in another game (another window, "
                        + "or one hosting it). Close that one first, or join it with --connect.");
            }
            lock = acquired;
        } catch (RuntimeException | IOException e) {
            if (lockChannel != null) {
                lockChannel.close();
            }
            super.close();
            throw e;
        }
    }

    private FileChannel lockChannel;
    private FileLock lock;

    @Override
    public void close() throws SQLException {
        super.close();
        try {
            lock.release();
            lockChannel.close();
        } catch (IOException e) {
            // the OS drops the lock when we exit anyway
        }
    }

    /** {@code <data dir>/worlds/world-<id>.db} */
    public static Path fileFor(Path dataDir, int worldId) {
        return dataDir.resolve("worlds").resolve("world-" + worldId + ".db");
    }

    /** Loads the world (replaying its content batches), or creates it with {@code seedIfNew}. */
    public World loadOrCreateWorld(int id, String nameIfNew, long seedIfNew) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("select name, seed from world_meta")) {
            if (rs.next()) {
                world = new World(id, rs.getString("name"), rs.getLong("seed"));
                loadBatches().forEach(world.content::apply);
                return world;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement("insert into world_meta (id, name, seed) values (1, ?, ?)")) {
            set(insert, nameIfNew, seedIfNew);
            insert.executeUpdate();
        }
        world = new World(id, nameIfNew, seedIfNew);
        return world;
    }

    // ---- content ----

    @Override
    public void saveContent(ContentBatch batch) throws SQLException {
        saveContent(batch, null);
    }

    /** Writes the whole batch (and the grant, if any) and bumps the content version, or nothing at all. */
    @Override
    public void saveContent(ContentBatch batch, Grant grant) throws SQLException {
        transaction(() -> {
            writeContent(batch);
            if (grant != null) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "insert into inventory (character_id, ordinal, product_id, count) "
                                + "values (?, (select coalesce(max(ordinal), -1) + 1 from inventory where character_id = ?), ?, ?) "
                                + "on conflict (character_id, product_id) do update set count = count + excluded.count")) {
                    set(ps, grant.characterId(), grant.characterId(), grant.productId(), grant.count());
                    ps.executeUpdate();
                }
            }
        });
    }

    private void writeContent(ContentBatch batch) throws SQLException {
        int v = batch.version();
        int ordinal = 0;
        if (batch.origin() != null) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "insert into content_batch (content_version, created_by, created_at) values (?,?,?)")) {
                set(ps, v, batch.origin().by(), batch.origin().at());
                ps.executeUpdate();
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "insert into skill (id, content_version, ordinal, name, type, color_index, symbol) values (?,?,?,?,?,?,?)")) {
            for (ContentBatch.SkillDef d : batch.skills()) {
                set(ps, d.id(), v, ordinal++, d.name(), d.type().name(), d.colorIndex(), String.valueOf(d.symbol()));
                ps.executeUpdate();
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "insert into product (id, content_version, ordinal, name) values (?,?,?,?)")) {
            for (ContentBatch.ProductDef d : batch.products()) {
                set(ps, d.id(), v, ordinal++, d.name());
                ps.executeUpdate();
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "insert into product_line (id, content_version, ordinal, skill_id) values (?,?,?,?)")) {
            for (ContentBatch.ProductLineDef d : batch.productLines()) {
                set(ps, d.id(), v, ordinal++, d.skillId());
                ps.executeUpdate();
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "insert into recipe (id, content_version, ordinal, product_line_id, product_id, level_needed, exp_gained) values (?,?,?,?,?,?,?)");
             PreparedStatement component = connection.prepareStatement(
                     "insert into recipe_component (recipe_id, ordinal, product_id, count) values (?,?,?,?)")) {
            for (ContentBatch.RecipeDef d : batch.recipes()) {
                set(ps, d.id(), v, ordinal++, d.productLineId(), d.productId(), d.levelNeeded(), d.expGained());
                ps.executeUpdate();
                int componentOrdinal = 0;
                for (Map.Entry<Integer, Integer> c : d.components().entrySet()) {
                    set(component, d.id(), componentOrdinal++, c.getKey(), c.getValue());
                    component.executeUpdate();
                }
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "insert into node (id, content_version, ordinal, x, y, recipe_id, capacity, max_charges, respawn_ticks) values (?,?,?,?,?,?,?,?,?)")) {
            for (ContentBatch.NodeDef d : batch.nodes()) {
                set(ps, d.id(), v, ordinal++, d.x(), d.y(), d.recipeId(), d.capacity(), d.maxCharges(), d.respawnTicks());
                ps.executeUpdate();
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "insert into monster_type (id, content_version, ordinal, name, level, max_hp, max_hit, color_index, symbol, roam_radius, move_ticks) values (?,?,?,?,?,?,?,?,?,?,?)")) {
            for (ContentBatch.MonsterDef d : batch.monsters()) {
                set(ps, d.id(), v, ordinal++, d.name(), d.level(), d.maxHp(), d.maxHit(), d.colorIndex(), String.valueOf(d.symbol()),
                        d.roamRadius(), d.moveTicks());
                ps.executeUpdate();
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "insert into monster_drop (monster_id, product_id, content_version, ordinal, one_in, found_by) values (?,?,?,?,?,?)")) {
            for (ContentBatch.DropDef d : batch.drops()) {
                set(ps, d.monsterId(), d.productId(), v, ordinal++, d.oneIn(), d.foundBy());
                ps.executeUpdate();
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "update world_meta set content_version = ? where content_version = ?")) {
            set(ps, v, v - 1);
            if (ps.executeUpdate() != 1) {
                throw new SQLException("World is not at content version " + (v - 1));
            }
        }
    }

    private List<ContentBatch> loadBatches() throws SQLException {
        int version;
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("select content_version from world_meta")) {
            rs.next();
            version = rs.getInt(1);
        }
        List<List<ContentBatch.SkillDef>> skills = buckets(version);
        List<List<ContentBatch.ProductDef>> products = buckets(version);
        List<List<ContentBatch.ProductLineDef>> productLines = buckets(version);
        List<List<ContentBatch.RecipeDef>> recipes = buckets(version);
        List<List<ContentBatch.NodeDef>> nodes = buckets(version);
        List<List<ContentBatch.MonsterDef>> monsters = buckets(version);
        List<List<ContentBatch.DropDef>> drops = buckets(version);

        all("select * from skill order by content_version, ordinal", rs ->
                skills.get(rs.getInt("content_version") - 1).add(new ContentBatch.SkillDef(rs.getInt("id"), rs.getString("name"),
                        SkillType.valueOf(rs.getString("type")), rs.getInt("color_index"), rs.getString("symbol").charAt(0))));
        all("select * from product order by content_version, ordinal", rs ->
                products.get(rs.getInt("content_version") - 1).add(new ContentBatch.ProductDef(rs.getInt("id"), rs.getString("name"))));
        all("select * from product_line order by content_version, ordinal", rs ->
                productLines.get(rs.getInt("content_version") - 1).add(new ContentBatch.ProductLineDef(rs.getInt("id"), rs.getInt("skill_id"))));
        Map<Integer, Map<Integer, Integer>> components = new HashMap<>();
        all("select * from recipe_component order by recipe_id, ordinal", rs ->
                components.computeIfAbsent(rs.getInt("recipe_id"), k -> new LinkedHashMap<>()).put(rs.getInt("product_id"), rs.getInt("count")));
        all("select * from recipe order by content_version, ordinal", rs ->
                recipes.get(rs.getInt("content_version") - 1).add(new ContentBatch.RecipeDef(rs.getInt("id"), rs.getInt("product_line_id"),
                        rs.getInt("product_id"), rs.getInt("level_needed"), rs.getInt("exp_gained"),
                        components.getOrDefault(rs.getInt("id"), Map.of()))));
        all("select * from node order by content_version, ordinal", rs ->
                nodes.get(rs.getInt("content_version") - 1).add(new ContentBatch.NodeDef(rs.getInt("id"), rs.getInt("x"), rs.getInt("y"),
                        rs.getInt("recipe_id"), rs.getInt("capacity"), rs.getInt("max_charges"), rs.getInt("respawn_ticks"))));
        all("select * from monster_type order by content_version, ordinal", rs ->
                monsters.get(rs.getInt("content_version") - 1).add(new ContentBatch.MonsterDef(rs.getInt("id"), rs.getString("name"),
                        rs.getInt("level"), rs.getInt("max_hp"), rs.getInt("max_hit"), rs.getInt("color_index"),
                        rs.getString("symbol").charAt(0), rs.getInt("roam_radius"), rs.getInt("move_ticks"))));
        all("select * from monster_drop order by content_version, ordinal", rs ->
                drops.get(rs.getInt("content_version") - 1).add(new ContentBatch.DropDef(rs.getInt("monster_id"),
                        rs.getInt("product_id"), rs.getInt("one_in"), rs.getString("found_by"))));

        Map<Integer, ContentBatch.Origin> origins = new HashMap<>();
        all("select * from content_batch", rs -> origins.put(rs.getInt("content_version"),
                new ContentBatch.Origin(rs.getString("created_by"), rs.getLong("created_at"))));

        List<ContentBatch> batches = new ArrayList<>();
        for (int i = 0; i < version; i++) {
            batches.add(new ContentBatch(i + 1, skills.get(i), products.get(i), productLines.get(i), recipes.get(i), nodes.get(i),
                    monsters.get(i), drops.get(i), origins.get(i + 1)));
        }
        return batches;
    }

    // ---- characters ----

    @Override
    public String passwordHash(String accountName) throws SQLException {
        return accounts.passwordHash(accountName);
    }

    @Override
    public void setPasswordHash(String accountName, String hash) throws SQLException {
        accounts.setPasswordHash(accountName, hash);
    }

    @Override
    public GameCharacter loadOrCreateCharacter(String accountName) throws SQLException {
        int accountId = accounts.idFor(accountName);
        GameCharacter character = loadCharacter(accountId);
        return character != null ? character : createCharacter(accountId, accountName);
    }

    /** The account's character in this world, or null if it doesn't have one yet. */
    public GameCharacter loadCharacter(int accountId) throws SQLException {
        GameCharacter character;
        try (PreparedStatement ps = connection.prepareStatement("select * from game_character where account_id = ?")) {
            ps.setInt(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                character = new GameCharacter(rs.getInt("id"), rs.getString("name"));
                character.x = rs.getInt("x");
                character.y = rs.getInt("y");
                character.facing = Direction.valueOf(rs.getString("facing"));
                character.colorIndex = rs.getInt("color_index");
                character.running = rs.getInt("running") != 0;
                character.autoInvestigate = rs.getInt("auto_investigate") != 0;
                int lastX = rs.getInt("last_action_x");
                if (!rs.wasNull()) {
                    character.lastActionAt = new Position(lastX, rs.getInt("last_action_y"));
                }
            }
        }
        WorldContent content = world.content;
        int id = character.id;
        query("select * from character_skill_xp where character_id = ?", id, rs ->
                character.xp.put(content.skill(rs.getInt("skill_id")), rs.getLong("xp")));
        query("select * from inventory where character_id = ? order by ordinal", id, rs ->
                character.inventory.put(content.product(rs.getInt("product_id")), rs.getInt("count")));
        query("select * from goal where character_id = ? order by ordinal", id, rs ->
                character.goals.add(new Goal(content.skill(rs.getInt("skill_id")), rs.getInt("level"), content.product(rs.getInt("product_id")))));
        query("select * from character_tried_node where character_id = ?", id, rs ->
                character.triedNodes.add(rs.getInt("node_id")));
        return character;
    }

    public GameCharacter createCharacter(int accountId, String name) throws SQLException {
        int[] id = new int[1];
        transaction(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "insert into game_character (account_id, name, x, y) values (?, ?, 0, 0)")) {
                set(ps, accountId, name);
                ps.executeUpdate();
            }
            id[0] = lastInsertId();
        });
        return new GameCharacter(id[0], name);
    }

    @Override
    public void saveCharacter(GameCharacter character) throws SQLException {
        transaction(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "update game_character set x = ?, y = ?, last_action_x = ?, last_action_y = ?, facing = ?, color_index = ?, running = ?, auto_investigate = ? where id = ?")) {
                Position last = character.lastActionAt;
                set(ps, character.x, character.y, last == null ? null : last.x(), last == null ? null : last.y(),
                        character.facing.name(), character.colorIndex, character.running ? 1 : 0,
                        character.autoInvestigate ? 1 : 0, character.id);
                ps.executeUpdate();
            }
            for (String table : List.of("character_skill_xp", "inventory", "goal", "character_tried_node")) {
                try (PreparedStatement ps = connection.prepareStatement("delete from " + table + " where character_id = ?")) {
                    ps.setInt(1, character.id);
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = connection.prepareStatement("insert into character_skill_xp values (?, ?, ?)")) {
                for (Map.Entry<Skill, Long> e : character.xp.entrySet()) {
                    set(ps, character.id, e.getKey().id, e.getValue());
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = connection.prepareStatement("insert into inventory values (?, ?, ?, ?)")) {
                int ordinal = 0;
                for (Map.Entry<Product, Integer> e : character.inventory.entrySet()) {
                    set(ps, character.id, ordinal++, e.getKey().id, e.getValue());
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = connection.prepareStatement("insert into goal values (?, ?, ?, ?, ?)")) {
                int ordinal = 0;
                for (Goal goal : character.goals) {
                    set(ps, character.id, ordinal++, goal.skill().id, goal.level(), goal.product().id);
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = connection.prepareStatement("insert into character_tried_node values (?, ?)")) {
                for (int nodeId : character.triedNodes) {
                    set(ps, character.id, nodeId);
                    ps.executeUpdate();
                }
            }
        });
    }

    private void all(String sql, RowHandler handler) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                handler.handle(rs);
            }
        }
    }

    private static <T> List<List<T>> buckets(int count) {
        List<List<T>> buckets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            buckets.add(new ArrayList<>());
        }
        return buckets;
    }
}
