# Client / server split (sketch)

Goal: shape the code so single player is just "a server running inside the client", and real multiplayer is swapping that in-process connection for a network one. Nothing here is built yet.

## Decisions (2026-10-17)

- **Worlds**: multiple shared worlds, each generated from its own seed, each holding many players.
- **Content growth**: new skills / product lines / recipes / nodes reach every player in a world **atomically** -- all of it or none of it.
- **Nodes**: may run out *temporarily* later, and have a configurable number of concurrent users.
- **Players**: see each other on the map. Trade and fight eventually.
- **Accounts**: just a name for now, real login later.
- **Timing**: 600ms server tick. The client renders at ~60ms and smooths between ticks (prediction + correction for your own moves).

## Principle

**The server is the only thing that changes game state.** The client sends *intents* ("I want to move right", "perform the action here") and draws whatever the server says happened. The client never decides whether a recipe succeeds, how much xp it gave, or where a player is.

```
 ┌──────────── client ─────────────┐            ┌──────────── server (one per world) ─────┐
 │ Lanterna input  ──► Intent ──────┼──────────► │ intent queue                            │
 │                                  │            │   │                                     │
 │ UI state (tabs, hover, /cmd)     │            │   ▼  tick() every 600ms                 │
 │ ClientState (mirror of world     │            │ World content (versioned)               │
 │   content + nearby entities)     │ ◄──────────┼─ Characters (pos, xp, inv, goals, ...)  │
 │ Prediction for own movement      │   Events   │ Node occupancy / depletion              │
 │ Popups, XpTracker, MessageLog    │  (tick #)  │ Monsters (Walker + Pathfinding)         │
 │ BoardView / Menus render @60ms   │            │ GameStore (SQLite)                      │
 └──────────────────────────────────┘            └─────────────────────────────────────────┘
```

One process can host several worlds (one `GameServer` + tick loop each), or one process per world. Either way a world is the unit of state, persistence and broadcast.

## What lives where

| Server (authoritative) | Client (presentation) |
|---|---|
| World content: skills, product lines, recipes, products, skill color/symbol, nodes | Selected tab, menu focus, hover cursor, `/command` typing |
| Node state: current users, charges left, respawn timer | Popups (spawned from `XpGained` / `LevelUp` events) |
| Per character: position, xp per skill, inventory, goals, tried-locked-nodes | `XpTracker` / xp per hour (from `XpGained` events + local clock) |
| Current action, walk path, interrupt rules | Message log (fed by `Message` events) |
| Monsters, combat, drops, trades | Progress bar animation (from `ActionStarted` tick + duration) |
| Tick loop, persistence, world generation, admin commands | Rendering, predicted own position |

## Worlds, seeds and atomic content

