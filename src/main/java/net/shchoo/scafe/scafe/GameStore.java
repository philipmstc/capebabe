package net.shchoo.scafe.scafe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/**
 * Server-side persistence (SQLite). World content is stored per {@link ContentBatch} (one transaction each) and
 * reloaded by replaying the batches; characters are saved as a whole (one transaction each).
 * Only the server thread uses a store.
 */
public class GameStore implements AutoCloseable {
    private static final List<String> MIGRATIONS = List.of(
            // v1
            """
            create table world (id integer primary key, name text not null, seed integer not null,
                                content_version integer not null default 0);
            create table skill (world_id integer not null references world(id), id integer not null,
                                content_version integer not null, ordinal integer not null,
                                name text not null, type text not null, color_index integer not null, symbol text not null,
                                primary key (world_id, id));
            create table product (world_id integer not null references world(id), id integer not null,
                                  content_version integer not null, ordinal integer not null, name text not null,
                                  primary key (world_id, id));
            create table product_line (world_id integer not null references world(id), id integer not null,
                                       content_version integer not null, ordinal integer not null, skill_id integer not null,
                                       primary key (world_id, id));
            create table recipe (world_id integer not null references world(id), id integer not null,
                                 content_version integer not null, ordinal integer not null,
                                 product_line_id integer not null, product_id integer not null,
                                 level_needed integer not null, exp_gained integer not null,
                                 primary key (world_id, id));
            create table recipe_component (world_id integer not null, recipe_id integer not null, ordinal integer not null,
                                           product_id integer not null, count integer not null,
                                           primary key (world_id, recipe_id, product_id));
            create table node (world_id integer not null references world(id), id integer not null,
                               content_version integer not null, ordinal integer not null,
                               x integer not null, y integer not null, recipe_id integer not null,
                               capacity integer not null, max_charges integer not null, respawn_ticks integer not null,
                               primary key (world_id, id));
            create table account (id integer primary key autoincrement, name text not null unique);
            create table game_character (id integer primary key autoincrement,
                                         account_id integer not null references account(id),
                                         world_id integer not null references world(id),
                                         name text not null, x integer not null, y integer not null,
                                         last_action_x integer, last_action_y integer,
                                         unique (account_id, world_id));
            create table character_skill_xp (character_id integer not null references game_character(id),
                                             skill_id integer not null, xp integer not null,
                                             primary key (character_id, skill_id));
            create table inventory (character_id integer not null references game_character(id), ordinal integer not null,
                                    product_id integer not null, count integer not null,
                                    primary key (character_id, product_id));
            create table goal (character_id integer not null references game_character(id), ordinal integer not null,
                               skill_id integer not null, level integer not null, product_id integer not null,
                               primary key (character_id, skill_id, level, product_id));
            create table character_tried_node (character_id integer not null references game_character(id),
                                               node_id integer not null, primary key (character_id, node_id));
            """
    );

    private final Connection connection;

