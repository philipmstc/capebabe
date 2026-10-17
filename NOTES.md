# Helpful analogy
## Primary skill = Woodcutting, Mining. 
- These have 1 product line -- logs, and ores. Of several tiers.

## Secondary Skill - Smithing*
- If confined only to creating Bars and Arrowheads from various ores / bars respectively, smithing is a single product line secondary skill.

## Tertiary skill - Fletching
- combines outputs from the primary product line from woodcutting and secondary (derived) product line of smithing, and, a neutral product "Feathers" that are not a skill based resource to produce arrows.


# TODOS

## Shit to do first
1. **(done for skills + nodes, product lines still to come -- see "World growth" below)** Make commands `1`, `2`, `3` be derived from user actions with exponential backoff. Examples of actions: Moving (with lower probability.), "producing" (`q`), on level up
2. **(done)** Map "bigger" and centered on player character. Might need to make the origin look visibly different so that empty map navigation isn't disorienting. "Bigger" meaning just that you can move outside the current 8x5 window and explore more, the visible section can stay same for now.
3. **(done)** Map navigable with arrow key with background highlighted to show a preview of the actions available. This arrow key navigation is separate from player movement and serves as a way to "hover over" the nodes. Should only be possible in Map selection. 
4. **(partly done -- skills + product lines browsable, recipes not yet)** Skill menu navigable with submenu showing recipe list :)

## Shit to do some other time
- Inventory navigable and prettier (low prio) -- `InventoryMenu` exists but can't be focused yet
- Monsters and a fighting system of some kind involving the items
- "neutral" items that are not tied to any skill or recipe -- probably dropped by monsters, in addition to maybe other sources (think Feathers in runescape)
- Bank a la runescape

# backlog/scratch pad

## Monsters

- first encountered monster is monster0 
- until discovered, has a 1/2 drop rate for monster0-drop0
- only achievable 1 time _ever_
- then a 1/4 drop rate for monster0-drop1, ..., 1/8 for monster0-drop2 etc

## Rapid fire (2026-10-16 night)
- [x] Logging -> board log via a listener: `GameLog.say(...)` from anywhere, `MessageLog` subscribes in `Main`. All `board.log.add` calls and `Player`'s `System.out` moved over. (`SkillFactory`'s weight debug prints left on System.out on purpose, they'd flood the log.)
- [x] Map tab not focused: preview shows the player's current tile ("Here (x,y)")
- [x] "Goals" menu: `q` on a node you're too low level for marks it (non-_BRIGHT color), then `g` adds "reach SKILL Lv. XX to make PRODUCT". Goals tab shows `[ ]`/`[x]`, log says "Goal complete" on the level up.
- [x] Hover + `Enter` walks the player to the cursor, one tile per tick (horizontal then vertical). `Pathfinding.orthogonal` is the algorithm, `Walker` steps anything implementing `Walker.Mobile` -- monsters can reuse both. A wasd step replaces the walk.
- [x] Spot check of Recipe validation / resource usage -- see below
- [x] Off-screen "home" arrow on the map border: points at the last tile where something was successfully made (yellow), or the origin (white) if nothing yet. `BoardView.drawHomeIndicator`, tracked in `Player.lastActionAt`.

### Recipe spot check
- Fixed: `create` didn't check `levelNeeded` at all (only components). Added `Recipe.levelMet`, and `create` now says why it failed ("Need X Lv. 20 (you're Lv. 1)") instead of the generic "I can't do that!".
- Heads up: `ProductLineFactory` tiers are currently `{0}` only, so nothing is ever locked in-game and Goals can't be tried out. Put the 5 tiers back (or add one locked tier) to play with it.
- Not changed: `attemptToUseAll` leaves products at `x 0` in the inventory. Fine if intentional ("you've had this before"), otherwise remove the entry at 0.
- Not changed: `Recipe`'s constructor does `comps.put(co, 1)`, so passing the same product twice still needs only 1. Use `merge(co, 1, Integer::sum)` once multi-quantity recipes are a thing.
- Not changed (pre-existing): `gainExperience` levels up at most once per call, and `XP_PER_LEVEL[100]` will throw once someone hits 100.

