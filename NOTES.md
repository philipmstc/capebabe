# Helpful analogy
## Primary skill = Woodcutting, Mining. 
- These have 1 product line -- logs, and ores. Of several tiers.

## Secondary Skill - Smithing*
- If confined only to creating Bars and Arrowheads from various ores / bars respectively, smithing is a single product line secondary skill.

## Tertiary skill - Fletching
- combines outputs from the primary product line from woodcutting and secondary (derived) product line of smithing, and, a neutral product "Feathers" that are not a skill based resource to produce arrows.


# TODOS

## Shit to do first
1. Make commands `1`, `2`, `3` be derived from user actions with exponential backoff. Examples of actions: Moving (with lower probability.), "producing" (`q`), on level up
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
- [x] Hover + `Enter` walks the player to the cursor, one tile per tick (horizontal then vertical). `Pathfinding.orthogonal` is the algorithm, `Walker` steps anything implementing `Walker.Mobile` -- monsters can reuse both. Manual wasd cancels the walk.
- [x] Spot check of Recipe validation / resource usage -- see below
- [x] Off-screen "home" arrow on the map border: points at the last tile where something was successfully made (yellow), or the origin (white) if nothing yet. `BoardView.drawHomeIndicator`, tracked in `Player.lastActionAt`.

### Recipe spot check
- Fixed: `create` didn't check `levelNeeded` at all (only components). Added `Recipe.levelMet`, and `create` now says why it failed ("Need X Lv. 20 (you're Lv. 1)") instead of the generic "I can't do that!".
- Heads up: `ProductLineFactory` tiers are currently `{0}` only, so nothing is ever locked in-game and Goals can't be tried out. Put the 5 tiers back (or add one locked tier) to play with it.
- Not changed: `attemptToUseAll` leaves products at `x 0` in the inventory. Fine if intentional ("you've had this before"), otherwise remove the entry at 0.
- Not changed: `Recipe`'s constructor does `comps.put(co, 1)`, so passing the same product twice still needs only 1. Use `merge(co, 1, Integer::sum)` once multi-quantity recipes are a thing.
- Not changed (pre-existing): `gainExperience` levels up at most once per call, and `XP_PER_LEVEL[100]` will throw once someone hits 100.

## Morning (2026-10-17)
- [x] Progress bar (the box under the map, used to be the `frame` clock): empty until `q` starts an action, then fills green over `Board.Action.durationTicks()` (default `DEFAULT_DURATION_TICKS` = 20, ~1.2s) with the product name on top. The product is made when it fills. Locked / missing components fail immediately (`Action.canPerform`) with the reason. Moving (wasd or walking) interrupts it; `q` while busy says "Already busy."
- [x] XP popup: "+200xp" in the skill's color floats up from the tile (`Popup`, drawn in `BoardView.drawPopups`).
- [x] Actions repeat: after each completion the bar restarts if `canPerform` still holds (stops with "Can't make any more X." when components run out). `q` while busy stops it, moving interrupts it. Actions with no components (primaries) repeat forever until stopped.
- [x] Level up popup: " skill Lv. N! " in black on the skill's color, follows the player (`Popup.following`), lives `Player.LEVEL_UP_POPUP_TICKS` (45, ~2.7s). Log says the new level, "Unlocked: ..." for recipes newly within reach, and completed goals.
- [x] Leveling fix: one xp drop can now raise several levels; max level is 100 and xp past that never levels again (it still accumulates). `getExperienceToNextLevel` no longer goes out of bounds at 100.
- [x] XP tracker, top right of the header row: tracked skill (most recent xp drop), level, a progress bar to the next level (green fill, "662/1.4k", "MAX" at 100) and xp/h. `XpTracker` (data, injectable clock) + `XpTrackerView` (drawing).
  - xp/h = xp since the first drop of the session / time since that drop (the first drop only starts the clock). Shows `--/h` until the second drop, decays while idle, resets when the tracked skill changes or after 5 idle minutes (`IDLE_RESET_MILLIS`).
- [ ] TODO (think about): ways the game can interrupt a long repeating action to break up the loop in a fun way -- random events, a monster wandering up, a "rare find" mid-batch, a node depleting, an inventory-full check, fatigue...

# Next steps / ideas

## Current controls
- `Left`/`Right` switch tabs. `Down`/`Enter` focuses the current tab, `Esc` backs out (and quits from the top level).
- Map: unfocused, the right side previews the tile you're standing on. Focused, arrows move a hover cursor and `Enter` walks there.
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
- Walk speed is one tile per frame (~60ms + render). If the frame rate changes, give `Walker` its own tick interval.
- Pathfinding ignores obstacles (there aren't any yet). Swap `Pathfinding.orthogonal` for BFS/A* once tiles can block.
- `Board.Action.describe()` is how the preview gets its text; `RecipeAction` is the only real node type (used by the debug `3` command). The preview shows "locked" in red; could also show missing components.
- Symbol color semantics: bright = normal, dim = tried and too low level. Could add more states (e.g. never seen, depleted) once nodes get richer.

## Skills
- Next for #4: drill one more level into a product line to list each tier's recipe in full (product, components, xp), basically the `l` command's output but browsable. Would probably be a third column, or replace the skill list column.
- Product line names are derived by stripping `-tier` off the tier 0 product name. `ProductLine` should probably get a real `name` field.
- The "from" line could be colored with the source skill's color/symbol, and the product line view could show how many of each tier's product are in the inventory.
- Skill list column is 32 wide (rows cut with `~`), product lines at `SkillsMenu.DETAIL_POS_X` get ~42 cols at 100 wide. If menus grow, derive widths from `screen.getTerminalSize()`.

## Actions / progress
- Durations are in ticks (one tick = one frame of the main loop, ~60ms + render). Give `RecipeAction` per-tier durations by overriding `durationTicks()`.
- Skills list shows `xp/14921909` at Lv. 100; could show "MAX" instead (the XP tracker already does).
- XP tracker ideas: keep per-skill sessions instead of resetting on switch, show "time to level" (remaining xp / rate), let `/` command reset it. If a lot more tabs get added the header row runs out of room -- the tracker could move to its own row.
- Popups are generic (`new Popup(position, text, color)` on `player.popups`), so level ups or item drops could use them too. They live on `Player` for now; if monsters need them, move the list to `Board`.

## Goals
- Make the tab focusable to remove goals / sort them; maybe auto-remove (or archive) completed ones.
- Goals could hook into TODO #1 (spawning skills/nodes from player actions) -- e.g. completing a goal is a trigger.
- `GameLog` only carries strings. If TODO #1 needs to react to level ups / production, add typed events alongside it rather than parsing messages.

## Inventory
- Could reuse the Skills browse pattern: "made by" / "used in" for each item.

## Code structure
- New top-level menu = subclass `Menu`, add it to the `menus` list in `Main`. Headers lay themselves out from the titles.
- Menus only get keys while focused. Keys they return `IGNORED` for fall through to the global wasd/q// handling.
- Map drawing is in `BoardView`, skill colors/symbols in `SkillStyle`.
- Player-facing messages: `GameLog.say(...)`. Movement: `Walker` + `Pathfinding`. Map nodes: subclass `Board.Action` (see `RecipeAction`).
- Layout constants still live on `Main` (`LIST_ITEM_POS_X`, `LIST_ROWS`, ...). Could move them to a small `Layout` class once there's more than one area that cares.
