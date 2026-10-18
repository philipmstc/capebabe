# Database schema

Generated from fresh databases made by the current code (accounts v1, worlds v5). The source of truth is the
`MIGRATIONS` lists in `persistence/AccountStore.java` and `persistence/WorldStore.java`: each entry is one
schema version, applied in order and tracked with `PRAGMA user_version`, so old files upgrade in place.

## Where the files are

Everything lives under the data folder, `~/.capebabe` by default (on this machine `C:\Users\phili\.capebabe`),
or wherever `--data <dir>` points. It doesn't depend on where you run the jar from (so not `build/libs`).

```
~/.capebabe/
  accounts.db             one global account database, shared by every world
  worlds/world-1.db       one database per world (--world <id>)
  capebabe.db             old single-file format from before the split -- no longer read, safe to delete
```

Only the server side writes these (single player, `--host`, `--server`); `--connect` clients don't keep any saves.

## Looking inside

- DBeaver: New connection -> SQLite -> point it at one of the files above. Close the game first, or open it read-only.
- sqlite3 CLI: `sqlite3 ~/.capebabe/worlds/world-1.db ".schema"`, then `pragma user_version;` for the version.

## How it hangs together

- **Content** (`skill`, `product`, `product_line`, `recipe`, `recipe_component`, `node`, `monster_type`,
  `monster_drop`): every row has the `content_version` of the `ContentBatch` that added it and its `ordinal`
  within that batch. Loading replays the batches in order; ids are unique across all content in a world.
  `world_meta.content_version` is the latest batch, checked and bumped in the same transaction as each new batch.
- **Characters** (`game_character` + `character_skill_xp`, `inventory`, `goal`, `character_tried_node`): one
  character per account per world (`account_id` points into `accounts.db`; no foreign key across files). Levels
  aren't stored, only xp. Saved whole (delete + reinsert the child rows) in one transaction.
- **One-of-a-kind drops**: a `monster_drop` row means that drop has dropped, for `found_by`. It's written in the
  same transaction as the finder's `inventory` row.
- **Not stored**: hp, monsters out in the world, node charges, action progress, walk paths (all come back fresh).

## Tables

```sql
-- accounts.db (schema version 1)
CREATE TABLE account (id integer primary key autoincrement, name text not null unique,
                      created_at text not null default current_timestamp);

-- worlds/world-<id>.db (schema version 5)
CREATE TABLE world_meta (id integer primary key check (id = 1), name text not null, seed integer not null,
                         content_version integer not null default 0);
CREATE TABLE skill (id integer primary key, content_version integer not null, ordinal integer not null,
                    name text not null, type text not null, color_index integer not null, symbol text not null);
CREATE TABLE product (id integer primary key, content_version integer not null, ordinal integer not null,
                      name text not null);
CREATE TABLE product_line (id integer primary key, content_version integer not null, ordinal integer not null,
                           skill_id integer not null references skill(id));
CREATE TABLE recipe (id integer primary key, content_version integer not null, ordinal integer not null,
                     product_line_id integer not null references product_line(id),
                     product_id integer not null references product(id),
                     level_needed integer not null, exp_gained integer not null);
CREATE TABLE recipe_component (recipe_id integer not null references recipe(id), ordinal integer not null,
                               product_id integer not null references product(id), count integer not null,
                               primary key (recipe_id, product_id));
CREATE TABLE node (id integer primary key, content_version integer not null, ordinal integer not null,
                   x integer not null, y integer not null, recipe_id integer not null references recipe(id),
                   capacity integer not null, max_charges integer not null, respawn_ticks integer not null);
CREATE TABLE game_character (id integer primary key autoincrement, account_id integer not null unique,
                             name text not null, x integer not null, y integer not null,
                             last_action_x integer, last_action_y integer, facing text not null default 'DOWN', color_index integer not null default 0, running integer not null default 0);
CREATE TABLE character_skill_xp (character_id integer not null references game_character(id),
                                 skill_id integer not null references skill(id), xp integer not null,
                                 primary key (character_id, skill_id));
CREATE TABLE inventory (character_id integer not null references game_character(id), ordinal integer not null,
                        product_id integer not null references product(id), count integer not null,
                        primary key (character_id, product_id));
CREATE TABLE goal (character_id integer not null references game_character(id), ordinal integer not null,
                   skill_id integer not null, level integer not null, product_id integer not null,
                   primary key (character_id, skill_id, level, product_id));
CREATE TABLE character_tried_node (character_id integer not null references game_character(id),
                                   node_id integer not null references node(id),
                                   primary key (character_id, node_id));
CREATE TABLE monster_type (id integer primary key, content_version integer not null, ordinal integer not null,
                           name text not null, level integer not null, max_hp integer not null,
                           max_hit integer not null, color_index integer not null, symbol text not null, roam_radius integer not null default 3, move_ticks integer not null default 2);
CREATE TABLE monster_drop (monster_id integer not null references monster_type(id),
                           product_id integer not null references product(id),
                           content_version integer not null, ordinal integer not null, one_in integer not null, found_by text,
                           primary key (monster_id, product_id));
```

When the schema changes, regenerate the Tables section from a fresh data folder (run the game once with
`--data <empty dir>`, or open both files in DBeaver and copy the DDL) so this file doesn't drift.