## Morning (2026-10-17)
- [x] Progress bar (the box under the map, used to be the `frame` clock): empty until `q` starts an action, then fills green over `Board.Action.durationTicks()` (default `DEFAULT_DURATION_TICKS` = 2 ticks of 600ms) with the product name on top. The product is made when it fills. Locked / missing components fail immediately (`Action.canPerform`) with the reason. Moving (wasd or walking) interrupts it; `q` while busy stops it
- [x] XP popup: "+200xp" in the skill's color floats up from the tile (`Popup`, drawn in `BoardView.drawPopups`).
- [x] Actions repeat: after each completion the bar restarts if `canPerform` still holds (stops with "Can't make any more X." when components run out). `q` while busy stops it, moving interrupts it. Actions with no components (primaries) repeat forever until stopped.
- [x] Level up popup: " skill Lv. N! " in black on the skill's color, follows the player (`Popup.following`), lives `Player.LEVEL_UP_POPUP_TICKS` (45, ~2.7s). Log says the new level, "Unlocked: ..." for recipes newly within reach, and completed goals.
- [x] Leveling fix: one xp drop can now raise several levels; max level is 100 and xp past that never levels again (it still accumulates). `getExperienceToNextLevel` no longer goes out of bounds at 100.
- [x] XP tracker, top right of the header row: tracked skill (most recent xp drop), level, a progress bar to the next level (green fill, "662/1.4k", "MAX" at 100) and xp/h. `XpTracker` (data, injectable clock) + `XpTrackerView` (drawing).
  - xp/h = xp since the first drop of the session / *action time* since that drop (the first drop only starts the clock). Only time with an action running counts (`XpTracker.setActive`, driven by `ActionStarted`/`ActionStopped`), so the rate holds steady while you're idle and only moves while you're working. Shows `--/h` until the second drop, resets when the tracked skill changes or after 5 minutes without xp (`IDLE_RESET_MILLIS`). Tests: `XpTrackerTest`.
- [ ] TODO (think about): ways the game can interrupt a long repeating action to break up the loop in a fun way -- random events, a monster wandering up, a "rare find" mid-batch, a node depleting, an inventory-full check, fatigue...

## Persistence / multiplayer (2026-10-17)
- [x] Save game state between sessions: SQLite, server-side only. Decisions (multiple seeded worlds, atomic content batches, node capacity, 600ms server tick) and the build order are in `MULTIPLAYER.md`.
- [x] Step 0 of `MULTIPLAYER.md`: 600ms logic ticks split from 60ms render frames (`GameClock`).
  - Ticks: walking (1 tile/tick), wasd steps, action progress, and intents. `q` (start/stop) and wasd are queued and applied on the next tick, like a server would. Actions default to 2 ticks (1.2s, same as before).
  - Frames: drawing, popups (lifetimes are in frames), input. The progress bar fills smoothly using `clock.partialTick()`.
  - wasd queues at most 2 steps (`Walker.MAX_QUEUED_STEPS`) so holding a key doesn't overshoot; a wasd step replaces a hover-walk in progress. Starting an action stops walking.
  - Main loop handles every key pressed during a frame now (used to be one per frame).
  - Falls behind by >5 ticks (debugger, laptop sleep) -> skips ahead instead of fast-forwarding.
- [x] Step 1: stable IDs + world as data + seeded world RNG.
  - Every skill / product line / product / recipe / node has an `id` (unique within a world; equality is by id).
  - `ContentBatch` = one generation step as plain data (defs with ids). `WorldContent` is built only by applying batches in version order; `history()` is the list of batches.
  - `WorldGenerator.newSkills / newProductLines / newNode` generate on a copy with `Random` seeded from (world seed, step number) and return the diff as the next batch -- nothing changes until it's applied. Same seed + same steps = same world (tested).
  - Skill color index and symbol are part of the skill now (assigned at generation), so `SkillStyle` is just a palette.
  - `Board.Action` / `RecipeAction` are gone: map nodes are data (`Node`: recipe, capacity, charges, respawn). "Tried while locked" lives on the player (`triedNodes`).
  - `/3 [capacity] [charges] [respawn ticks]` -- capacity/charges are stored but not enforced until step 5.
  - JUnit tests live in `src/test` now (`gradlew test`).