- A world has `id`, `seed`, `content_version`. Generation uses a **world-owned RNG**, never `Math.random()`.
- To keep growth reproducible without saving RNG state, generation step *N* seeds its own RNG from `hash(worldSeed, N)`. Same seed + same sequence of growth triggers = same world. The generated content is still saved (the seed is provenance and handy for debugging, not the source of truth -- code changes would otherwise silently change old worlds).
- Growth (TODO #1 -- new skills from player actions) produces a **`ContentBatch`**: everything a single generation step creates (new skill + its product lines + recipes + products + any nodes), with server-assigned IDs and `version = content_version + 1`.
- Applying a batch, in one tick:
  1. Apply it to in-memory world content.
  2. Write it to SQLite in **one transaction** (bump `world.content_version` in the same transaction).
  3. Broadcast one `ContentAdded(version, batch)` event to every character in the world.
- Clients apply batches in version order. A client that sees a gap (e.g. reconnecting, or missed a message) asks for `ContentSince(version)` / a full snapshot. Nothing references content the client hasn't received yet, because the batch that introduces something is sent before any event that uses it.
- One player's actions grow everyone's world. Fun consequence to design around: whoever triggers a new skill could get credit ("discovered by ...").

## Nodes

- A node has `capacity` (max concurrent users, default e.g. 1 or unlimited) and optional depletion: `charges`, `respawnTicks`.
- Starting an action registers the character as a user of the node. A full node rejects with "Someone else is using that." A depleted node rejects with "It's empty, try again later."
- Depleting mid-loop stops everyone using it (`ActionStopped(reason)`) -- this is a natural fit for the "fun interruptions" TODO.
- `NodeChanged(position, state)` goes to everyone who can see it, so the dim/empty look updates for all.
- "Tried this locked node" is per character (`character_tried_node`), not on the node.

## Seeing other players

- The server only sends what's near you (area of interest = your view plus a margin): `EntityEntered(id, kind, name, position)`, `EntityMoved(id, position)`, `EntityLeft(id)`. The same messages work for monsters later.
- Trade and fight are server-side transactions involving two characters: both parties confirm, the server checks and applies both inventories in one tick and one DB transaction.

## Accounts and characters

- `account(id, name)` now; credentials get added to `account` later without touching game tables.
- A character belongs to one account and one world (different seeds mean different skills, so progress can't carry over): `character(id, account_id, world_id, name, x, y, ...)`.
- Until login exists, the server trusts the name it's given. Fine for friends, not for public.

## Timing: 600ms ticks, 60ms frames

- Everything that counts time on the server counts **ticks**. Actions default to 2 ticks; walking is 1 tile per tick (RuneScape walk speed), maybe 2 for running.
- Every event carries the server tick number. The client estimates the current server tick from the last received tick + elapsed local time.
- **Progress bar**: `ActionStarted(tick, durationTicks)` lets the client fill it smoothly at 60ms with no further messages.
- **Own movement**: pressing `d` moves your `*` immediately (prediction) and sends `Move`. When the server's `EntityMoved` for you arrives, it either matches (nothing to do) or doesn't (snap to the server's position = the "rollback"). Repeated key presses queue up on the server as a short walk path, one tile per tick.
- **Other players / monsters**: positions are whole tiles, so there's nothing to interpolate *between* cells in a character grid. Drawing them one tick behind keeps their movement steady instead of jittery. Smoothing mostly matters for bars, popups and timers.
- Done in single player (step 0): `GameClock` runs 600ms logic ticks and 60ms render frames, durations are in ticks, and player requests are queued until the next tick. Prediction isn't there yet -- with no server it would just be guessing at our own code.

## Messages

Client → server (`Intent`):
- `Join(accountName, worldId)`
- `Move(dx, dy)`, `WalkTo(position)`, `Perform`, `Stop`, `AddGoal`
- Later: `TradeRequest`, `TradeOffer`, `TradeConfirm`, `Attack`
- `Admin(command, args)` for the debug `1` / `2` / `3` commands (dev builds only)

Server → client (`Event`, each with `tick`):
- `Welcome(characterId, contentVersion, worldContent, yourCharacter, visibleNodes, visibleEntities)`
- `ContentAdded(version, batch)`
- `EntityEntered` / `EntityMoved` / `EntityLeft`
- `ActionStarted(label, startTick, durationTicks)`, `ActionStopped(reason)`
- `XpGained(skillId, amount, position)`, `LevelUp(skillId, level, unlockedRecipeIds)`
- `InventoryChanged(productId, count)`, `GoalAdded(goal)`, `GoalCompleted(goal)`
- `NodeChanged(position, state)`
- `Message(text)` -- what `GameLog.say` becomes

Events are addressed to one character, to everyone near a position, or to the whole world. Each tick the server drains them into each connection.

## How the code maps to this (as built)

- **Shared:** content classes (`Skill`, `ProductLine`, `Product`, `Recipe`, `Node`), `ContentBatch`, `WorldContent`, `Intent`, `Event`, `Levels`, `Wire` (JSON lines).
- **Server:** `GameServer` (tick loop, sessions, visibility), `GameCharacter` (rules), `ActionProgress`, `NodeStates`, `Walker` + `Pathfinding`, `WorldGenerator` (+ factories), `GameStore` (SQLite), `NetworkServer`.
- **Client:** `Player` (mirror of your character + prediction + nearby entities), `Board` (content view + log), `BoardView`, the `Menu`s, `Popup`, `XpTracker`, `ConsoleAction` (keys -> intents), `GameConnection` (`LocalConnection` / `SocketConnection`).
- `Main` wires it up: local game (default), `--host`, `--server`, `--connect`.

## Transport

Start with an in-process connection:

```java
interface GameConnection {
    void send(Intent intent);
    List<Event> poll();
}
```

`LocalConnection` calls straight into a `GameServer` in the same JVM; `SocketConnection` talks to a `NetworkServer` over TCP with one JSON object per line. Same interface, so the game doesn't know the difference. Each world's server runs one thread for its tick loop and takes intents through a queue, so game logic never needs locks.

## Persistence (SQLite, server-only)

- World content: `world(id, seed, content_version, name)`, and `skill`, `product_line`, `product`, `recipe`, `recipe_component`, `node(world_id, x, y, recipe_id, capacity, max_charges, respawn_ticks)` -- every content row carries `world_id` and the `content_version` that introduced it.
- Accounts / characters: `account`, `game_character`, `character_skill_xp`, `inventory`, `goal`, `character_tried_node`.
- Not saved: action progress, walk paths, popups, xp/h, hover. Node charges/respawn timers: probably not worth saving at first (nodes come back full after a restart).
- A `ContentBatch` is one transaction. Character state autosaves on an interval, on level up and on disconnect; a trade is one transaction.
- SQLite allows one writer at a time; with one writer thread per world that's fine. If many worlds share one file and it gets busy, one DB file per world is the easy fix, and Postgres is the bigger one.

## Suggested order

0. ~~**Split logic ticks from render frames**~~ (done) in the current single-player loop (600ms logic, 60ms render, durations in ticks). Small, independent, and you'll feel the new pacing right away.
1. ~~**IDs + world as data + world RNG**~~ (done): stable IDs, color/symbol on the skill, factories produce `ContentBatch`es from a seeded RNG.
2. ~~**Intents**~~ (done): key handling produces `Intent`s; an in-process `GameServer` applies them.
3. ~~**Events**~~ (done): replace `GameLog` and the direct Player → UI coupling (popups, xp tracker) with events; client keeps a mirror of world content.
4. ~~**`GameStore`**~~ (done) on the server: worlds, content batches, characters. Single-player saves land here.
5. ~~**Node capacity / depletion**~~ (done) (needs the server owning node state).
6. ~~**Network transport** + `Join` + seeing other players~~ (done). Then trade / fight.
7. Client-side prediction for own movement, area-of-interest filtering (everyone currently gets every `EntityMoved` in the world), login.

## Still open

- Can a character move between worlds, or is it locked to the world it was created in? (Assumed locked, since seeds differ.)
- ~~What triggers a world's initial content?~~ Decided: worlds start empty and grow from play (`Discovery`).
- Run speed (2 tiles per tick)? Does walking use energy?
- With many players repeating actions, do we care about bandwidth yet, or is "send everything nearby every tick" good enough for now? (Probably good enough.)
