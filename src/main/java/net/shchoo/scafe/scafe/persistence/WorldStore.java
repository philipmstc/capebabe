package net.shchoo.scafe.scafe.persistence;

import net.shchoo.scafe.scafe.model.Building;
import net.shchoo.scafe.scafe.model.ContentBatch;
import net.shchoo.scafe.scafe.model.Direction;
import net.shchoo.scafe.scafe.model.Goal;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.Skill;
import net.shchoo.scafe.scafe.model.SkillType;
import net.shchoo.scafe.scafe.model.Stat;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.model.WorldContent;
import net.shchoo.scafe.scafe.server.GameCharacter;
import net.shchoo.scafe.scafe.server.Oracle;
import net.shchoo.scafe.scafe.server.Storage;

import java.sql.ResultSet;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
            """,
            // v8: product tiers as data (-1 = no tier). Rows from before are null and read their tier off the name.
            """
            alter table product add column tier integer;
            """,
            // v9: show product tiers player setting
            """
            alter table game_character add column show_product_tiers integer not null default 0;
            """,
            // v10: show time estimate setting
            """ 
            alter table game_character add column show_time_estimate integer not null default 0;
            """,
            // v11: compact chat setting, and lifetime stats per character (one row per stat, by Stat name)
            """
            alter table game_character add column compact_chat integer not null default 0;
            create table character_stat (character_id integer not null references game_character(id),
                                         stat text not null, value integer not null,
                                         primary key (character_id, stat));
            """,
            // v12: the equipped stack (one item type, how many left)
            """
            alter table game_character add column equipped_product_id integer references product(id);
            alter table game_character add column equipped_count integer not null default 0;
            """,
            // v13: two slots, weapon + armour, each used up from the inventory (no separate count)
            """
            alter table game_character rename column equipped_product_id to weapon_product_id;
            alter table game_character drop column equipped_count;
            alter table game_character add column armor_product_id integer references product(id);
            """,
            // v14: light mode setting
            """
            alter table game_character add column light_mode integer not null default 0;
            """,
            // v15: buildings (the bank) as content, and each character's bank
            """
            create table building (id integer primary key, content_version integer not null, ordinal integer not null,
                                   kind text not null, x integer not null, y integer not null);
            create table bank_item (character_id integer not null references game_character(id), ordinal integer not null,
                                    product_id integer not null references product(id), count integer not null,
                                    primary key (character_id, ordinal));
            """,
            // v16: hp is kept (it doesn't come back by itself any more); null = full
            """
            alter table game_character add column hp integer;
            """,
            // v17: prestige -- prestige versions of products, and each character's prestige + banked xp per skill
            """
            alter table product add column base_id integer references product(id);
            alter table product add column prestige integer not null default 0;
            create table character_prestige (character_id integer not null references game_character(id),
                                             skill_id integer not null references skill(id),
                                             prestige integer not null, banked_xp integer not null,
                                             primary key (character_id, skill_id));
            """,
            // v18: the display settings moved to the client's own settings.properties (DisplaySettings)
            """
            alter table game_character drop column show_product_tiers;
            alter table game_character drop column show_time_estimate;
            alter table game_character drop column compact_chat;
            alter table game_character drop column light_mode;
            """,
            // v19: a church blessing (Prayer): the recipe prayed for, and steps / actions left
            """
            alter table game_character add column blessing_recipe_id integer references recipe(id);
            alter table game_character add column blessing_left integer not null default 0;
            """,
            // v20: the church's oracle (Oracle): new-skill rolls since the last skill, rolls an hour, average chance
            """
            alter table world_meta add column oracle_rolls_since real not null default 0;
            alter table world_meta add column oracle_rolls_per_hour real not null default 0;
            alter table world_meta add column oracle_base_chance real not null default 0;
            """,
            // v21: whether the Merch Ant has asked for you (your first prestige) and trades with you
            """
            alter table game_character add column met_merchant integer not null default 0;
            """,
            // v22: a drop can be found more than once (drop k: k + 1 copies), so a row a find, not a row a product
            """
            create table monster_drop_v22 (monster_id integer not null references monster_type(id),
                                           product_id integer not null references product(id),
                                           content_version integer not null, ordinal integer not null,
                                           one_in integer not null, found_by text,
                                           primary key (content_version, ordinal));
            insert into monster_drop_v22 select monster_id, product_id, content_version, ordinal, one_in, found_by from monster_drop;
            drop table monster_drop;
            alter table monster_drop_v22 rename to monster_drop;
            """,
            // v23: church blessings are items: a product can be the blessing for a recipe
            """
            alter table product add column blesses_recipe_id integer references recipe(id);
            """,
            // v24: blessings of the same recipe stack: how many
            """
            alter table game_character add column blessing_strength integer not null default 1;
            """,
            // v25: a skill split off an old world's extra product line has the line's id and no skill row, so xp and
            // prestige can't point into skill (a character with xp in one couldn't be saved at all)
            """
            create table character_skill_xp_v25 (character_id integer not null references game_character(id),
                                                 skill_id integer not null, xp integer not null,
                                                 primary key (character_id, skill_id));
            insert into character_skill_xp_v25 select character_id, skill_id, xp from character_skill_xp;
            drop table character_skill_xp;
            alter table character_skill_xp_v25 rename to character_skill_xp;
            create table character_prestige_v25 (character_id integer not null references game_character(id),
                                                 skill_id integer not null, prestige integer not null,
                                                 banked_xp integer not null, primary key (character_id, skill_id));
            insert into character_prestige_v25 select character_id, skill_id, prestige, banked_xp from character_prestige;
            drop table character_prestige;
            alter table character_prestige_v25 rename to character_prestige;
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

    /** A world picked by name: {@code <data dir>/worlds/world-<file name>.db}, e.g. "Gabe's World" -> world-gabes-world.db. */
    public static Path fileFor(Path dataDir, String worldName) {
        return dataDir.resolve("worlds").resolve("world-" + fileName(worldName) + ".db");
    }

    /** Lowercase letters and digits with dashes between words ("Gabe's World" -> "gabes-world"); never just digits. */
    public static String fileName(String worldName) {
        String name = worldName.toLowerCase(Locale.ROOT).replace("'", "")
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return name.matches("\\d*") ? "named-" + name : name;
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

    @Override
    public Oracle.Saved loadOracle() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("select oracle_rolls_since, oracle_rolls_per_hour, oracle_base_chance from world_meta")) {
            return rs.next() ? new Oracle.Saved(rs.getDouble(1), rs.getDouble(2), rs.getDouble(3)) : null;
        }
    }

    @Override
    public void saveOracle(Oracle.Saved saved) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "update world_meta set oracle_rolls_since = ?, oracle_rolls_per_hour = ?, oracle_base_chance = ?")) {
            set(ps, saved.rollsSince(), saved.rollsPerHour(), saved.baseChance());
            ps.executeUpdate();
        }
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
                "insert into product (id, content_version, ordinal, name, tier, base_id, prestige, blesses_recipe_id) values (?,?,?,?,?,?,?,?)")) {
            for (ContentBatch.ProductDef d : batch.products()) {
                set(ps, d.id(), v, ordinal++, d.name(), d.tier(), d.prestige() > 0 ? d.baseId() : null, d.prestige(),
                        d.blessesRecipeId() > 0 ? d.blessesRecipeId() : null);
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
                "insert into building (id, content_version, ordinal, kind, x, y) values (?,?,?,?,?,?)")) {
            for (ContentBatch.BuildingDef d : batch.buildings()) {
                set(ps, d.id(), v, ordinal++, d.kind().name(), d.x(), d.y());
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

    /** A product row's tier; rows saved before v8 have none, and get it from the "-N" their name ends with. */
    private static int tierOf(ResultSet rs) throws SQLException {
        int tier = rs.getInt("tier");
        if (!rs.wasNull()) {
            return tier;
        }
        Matcher legacy = LEGACY_TIER.matcher(rs.getString("name"));
        return legacy.matches() ? Integer.parseInt(legacy.group(1)) : Product.NO_TIER;
    }

    /** How generated product names end: "skill1{0}-3" before tiers were stored, "skill1-3" now. */
    private static final Pattern LEGACY_TIER = Pattern.compile(".*-(\\d+)");

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
        List<List<ContentBatch.BuildingDef>> buildings = buckets(version);

        all("select * from skill order by content_version, ordinal", rs ->
                skills.get(rs.getInt("content_version") - 1).add(new ContentBatch.SkillDef(rs.getInt("id"), rs.getString("name"),
                        SkillType.valueOf(rs.getString("type")), rs.getInt("color_index"), rs.getString("symbol").charAt(0))));
        all("select * from product order by content_version, ordinal", rs ->
                products.get(rs.getInt("content_version") - 1).add(new ContentBatch.ProductDef(rs.getInt("id"), rs.getString("name"),
                        tierOf(rs), rs.getInt("base_id"), rs.getInt("prestige"), rs.getInt("blesses_recipe_id"))));
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

        all("select * from building order by content_version, ordinal", rs ->
                buildings.get(rs.getInt("content_version") - 1).add(new ContentBatch.BuildingDef(rs.getInt("id"),
                        Building.Kind.valueOf(rs.getString("kind")), rs.getInt("x"), rs.getInt("y"))));

        Map<Integer, ContentBatch.Origin> origins = new HashMap<>();
        all("select * from content_batch", rs -> origins.put(rs.getInt("content_version"),
                new ContentBatch.Origin(rs.getString("created_by"), rs.getLong("created_at"))));

        List<ContentBatch> batches = new ArrayList<>();
        for (int i = 0; i < version; i++) {
            batches.add(new ContentBatch(i + 1, skills.get(i), products.get(i), productLines.get(i), recipes.get(i), nodes.get(i),
                    monsters.get(i), drops.get(i), buildings.get(i), origins.get(i + 1)));
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

    /** A nullable product id column, or null if it's empty or names nothing in this world. */
    private Product product(ResultSet rs, String column) throws SQLException {
        int id = rs.getInt(column);
        return rs.wasNull() ? null : world.content.product(id);
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
                int hp = rs.getInt("hp");
                if (!rs.wasNull()) {
                    character.savedHp = hp;
                }
                character.weapon = product(rs, "weapon_product_id");
                character.armor = product(rs, "armor_product_id");
                character.metMerchant = rs.getInt("met_merchant") != 0;
                int blessing = rs.getInt("blessing_recipe_id");
                if (!rs.wasNull()) {
                    character.blessing = world.content.recipe(blessing);
                    character.blessingLeft = rs.getInt("blessing_left");
                    character.blessingStrength = Math.max(1, rs.getInt("blessing_strength"));
                }
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
        query("select * from character_prestige where character_id = ?", id, rs -> {
            Skill skill = content.skill(rs.getInt("skill_id"));
            character.prestige.put(skill, rs.getInt("prestige"));
            character.bankedXp.put(skill, rs.getLong("banked_xp"));
        });
        query("select * from inventory where character_id = ? order by ordinal", id, rs ->
                character.inventory.put(content.product(rs.getInt("product_id")), rs.getInt("count")));
        query("select * from bank_item where character_id = ? order by ordinal", id, rs ->
                character.bank.put(content.product(rs.getInt("product_id")), rs.getInt("count")));
        // one a skill (older saves could have several: the first stays)
        query("select * from goal where character_id = ? order by ordinal", id, rs -> {
            Skill skill = content.skill(rs.getInt("skill_id"));
            if (character.goals.stream().noneMatch(g -> g.skill().equals(skill))) {
                character.goals.add(new Goal(skill, rs.getInt("level"), content.product(rs.getInt("product_id"))));
            }
        });
        query("select * from character_tried_node where character_id = ?", id, rs ->
                character.triedNodes.add(rs.getInt("node_id")));
        query("select * from character_stat where character_id = ?", id, rs -> {
            int killed = Stat.killsOf(rs.getString("stat"));
            if (killed >= 0) {
                character.kills.put(killed, rs.getLong("value"));
                return;
            }
            try {
                character.stats.put(Stat.valueOf(rs.getString("stat")), rs.getLong("value"));
            } catch (IllegalArgumentException retired) {
                // a stat this version doesn't know: skip it
            }
        });
        return character;
    }

    public GameCharacter createCharacter(int accountId, String name) throws SQLException {
        int[] id = new int[1];
        transaction(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "insert into game_character (account_id, name, x, y, running) values (?, ?, 0, 0, 1)")) {
                set(ps, accountId, name);
                ps.executeUpdate();
            }
            id[0] = lastInsertId();
        });
        GameCharacter character = new GameCharacter(id[0], name);
        // new characters run (they can walk instead in the settings)
        character.running = true;
        return character;
    }

    @Override
    public void saveCharacter(GameCharacter character) throws SQLException {
        transaction(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "update game_character set x = ?, y = ?, last_action_x = ?, last_action_y = ?, facing = ?, color_index = ?, running = ?, auto_investigate = ?, weapon_product_id = ?, armor_product_id = ?, hp = ?, blessing_recipe_id = ?, blessing_left = ?, blessing_strength = ?, met_merchant = ? where id = ?")) {
                Position last = character.lastActionAt;
                set(ps, character.x, character.y, last == null ? null : last.x(), last == null ? null : last.y(),
                        character.facing.name(), character.colorIndex, character.running ? 1 : 0,
                        character.autoInvestigate ? 1 : 0, character.weapon == null ? null : character.weapon.id,
                        character.armor == null ? null : character.armor.id,
                        character.currentHp(), character.blessing == null ? null : character.blessing.id,
                        character.blessingLeft, Math.max(1, character.blessingStrength), character.metMerchant ? 1 : 0, character.id);
                ps.executeUpdate();
            }
            for (String table : List.of("character_skill_xp", "character_prestige", "inventory", "bank_item", "goal", "character_tried_node", "character_stat")) {
                try (PreparedStatement ps = connection.prepareStatement("delete from " + table + " where character_id = ?")) {
                    ps.setInt(1, character.id);
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = connection.prepareStatement("insert into character_prestige values (?, ?, ?, ?)")) {
                for (Map.Entry<Skill, Integer> e : character.prestige.entrySet()) {
                    set(ps, character.id, e.getKey().id, e.getValue(), character.bankedXp.getOrDefault(e.getKey(), 0L));
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
            try (PreparedStatement ps = connection.prepareStatement("insert into bank_item values (?, ?, ?, ?)")) {
                int ordinal = 0;
                for (Map.Entry<Product, Integer> e : character.bank.entrySet()) {
                    if (e.getValue() > 0) {
                        set(ps, character.id, ordinal++, e.getKey().id, e.getValue());
                        ps.executeUpdate();
                    }
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
            try (PreparedStatement ps = connection.prepareStatement("insert into character_stat values (?, ?, ?)")) {
                for (Map.Entry<String, Long> stat : character.statsByName().entrySet()) {
                    set(ps, character.id, stat.getKey(), stat.getValue());
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