- [x] Steps 2+3: intents, events, in-process `GameServer`. Single player is now "a server in the same JVM".
  - `GameServer` (one per world) owns the `World` and the online `GameCharacter`s, runs its own 600ms tick thread. Each tick: `Tick` event, intents, walkers, actions.
  - Clients talk through `GameConnection` (`send(Intent)` / `poll()` events). `LocalConnection` wraps a server `Session` (two concurrent queues), so there are no locks anywhere.
  - `Intent`s: Join, Leave, Move, WalkTo, Perform (start/stop), AddGoal, Admin (the 1/2/3 debug commands -- generation happens on the server and every client gets the same `ContentAdded` batch).
  - `Event`s: Welcome (all content batches + your snapshot + who's online), Tick, ContentAdded, EntityEntered/Moved/Left, ActionStarted/Stopped, XpGained, LevelUp, InventoryChanged, GoalAdded, NodeTried, HomeChanged, Message.
  - `GameCharacter` (server) has the game rules that used to be in `Player`. `Player` is now the client's mirror: same methods the UI used, but it only changes by applying events. Popups, xp tracker, level-up/unlock/goal-complete log lines are all derived client-side from events.
  - Progress bar is computed from `ActionStarted(startTick, duration)` + the latest `Tick` + local time, so the server doesn't send anything per frame. Repeats wrap without new messages.
  - Other players on the map: magenta `*` (drawn from `EntityEntered/Moved`).
  - `Main` starts a `GameServer`, connects with a `LocalConnection` and joins with your OS user name. World seed is random per run until persistence (step 4).
  - Tests: `GameServerTest` (two clients, content sync, movement, actions/xp/items, interrupt, goals, bad admin commands).
- [x] Step 4: `GameStore` (SQLite). Saves go to `~/.capebabe/capebabe.db` by default.
  - Run options: `--name <name>` (default: OS user name), `--world <id>` (default 1), `--db <file>`.
  - Schema versioned with `PRAGMA user_version` (`GameStore.MIGRATIONS`; add a new string to the list for v2). Tables as in `MULTIPLAYER.md` (`game_character` instead of `character`).
  - World seed is stored, so a world grows the same way every time. Content is stored per batch (rows keep their batch version + order) and reloaded by replaying the batches -- reload gives identical `ContentBatch`es (tested).
  - A new batch is written in one transaction *before* it's applied/broadcast; if the write fails, nothing changes and the admin gets an error. The world's `content_version` is checked in the same transaction.
  - Characters: loaded/created on Join (account by name, one character per account per world), saved on leave, on level up, every 50 ticks (30s) and on shutdown (`Main` adds a shutdown hook -> `GameServer.stop()` -> final save). Same name can't join twice.
  - Not saved (by design): action in progress, walk path, popups, xp/h.
  - The old commented-out Chinook DB code in `Main` is removed (it's in git history).
  - sqlite-jdbc prints an SLF4J "no logger" warning on startup -- harmless; add `org.slf4j:slf4j-nop` if it bugs you.
  - Tests: `GameStoreTest` (migrations, seed, batch round trip, out-of-order batch writes nothing, character round trip, full server restart keeps world + progress).
- [x] Step 5: node capacity / depletion. `/3 [capacity] [charges] [respawn ticks]` (0 = unlimited; respawn defaults to 10 ticks = 6s).
  - `NodeStates` (server, not saved) tracks users, charges left and respawn timers per node; changes go out as `NodeChanged`, and `Welcome` carries anything not in its default state.
  - Full node: "Someone else is using that (1/1)." Empty node: "It's empty -- back in about Ns." Running out stops *everyone* using it ("The X ran out."), which is the first of the "fun interruptions".
  - Client: empty nodes draw grey; Map preview shows "3/10 left" / "empty -- respawning" and "in use: 1/2".
  - Tests: `NodeStatesTest`.
- [x] Step 6: network transport. How to run (jar from `gradlew shadowJar`, `build/libs/capebabe-1.0-SNAPSHOT-all.jar`):
  - `java -jar capebabe.jar` -- single player, as before.
  - `java -jar capebabe.jar --host [--port 43594] [--admin]` -- play locally *and* let friends connect.
  - `java -jar capebabe.jar --server [--port 43594] [--admin] [--world 2] [--db file]` -- headless world, no window.
  - `java -jar capebabe.jar --connect host[:port] --name bob` -- join someone else's world (nothing is saved on the client).
  - Wire format: one JSON object per line (`Wire`, Gson 2.10.1 -- new dependency, was already in the Gradle cache). Only `Intent`/`Event` record types decode, so clients can't make the server build arbitrary objects (which is why it isn't Java serialization). Client lines are capped at 64KB.
  - `NetworkServer` bridges each TCP client to a `GameServer.Session` (one reader + one writer thread per client); `SocketConnection` is the client side. The game itself didn't change -- it only ever saw sessions.
  - Admin (1/2/3) is per session: local player always, network players only with `--admin`.
  - Names must be 1-20 of `A-Z a-z 0-9 _ -` (OS user name is cleaned up for the default). Still no passwords -- anyone can join as anyone; fine among friends, not for the public internet.
  - Default port 43594 (RuneScape's, for luck). Open it in the firewall / forward it on the router to play over the internet.
  - Tests: `NetworkTest` (wire round trips incl. a whole world, unknown types rejected, long lines refused, two players over real TCP, admin denied).
- [x] Client-side prediction for your own movement (the "rollback" part of the timing decision).
  - wasd moves your `*` immediately; `Player` keeps the predicted steps (max 2, same cap as the server) and drops each one as the server's `EntityMoved` confirms it. If the server says something else, or sends `StepsCleared` (it threw your queued steps away: you started an action, hover-walked, ...), the client snaps to the server's position.
  - `Player.x/y` = where you're drawn (predicted), `Player.serverPosition()` = where the server has you.
  - Server tick order is now: joins/leaves + movement intents, walkers, actions, then Perform/AddGoal/Admin intents. So "d then q" in one tick acts on the tile you stepped to instead of cancelling the step.
- [x] Area-of-interest filtering: you only get `EntityEntered/Moved/Left` for characters within `GameServer.VIEW_RANGE` (16 tiles either axis; the map shows 5x3). Each `GameCharacter` tracks who it has been told about (`seen`). Node and content events still go to the whole world.
- [x] Hovering over another player (or standing on their tile) shows `* name  total Lv. N` in the Map preview, above any node info. Total level = sum of skill levels, untrained skills count as 1 (RuneScape style). `EntityInfo` carries `levelsGained` (levels above 1) and the client adds the world's skill count, so it stays right when new skills appear; level ups send `EntityUpdated` to whoever can see you. Other players' `*` now draw as part of the tile, so the hover highlight doesn't hide them.
- [x] World growth from play (TODO #1): worlds start empty; `Discovery` (server) rolls on every step, every item made and every level up.
  - New skill chance = base x `0.6^(skills in the world)`; base 3% per step, 8% per item made, 50% per level up. The first skill can only come from walking.
  - New node chance = base x `0.5^(nodes within 8 tiles of you)`; base 10% per step, 5% per item, 30% per level up. Backing off on *nearby* nodes (not the world total) means crowded spots stop spawning but exploring keeps finding things.
  - Nodes spawn 2-5 tiles from you on an empty tile, with capacity 1-3, 5-20 charges and a 12-36s respawn. Recipes you can already do are 4x likelier than locked ones (`Discovery.UNLOCKED_WEIGHT`), so new players still see the occasional goal-worthy locked node.
  - Every discovery is its own content batch (saved + broadcast like everything else). The finder gets "You discovered a new skill: skill3 (secondary)!" / "A skill1 node (skill1{0}-0) appeared nearby."; everyone else hears about new skills.
  - Simulated over 10 seeds walking 400 tiles: first skill after 6-76 steps (avg ~28), first node a few to ~50 steps after that, end state 3-5 skills and ~0.7 nodes per 10 tiles walked.
  - All the numbers live at the top of `Discovery` (`Trigger` enum + constants). Debug `1`/`2`/`3` still work for admins.
  - Tests: `DiscoveryTest`. Other tests use `server.setDiscovery(Discovery.off())` so their worlds stay fixed.
- [ ] Next for world growth: new product lines from play (same pattern -- a third chance in `Trigger`, backing off by product lines per skill), and maybe skill discovery tied to *what* you do (e.g. making lots of a product makes a secondary skill that uses it likelier).
- [ ] Not started (bigger, needs design): login (passwords / tokens on `account`), trading, fighting, monsters (server-side, can reuse `Walker` + `Pathfinding` + the entity events).

# Next steps / ideas

## Current controls
- `Left`/`Right` switch tabs. `Down`/`Enter` focuses the current tab, `Esc` backs out (and quits from the top level).
- Map: unfocused, the right side previews the tile you're standing on. Focused, arrows move a hover cursor and `Enter` walks there. Other players on the tile are listed with their total level.
- Skills: `Up`/`Down` pick a skill (product lines preview on the right), `Right`/`Enter` moves into the product lines, `Left`/`Esc` back. `Up` from the top skill leaves the menu.
- wasd/q/g/`/` work everywhere, even while a menu is focused. `g` = add goal for the locked node you're standing on (after trying it with `q`).
- Goals tab is view-only for now.
- `HELP` (`k`) only lists `ConsoleAction`s, so none of the above is discoverable in-game except the Map tab hint. Might want a "controls" section in help.

## Map
- 11x7 visible, centered on the player, world is unbounded. Window is 100x28 (`Main.TERMINAL_SIZE`); all layout positions derive from `BOARD_WIDTH`/`BOARD_HEIGHT`.
- Orientation: `o` at the origin, player's coords in the map's top border, and an arrow on the border pointing to the last successful action tile (else origin) when it's off-screen.
- Home arrow: only cardinal arrows (which edge it's on shows the diagonal). It can overwrite a character of the coords label when home is up-left -- move the label to the bottom border if it bugs you. Could show both origin and last-action arrows at once, or the distance next to the arrow.
  - [ ] ability for player to drop pins that show as separate arrows :) 
- Hover cursor is clamped to the visible window. To scout further, let the camera follow the cursor while hovering and snap back on Esc.
- Walking could auto-`q` on arrival, or `Shift+Enter` = walk and perform.
- Walk speed is one tile per 600ms tick. Running (2 tiles/tick) would be a `Walker` option.
- Own movement is predicted (see Persistence / multiplayer above); `q` still waits for the tick (up to 600ms) because the server decides whether the action can start.
- Pathfinding ignores obstacles (there aren't any yet). Swap `Pathfinding.orthogonal` for BFS/A* once tiles can block.
- Map nodes are data (`Node`); the only kind is "recipe". Other kinds (bank, monster spawn, ...) would add a `kind` to `Node`/`NodeDef` and a branch in `GameCharacter.performOrStop`. Preview text is `MapMenu.describe(node)`; could also show missing components.
- Placing a node on a tile that already has one replaces it (the old one stays in the content history). Fine for the debug command; real removal would need a "removed nodes" list in `ContentBatch`.
- Symbol color semantics: bright = normal, dim = tried and too low level. Could add more states (e.g. never seen, depleted) once nodes get richer.

## Skills
- Next for #4: drill one more level into a product line to list each tier's recipe in full (product, components, xp), basically the `l` command's output but browsable. Would probably be a third column, or replace the skill list column.
- Product line names are derived by stripping `-tier` off the tier 0 product name. `ProductLine` should probably get a real `name` field.
- The "from" line could be colored with the source skill's color/symbol, and the product line view could show how many of each tier's product are in the inventory.
- Skill list column is 32 wide (rows cut with `~`), product lines at `SkillsMenu.DETAIL_POS_X` get ~42 cols at 100 wide. If menus grow, derive widths from `screen.getTerminalSize()`.

## Actions / progress
- Durations are in game ticks (`GameClock.TICK_MILLIS` = 600ms), from `Node.durationTicks()`. Per-tier durations would be a `durationTicks` field on `Recipe` / `RecipeDef`.
- Feedback for `q` waits for the next tick (up to 600ms). Could show a "queued" hint on the bar meanwhile.
- Skills list shows `xp/14921909` at Lv. 100; could show "MAX" instead (the XP tracker already does).
- XP tracker ideas: keep per-skill sessions instead of resetting on switch, show "time to level" (remaining xp / rate), let `/` command reset it. If a lot more tabs get added the header row runs out of room -- the tracker could move to its own row.
- Popups are generic (`new Popup(position, text, color)` on `player.popups`) and purely client-side -- the client creates them from events (`XpGained`, `LevelUp`). Item drops etc. would do the same.

## Goals
- Make the tab focusable to remove goals / sort them; maybe auto-remove (or archive) completed ones.
- Goals could hook into TODO #1 (spawning skills/nodes from player actions) -- e.g. completing a goal is a trigger.
- TODO #1 can hook into the server: `GameCharacter.gainExperience` / `perform` are where level ups and production happen; a trigger there calls `WorldGenerator` and `GameServer.applyContent`. Clients already handle `ContentAdded`.

## Inventory
- Could reuse the Skills browse pattern: "made by" / "used in" for each item.

## Code structure
- New top-level menu = subclass `Menu`, add it to the `menus` list in `Main`. Headers lay themselves out from the titles.
- Menus only get keys while focused. Keys they return `IGNORED` for fall through to the global wasd/q// handling.
- Map drawing is in `BoardView`, skill colors/symbols in `SkillStyle`.
- Server side: `GameServer`, `GameCharacter`, `ActionProgress`, `Walker` + `Pathfinding`, `WorldGenerator` (+ `SkillFactory`, `ProductLineFactory`). Client side: `Player` (mirror), `Board` (content view + log), `BoardView`, menus, popups, `XpTracker`. Shared: content classes, `ContentBatch`, `WorldContent`, `Intent`, `Event`, `Levels`.
- Rule of thumb: game rules go on the server and announce themselves with an `Event`; the client only reacts. Client-only messages (help, list, echo) use `GameLog.say` directly; server messages arrive as `Event.Message`.
- Layout constants still live on `Main` (`LIST_ITEM_POS_X`, `LIST_ROWS`, ...). Could move them to a small `Layout` class once there's more than one area that cares.