    public GameStore(String jdbcUrl) throws SQLException {
        connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("pragma foreign_keys = on");
        }
        migrate();
    }

    public static GameStore openFile(Path file) throws Exception {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        return new GameStore("jdbc:sqlite:" + file.toAbsolutePath());
    }

    public static Path defaultFile() {
        return Path.of(System.getProperty("user.home"), ".capebabe", "capebabe.db");
    }

    private void migrate() throws SQLException {
        int version;
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("pragma user_version")) {
            version = rs.next() ? rs.getInt(1) : 0;
        }
        for (int i = version; i < MIGRATIONS.size(); i++) {
            int target = i + 1;
            transaction(() -> {
                try (Statement statement = connection.createStatement()) {
                    for (String sql : MIGRATIONS.get(target - 1).split(";")) {
                        if (!sql.isBlank()) {
                            statement.execute(sql);
                        }
                    }
                    statement.execute("pragma user_version = " + target);
                }
            });
        }
    }

    public int schemaVersion() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("pragma user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    // ---- worlds and content ----

    /** Loads the world (replaying its content batches), or creates it with {@code seedIfNew}. */
    public World loadOrCreateWorld(int id, String name, long seedIfNew) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("select name, seed from world where id = ?")) {
            select.setInt(1, id);
            try (ResultSet rs = select.executeQuery()) {
                if (rs.next()) {
                    World world = new World(id, rs.getString("name"), rs.getLong("seed"));
                    loadBatches(id).forEach(world.content::apply);
                    return world;
                }
            }
        }
        try (PreparedStatement insert = connection.prepareStatement("insert into world (id, name, seed) values (?, ?, ?)")) {
            insert.setInt(1, id);
            insert.setString(2, name);
            insert.setLong(3, seedIfNew);
            insert.executeUpdate();
        }
        return new World(id, name, seedIfNew);
    }

    /** Writes the whole batch and bumps the world's content version, or nothing at all. */
    public void saveBatch(int worldId, ContentBatch batch) throws SQLException {
        transaction(() -> {
            int v = batch.version();
            int ordinal = 0;
            try (PreparedStatement ps = connection.prepareStatement(
                    "insert into skill (world_id, id, content_version, ordinal, name, type, color_index, symbol) values (?,?,?,?,?,?,?,?)")) {
                for (ContentBatch.SkillDef d : batch.skills()) {
                    set(ps, worldId, d.id(), v, ordinal++, d.name(), d.type().name(), d.colorIndex(), String.valueOf(d.symbol()));
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "insert into product (world_id, id, content_version, ordinal, name) values (?,?,?,?,?)")) {
                for (ContentBatch.ProductDef d : batch.products()) {
                    set(ps, worldId, d.id(), v, ordinal++, d.name());
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "insert into product_line (world_id, id, content_version, ordinal, skill_id) values (?,?,?,?,?)")) {
                for (ContentBatch.ProductLineDef d : batch.productLines()) {
                    set(ps, worldId, d.id(), v, ordinal++, d.skillId());
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "insert into recipe (world_id, id, content_version, ordinal, product_line_id, product_id, level_needed, exp_gained) values (?,?,?,?,?,?,?,?)");
                 PreparedStatement component = connection.prepareStatement(
                         "insert into recipe_component (world_id, recipe_id, ordinal, product_id, count) values (?,?,?,?,?)")) {
                for (ContentBatch.RecipeDef d : batch.recipes()) {
                    set(ps, worldId, d.id(), v, ordinal++, d.productLineId(), d.productId(), d.levelNeeded(), d.expGained());
                    ps.executeUpdate();
                    int componentOrdinal = 0;
                    for (Map.Entry<Integer, Integer> c : d.components().entrySet()) {
                        set(component, worldId, d.id(), componentOrdinal++, c.getKey(), c.getValue());
                        component.executeUpdate();
                    }
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "insert into node (world_id, id, content_version, ordinal, x, y, recipe_id, capacity, max_charges, respawn_ticks) values (?,?,?,?,?,?,?,?,?,?)")) {
                for (ContentBatch.NodeDef d : batch.nodes()) {
                    set(ps, worldId, d.id(), v, ordinal++, d.x(), d.y(), d.recipeId(), d.capacity(), d.maxCharges(), d.respawnTicks());
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "update world set content_version = ? where id = ? and content_version = ?")) {
                set(ps, v, worldId, v - 1);
                if (ps.executeUpdate() != 1) {
                    throw new SQLException("World " + worldId + " is not at content version " + (v - 1));
                }
            }
        });
    }

    private List<ContentBatch> loadBatches(int worldId) throws SQLException {
        int version;
        try (PreparedStatement ps = connection.prepareStatement("select content_version from world where id = ?")) {
            ps.setInt(1, worldId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                version = rs.getInt(1);
            }
        }
        List<List<ContentBatch.SkillDef>> skills = buckets(version);
        List<List<ContentBatch.ProductDef>> products = buckets(version);
        List<List<ContentBatch.ProductLineDef>> productLines = buckets(version);
        List<List<ContentBatch.RecipeDef>> recipes = buckets(version);
        List<List<ContentBatch.NodeDef>> nodes = buckets(version);

        query("select * from skill where world_id = ? order by content_version, ordinal", worldId, rs ->
                skills.get(rs.getInt("content_version") - 1).add(new ContentBatch.SkillDef(rs.getInt("id"), rs.getString("name"),
                        SkillType.valueOf(rs.getString("type")), rs.getInt("color_index"), rs.getString("symbol").charAt(0))));
        query("select * from product where world_id = ? order by content_version, ordinal", worldId, rs ->
                products.get(rs.getInt("content_version") - 1).add(new ContentBatch.ProductDef(rs.getInt("id"), rs.getString("name"))));
        query("select * from product_line where world_id = ? order by content_version, ordinal", worldId, rs ->
                productLines.get(rs.getInt("content_version") - 1).add(new ContentBatch.ProductLineDef(rs.getInt("id"), rs.getInt("skill_id"))));
        Map<Integer, Map<Integer, Integer>> components = new HashMap<>();
        query("select * from recipe_component where world_id = ? order by recipe_id, ordinal", worldId, rs ->
                components.computeIfAbsent(rs.getInt("recipe_id"), k -> new LinkedHashMap<>()).put(rs.getInt("product_id"), rs.getInt("count")));
        query("select * from recipe where world_id = ? order by content_version, ordinal", worldId, rs ->
                recipes.get(rs.getInt("content_version") - 1).add(new ContentBatch.RecipeDef(rs.getInt("id"), rs.getInt("product_line_id"),
                        rs.getInt("product_id"), rs.getInt("level_needed"), rs.getInt("exp_gained"),
                        components.getOrDefault(rs.getInt("id"), Map.of()))));
        query("select * from node where world_id = ? order by content_version, ordinal", worldId, rs ->
                nodes.get(rs.getInt("content_version") - 1).add(new ContentBatch.NodeDef(rs.getInt("id"), rs.getInt("x"), rs.getInt("y"),
                        rs.getInt("recipe_id"), rs.getInt("capacity"), rs.getInt("max_charges"), rs.getInt("respawn_ticks"))));

        List<ContentBatch> batches = new ArrayList<>();
        for (int i = 0; i < version; i++) {
            batches.add(new ContentBatch(i + 1, skills.get(i), products.get(i), productLines.get(i), recipes.get(i), nodes.get(i)));
        }
        return batches;
    }

    // ---- characters ----

    /** The account's character in this world, or null if it doesn't have one yet. */
    public GameCharacter loadCharacter(World world, String accountName) throws SQLException {
        GameCharacter character;
        try (PreparedStatement ps = connection.prepareStatement(
                "select c.* from game_character c join account a on a.id = c.account_id where a.name = ? and c.world_id = ?")) {
            ps.setString(1, accountName);
            ps.setInt(2, world.id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                character = new GameCharacter(rs.getInt("id"), rs.getString("name"));
                character.x = rs.getInt("x");
                character.y = rs.getInt("y");
                int lastX = rs.getInt("last_action_x");
                if (!rs.wasNull()) {
                    character.lastActionAt = new Board.Position(lastX, rs.getInt("last_action_y"));
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

    public GameCharacter createCharacter(World world, String accountName) throws SQLException {
        int[] id = new int[1];
        transaction(() -> {
            try (PreparedStatement ps = connection.prepareStatement("insert or ignore into account (name) values (?)")) {
                ps.setString(1, accountName);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "insert into game_character (account_id, world_id, name, x, y) select id, ?, ?, 0, 0 from account where name = ?")) {
                set(ps, world.id, accountName, accountName);
                ps.executeUpdate();
            }
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("select last_insert_rowid()")) {
                rs.next();
                id[0] = rs.getInt(1);
            }
        });
        return new GameCharacter(id[0], accountName);
    }

    public void saveCharacter(GameCharacter character) throws SQLException {
        transaction(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "update game_character set x = ?, y = ?, last_action_x = ?, last_action_y = ? where id = ?")) {
                Board.Position last = character.lastActionAt;
                set(ps, character.x, character.y, last == null ? null : last.x(), last == null ? null : last.y(), character.id);
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

    // ---- plumbing ----

    private interface SqlWork {
        void run() throws SQLException;
    }

    private interface RowHandler {
        void handle(ResultSet rs) throws SQLException;
    }

    private void transaction(SqlWork work) throws SQLException {
        connection.setAutoCommit(false);
        try {
            work.run();
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void query(String sql, int param, RowHandler handler) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    handler.handle(rs);
                }
            }
        }
    }

    private static void set(PreparedStatement ps, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) {
            ps.setObject(i + 1, values[i]);
        }
    }

    private static <T> List<List<T>> buckets(int count) {
        List<List<T>> buckets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            buckets.add(new ArrayList<>());
        }
        return buckets;
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
