package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.client.SkillStyle;
import net.shchoo.scafe.scafe.model.Position;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.TextColor;
import net.shchoo.scafe.scafe.client.ui.MapView;
import net.shchoo.scafe.scafe.client.ui.Layout;
import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Popup;
import net.shchoo.scafe.scafe.model.Stacks;
import net.shchoo.scafe.scafe.client.ui.InventoryPanel;
import net.shchoo.scafe.scafe.model.EquipSlot;
import net.shchoo.scafe.scafe.model.DamageColor;
import net.shchoo.scafe.scafe.model.Stat;
import net.shchoo.scafe.scafe.client.GameLog;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.model.*;
import net.shchoo.scafe.scafe.protocol.Event;
import net.shchoo.scafe.scafe.protocol.Intent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class CombatTest {

    /**
     * nextDouble() always returns {@code roll} (0.7 = your hits land, the monster's miss); nextInt(bound) returns
     * {@code pick} capped to the bound (0 = every drop roll succeeds). Monsters never wander off.
     */
    static class Rigged extends Random {
        double roll;
        int pick;

        Rigged(double roll, int pick) {
            this.roll = roll;
            this.pick = pick;
        }

        @Override
        public double nextDouble() {
            return roll;
        }

        @Override
        public int nextInt(int bound) {
            if (bound == 100) {
                return 99;
            }
            return Math.min(pick, bound - 1);
        }
    }

    private GameServer server;
    private Rigged random;
    private Player alice;
    private final List<String> log = new ArrayList<>();

    @BeforeEach
    void setUp() {
        server = new GameServer(new World(1, "w", 21));
        server.setDiscovery(Discovery.off());
        random = new Rigged(0.7, 0);
        server.setRandom(random);
        GameLog.subscribe(log::add);
        alice = join("alice");
    }

    private Player join(String name) {
        Player player = new Player(new LocalConnection(server.connect()));
        player.send(new Intent.Join(name));
        tick(player);
        return player;
    }

    private void tick(Player... players) {
        server.tick();
        for (Player player : players.length == 0 ? new Player[]{alice} : players) {
            player.receive();
        }
    }

    private Event.EntityInfo spawnNextToAlice() {
        alice.send(new Intent.Admin("4", List.of()));
        tick();
        return alice.others.values().stream().filter(Event.EntityInfo::isMonster).findFirst().orElseThrow();
    }

    /** Starts a fight unless one is already going (Attack toggles), then ticks until it's over. */
    private void fightUntilOver() {
        if (alice.fightingId == null) {
            alice.send(new Intent.Attack());
        }
        for (int i = 0; i < 200 && (i == 0 || alice.fightingId != null); i++) {
            tick();
        }
        assertNull(alice.fightingId, "fight should be over");
    }

    @Test
    void theFirstMonsterBringsTheCombatSkill() {
        Event.EntityInfo monster = spawnNextToAlice();
        assertNotNull(alice.content.combatSkill());
        assertEquals(SkillType.COMBAT, alice.content.combatSkill().type);
        assertEquals("monster0", monster.name());
        int distance = Math.max(Math.abs(monster.position().x() - alice.x), Math.abs(monster.position().y() - alice.y));
        assertEquals(1, distance);
        assertTrue(alice.content.craftingSkills().isEmpty());
    }

    @Test
    void killingGivesXpAndAOneOfAKindDrop() {
        Event.EntityInfo monster = spawnNextToAlice();
        MonsterType type = alice.content.monster(monster.monsterTypeId());
        alice.send(new Intent.Attack());
        tick();
        assertEquals(monster.id(), alice.fightingId);
        assertTrue(log.contains("You attack monster0 (Lv. " + type.level + ")."));
        fightUntilOver();

        Skill combat = alice.content.combatSkill();
        assertEquals((long) type.maxHp * Combat.XP_PER_DAMAGE, alice.getExperience(combat), "1 damage per hit, every hit lands");
        assertEquals((long) type.maxHp, alice.stats.get(net.shchoo.scafe.scafe.model.Stat.DAMAGE_DEALT), "damage dealt, all of it");
        assertEquals(alice.maxHp, alice.hp, "the monster never landed a hit");
        assertTrue(log.contains("You defeated monster0!"));
        assertFalse(alice.others.containsKey(monster.id()), "dead monsters leave the map");

        assertEquals(1, type.drops.size(), "drop0 dropped on the first kill");
        Product drop0 = type.drops.get(0).product();
        assertEquals("monster0-drop0", drop0.name);
        assertEquals(2, type.drops.get(0).oneIn());
        assertEquals("alice", type.drops.get(0).foundBy());
        assertEquals(1, alice.inventory.get(drop0));
        assertTrue(log.contains("You found monster0-drop0 -- the only one there will ever be!"));
        assertEquals(type, alice.content.droppedBy(drop0));

        spawnNextToAlice();
        fightUntilOver();
        assertEquals(2, type.drops.size());
        assertEquals("monster0-drop1", type.drops.get(1).product().name);
        assertEquals(4, type.drops.get(1).oneIn());
        assertEquals(1, alice.inventory.get(drop0), "drop0 never drops again");
        assertEquals(1, alice.inventory.get(type.drops.get(1).product()));
    }

    @Test
    void eachDropOnlyEverExistsOnceInTheWorld() {
        Player bob = join("bob");
        spawnNextToAlice();
        fightUntilOver();
        bob.receive();
        MonsterType type = alice.content.monsters.get(0);
        Product drop0 = type.drops.get(0).product();
        assertTrue(log.contains("alice found monster0-drop0. There won't be another."));

        // bob kills the same kind of monster with the same luck: he gets the *next* one, never another drop0
        bob.send(new Intent.Admin("4", List.of()));
        tick(alice, bob);
        bob.send(new Intent.Attack());
        for (int i = 0; i < 200 && (i == 0 || bob.fightingId != null); i++) {
            tick(alice, bob);
        }
        assertNull(bob.inventory.get(bob.content.product(drop0.id)));
        assertEquals(1, bob.inventory.get(bob.content.product(type.drops.get(1).product().id)));
        assertEquals("bob", type.drops.get(1).foundBy());
        int drop0sInTheWorld = alice.inventory.getOrDefault(drop0, 0) + bob.inventory.getOrDefault(bob.content.product(drop0.id), 0);
        assertEquals(1, drop0sInTheWorld);
        assertEquals(1, alice.trophies(), "a trophy each");
        assertEquals(1, bob.trophies());
    }

    @Test
    void ifTheDropCantBeSavedNobodyFindsIt() {
        server = new GameServer(new World(1, "w", 21), new Storage() {
            @Override
            public void saveContent(ContentBatch batch, Grant grant) throws Exception {
                if (grant != null) {
                    throw new Exception("disk full");
                }
            }

            @Override
            public GameCharacter loadOrCreateCharacter(String accountName) {
                return new GameCharacter(1, accountName);
            }

            @Override
            public void saveCharacter(GameCharacter character) {}

            @Override
            public String passwordHash(String accountName) {
                return null;
            }

            @Override
            public void setPasswordHash(String accountName, String hash) {}
        });
        server.setDiscovery(Discovery.off());
        server.setRandom(random);
        alice = join("alice");
        spawnNextToAlice();
        fightUntilOver();
        assertTrue(log.contains("You defeated monster0!"));
        assertTrue(log.contains("Something glinted on the ground, but it's gone."));
        assertTrue(server.world.content.monsters.get(0).drops.isEmpty(), "not found, so still up for grabs");
        assertTrue(alice.inventory.isEmpty());
    }

    @Test
    void noLuckMeansNoDrops() {
        random.pick = Integer.MAX_VALUE;
        Event.EntityInfo monster = spawnNextToAlice();
        fightUntilOver();
        MonsterType type = alice.content.monster(monster.monsterTypeId());
        assertTrue(type.drops.isEmpty());
        assertTrue(log.contains("You defeated monster0!"));
    }

    @Test
    void aStrongMonsterKnocksYouOutBackToTheOrigin() {
        alice.send(new Intent.WalkTo(new Position(4, 0)));
        for (int i = 0; i < 4; i++) {
            tick();
        }
        WorldContent scratch = server.world.content.copy();
        scratch.addSkill(new Skill(scratch.allocateId(), "combat", SkillType.COMBAT, 0, 'x'));
        MonsterType brute = new MonsterType(scratch.allocateId(), "brute", 50, 500, 100, 0, 'B', 3, 2);
        scratch.addMonster(brute);
        server.applyContent(scratch.diff(server.world.content));
        server.spawnMonster(server.world.content.monster(brute.id), new Position(5, 0));
        tick();

        random.roll = 0.0;
        random.pick = Integer.MAX_VALUE;
        alice.send(new Intent.Attack());
        for (int i = 0; i < 10 && alice.hp > 0; i++) {
            tick();
        }
        assertEquals(0, alice.hp, "down");
        assertTrue(log.contains("brute knocked you out."));
        assertNull(alice.fightingId);
        Position fell = alice.position();
        assertNotEquals(new Position(0, 0), fell, "where you fell, for a moment");
        alice.moveBy(-1, 0);
        for (int i = 0; i < Combat.DOWN_TICKS - 1; i++) {
            tick();
            assertEquals(0, alice.hp);
        }
        assertEquals(fell, server.online(alice.id).position(), "you can't move while you're down");
        tick();
        assertEquals(new Position(0, 0), alice.position());
        assertEquals(alice.maxHp, alice.hp);
        assertTrue(log.contains("You wake up at the origin."));
    }

    @Test
    void knockedOutPlayersFlickerDarkGrey() throws Exception {
        alice.hp = 0;
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(Layout.TERMINAL_SIZE));
        screen.startScreen();
        java.util.Set<TextColor> seen = new java.util.HashSet<>();
        for (int frame = 0; frame < 4 * Player.FLICKER_FRAMES; frame++) {
            alice.frame();
            new MapView(Layout.MAP).draw(screen, alice, new Board(alice.content));
            seen.add(screen.getBackCharacter(Layout.tileScreenX(alice.x, alice.x), Layout.tileScreenY(alice.y, alice.y))
                    .getForegroundColor());
        }
        assertEquals(java.util.Set.of(TextColor.ANSI.BLACK_BRIGHT, SkillStyle.playerColor(alice.colorIndex)), seen);
    }

    @Test
    void walkingAwayEndsTheFight() {
        spawnNextToAlice();
        alice.send(new Intent.Attack());
        tick();
        assertNotNull(alice.fightingId);
        Event.EntityInfo monster = alice.fightingMonster();
        int awayX = monster.position().x() <= alice.x ? 1 : -1;
        alice.moveBy(awayX, 0);
        alice.moveBy(awayX, 0);
        tick();
        tick();
        assertNull(alice.fightingId);
        assertTrue(log.contains("You stopped fighting."));
    }

    @Test
    void attackingAgainOrPressingQStopsTheFight() {
        spawnNextToAlice();
        alice.send(new Intent.Attack());
        tick();
        alice.send(new Intent.Attack());
        tick();
        assertNull(alice.fightingId);
        alice.send(new Intent.Attack());
        tick();
        alice.send(new Intent.Perform());
        tick();
        assertNull(alice.fightingId);
        assertEquals(2, log.stream().filter("You stop fighting."::equals).count());
    }

    @Test
    void nothingToFightWithoutAMonsterNextToYou() {
        alice.send(new Intent.Attack());
        tick();
        assertTrue(log.contains("Nothing to fight here."));
    }

    @Test
    void othersSeeTheMonsterAndItsHp() {
        Player bob = join("bob");
        tick(alice, bob);
        Event.EntityInfo monster = spawnNextToAlice();
        bob.receive();
        assertTrue(bob.others.containsKey(monster.id()));
        alice.send(new Intent.Attack());
        tick(alice, bob);
        tick(alice, bob);
        assertTrue(bob.others.get(monster.id()).hp() < monster.maxHp(), "bob sees the damage");
        fightUntilOver();
        bob.receive();
        assertFalse(bob.others.containsKey(monster.id()));
    }

    @Test
    void combatLevelsRaiseMaxHp() {
        GameCharacter fighter = new GameCharacter(1, "f");
        Skill combat = new Skill(99, "combat", SkillType.COMBAT, 0, 'x');
        assertEquals(Combat.maxHp(1), fighter.maxHp());
        fighter.hp = 5;
        fighter.gainExperience(combat, Skill.XP_PER_LEVEL[1]);
        assertEquals(2, fighter.combatLevel());
        assertEquals(Combat.maxHp(2), fighter.maxHp());
        assertEquals(6, fighter.hp, "the extra max hp is added to current hp");
    }

    @Test
    void monstersStayWithinTheirRoamRadiusAndLeaveWhenNobodyIsAround() {
        Monsters monsters = new Monsters();
        MonsterType type = new MonsterType(1, "m", 1, 5, 1, 0, 'M', 2, 1);
        Monster monster = monsters.spawn(type, new Position(10, 10));
        GameCharacter nearby = new GameCharacter(1, "near");
        nearby.moveTo(new Position(10, 10));
        Random rng = new Random(3);
        boolean moved = false;
        for (int i = 0; i < 500; i++) {
            moved |= !monsters.tick(rng, List.of(nearby)).isEmpty();
            assertTrue(Monsters.distance(monster.position(), monster.home) <= type.roamRadius, "wandered off: " + monster.position());
        }
        assertTrue(moved, "it should wander a bit");
        assertTrue(monsters.contains(monster));

        nearby.moveTo(new Position(500, 500));
        for (int i = 0; i <= Monsters.DESPAWN_TICKS; i++) {
            monsters.tick(rng, List.of(nearby));
        }
        assertFalse(monsters.contains(monster));
    }

    @Test
    void monstersOnlyStepEveryMoveTicks() {
        Monsters monsters = new Monsters();
        MonsterType slow = new MonsterType(1, "slow", 1, 5, 1, 0, 'S', 5, 3);
        Monster monster = monsters.spawn(slow, new Position(0, 0));
        GameCharacter nearby = new GameCharacter(1, "near");
        Random rng = new Random(11);
        long lastStep = -100;
        int steps = 0;
        for (int tick = 0; tick < 600; tick++) {
            if (!monsters.tick(rng, List.of(nearby)).isEmpty()) {
                assertTrue(tick - lastStep >= slow.moveTicks, "stepped again after " + (tick - lastStep) + " ticks");
                lastStep = tick;
                steps++;
            }
        }
        assertTrue(steps > 10, "it should still get around (" + steps + " steps)");
        assertTrue(Monsters.distance(monster.position(), monster.home) <= slow.roamRadius);
    }

    @Test
    void monsterIdsNeverClashWithPlayers() {
        Monsters monsters = new Monsters();
        MonsterType type = new MonsterType(1, "m", 1, 5, 1, 0, 'M', 3, 2);
        assertTrue(monsters.spawn(type, new Position(0, 0)).id < 0);
        assertTrue(monsters.spawn(type, new Position(0, 0)).id < 0);
        assertTrue(alice.id > 0);
    }

    @Test
    void killsAreCounted() {
        spawnNextToAlice();
        fightUntilOver();
        tick();
        assertEquals(1L, alice.stats.get(Stat.MONSTERS_KILLED));
    }

    @Test
    void levelStatsComeFromYourXp() {
        alice.send(new Intent.Admin("1", List.of("2")));
        tick();
        assertEquals(0, alice.totalExperience());
        assertEquals(1, alice.highestLevel());
        spawnNextToAlice();
        fightUntilOver();
        Skill combat = alice.content.combatSkill();
        assertEquals(alice.getExperience(combat).longValue(), alice.totalExperience());
        assertEquals(alice.getLevel(combat), alice.highestLevel());
        assertEquals(alice.content.skills.size() - 1 + alice.getLevel(combat), alice.totalLevel());
    }

    /** A tier-2 item of a fresh crafting skill, {@code count} of them in alice's inventory, the skill at {@code level}. */
    private Product giveItems(int level, int count) {
        alice.send(new Intent.Admin("1", List.of("1")));
        tick();
        Skill skill = alice.content.craftingSkills().get(0);
        if (level > 1) {
            alice.send(new Intent.Admin("5", List.of(skill.name, String.valueOf(Skill.XP_PER_LEVEL[level - 1]))));
        }
        Product item = skill.productLines.get(0).recipes.get(2).product;
        server.online(alice.id).changeInventory(server.world.content.product(item.id), count);
        tick();
        return item;
    }

    @Test
    void anEquippedItemIsUsedUpFromEveryStackThenYoureOnFists() {
        Product item = giveItems(2, 5);
        assertEquals(3, InventoryPanel.slots(alice).size(), "stacks of 2: 2 + 2 + 1");
        alice.equip(EquipSlot.WEAPON, item);
        tick();
        assertEquals(item, alice.weapon);
        assertEquals(5, alice.inventory.get(item), "equipping takes nothing out of the inventory");
        for (int i = 0; i < 400 && alice.weapon != null; i++) {
            if (alice.fightingId == null) {
                spawnNextToAlice();
                alice.send(new Intent.Attack());
            }
            tick();
        }
        assertNull(alice.weapon, "all 5, across the stacks");
        assertEquals(0, alice.inventory.get(item));
        assertTrue(log.contains("You've used up your " + item.name + " (weapon)."));
    }

    @Test
    void eachSwingUsesOneUpAndHitsForTheTierTimesTheColour() {
        Product item = giveItems(1, 3);
        alice.equip(EquipSlot.WEAPON, item);
        tick();
        Event.EntityInfo monster = spawnNextToAlice();
        MonsterType type = alice.content.monster(monster.monsterTypeId());
        alice.send(new Intent.Attack());
        for (int i = 0; i < 20 && alice.inventory.get(item) == 3; i++) {
            tick();
        }
        assertEquals(2, alice.inventory.get(item), "one swing, one used");
        assertEquals(item, alice.weapon, "still equipped: there are more");

        double strength = 3 * DamageColor.multiplier(DamageColor.of(alice.content, item), type.colorIndex);
        int hit = Math.min(monster.hp(), (int) Math.ceil(strength - 1e-9));
        assertEquals(monster.hp() - hit, alice.others.get(monster.id()).hp(), "tier 2 = 3 base, rounded up by the rigged dice");
    }

    @Test
    void armourTakesTheMonstersHitsInItsColourAndWearsOut() {
        random.roll = 0.5;
        Product item = giveItems(1, 4);
        alice.equip(EquipSlot.ARMOR, item);
        tick();
        Event.EntityInfo monster = spawnNextToAlice();
        MonsterType type = alice.content.monster(monster.monsterTypeId());
        int hp = alice.hp;
        alice.send(new Intent.Attack());
        for (int i = 0; i < 20 && alice.inventory.get(item) == 4; i++) {
            tick();
        }
        assertEquals(3, alice.inventory.get(item), "one monster swing at you, one used");
        double taken = DamageColor.multiplier(type.colorIndex, DamageColor.of(alice.content, item));
        assertEquals(hp - (int) Math.ceil(taken - 1e-9), alice.hp, "its 1 damage times its colour against the armour's");
    }

    @Test
    void unequipPutsItAwayAndLeavesTheInventoryAlone() {
        Product item = giveItems(1, 2);
        alice.equip(EquipSlot.WEAPON, item);
        alice.equip(EquipSlot.ARMOR, item);
        tick();
        assertEquals(item, alice.weapon);
        assertEquals(item, alice.armor, "the same item can be both");
        alice.unequip(EquipSlot.WEAPON);
        tick();
        assertNull(alice.weapon);
        assertEquals(item, alice.armor);
        assertEquals(2, alice.inventory.get(item));
    }

    @Test
    void fistsAreBlack() {
        Event.EntityInfo monster = spawnNextToAlice();
        MonsterType type = alice.content.monster(monster.monsterTypeId());
        alice.send(new Intent.Attack());
        for (int i = 0; i < 20 && alice.others.get(monster.id()).hp() == monster.hp(); i++) {
            tick();
        }
        double strength = DamageColor.multiplier(DamageColor.BLACK, type.colorIndex);
        assertEquals(monster.hp() - (int) Math.ceil(strength - 1e-9), alice.others.get(monster.id()).hp(),
                "x1/2 against colour rounds up to 1 here; x2 against the colourless");
    }

    @Test
    void noDropWhileYourInventoryIsFull() {
        Product item = giveItems(1, Stacks.INVENTORY_SLOTS);
        assertEquals(Stacks.INVENTORY_SLOTS, alice.slotsUsed(), "stacks of 1 at level 1");
        Event.EntityInfo monster = spawnNextToAlice();
        MonsterType type = alice.content.monster(monster.monsterTypeId());
        fightUntilOver();
        assertTrue(log.contains("Something glinted, but your inventory is full."));
        assertTrue(type.drops.isEmpty(), "the drop is still out there for later");
        assertEquals(Stacks.INVENTORY_SLOTS, alice.inventory.get(item));
    }

    @Test
    void hitsArePlainNumbersATileOutOnEachFightersSideUnderTheXp() throws Exception {
        random.roll = 0.5;
        Event.EntityInfo monster = spawnNextToAlice();
        Position me = alice.position();
        alice.send(new Intent.Attack());
        for (int i = 0; i < 20 && alice.popups.stream().filter(p -> p.background == TextColor.ANSI.RED).count() < 2; i++) {
            tick();
        }
        Popup mine = alice.popups.stream().filter(p -> p.background == TextColor.ANSI.RED && p.at().equals(me)).findFirst().orElseThrow();
        Popup theirs = alice.popups.stream().filter(p -> p.background == TextColor.ANSI.RED && !p.at().equals(me)).findFirst().orElseThrow();
        Popup xp = alice.popups.stream().filter(p -> p.text.endsWith("xp")).findFirst().orElseThrow();
        assertTrue(mine.text.matches("[0-9]+") && theirs.text.matches("[0-9]+"), "no signs: " + mine.text + " " + theirs.text);
        assertEquals(TextColor.ANSI.WHITE, mine.color);
        int dx = monster.position().x() - alice.x;
        assertEquals(dx == 0 ? -1 : -Integer.signum(dx), mine.side, "mine away from the monster");
        assertEquals(-mine.side, theirs.side, "theirs the other way");
        assertEquals(0, xp.side, "xp centred on you");
        assertEquals(xp.rise() - 1, mine.rise(), "a row under the xp");

        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(Layout.TERMINAL_SIZE));
        screen.startScreen();
        new MapView(Layout.MAP).draw(screen, alice, new Board(alice.content));
        int myTile = Layout.tileScreenX(alice.x, alice.x), row = Layout.tileScreenY(alice.y, alice.y);
        int at = mine.side < 0 ? myTile - 1 - mine.text.length() : myTile + 2;
        assertEquals(mine.text.substring(0, 1), screen.getBackCharacter(at, row).getCharacterString(), "a tile out, on your row");
        assertEquals(TextColor.ANSI.RED, screen.getBackCharacter(at, row).getBackgroundColor());
    }

    @Test
    void aMissIsAZeroOnBlue() {
        random.roll = 0.95;
        spawnNextToAlice();
        alice.send(new Intent.Attack());
        for (int i = 0; i < 10 && alice.popups.stream().noneMatch(p -> p.background == TextColor.ANSI.BLUE); i++) {
            tick();
        }
        Popup miss = alice.popups.stream().filter(p -> p.background == TextColor.ANSI.BLUE).findFirst().orElseThrow();
        assertEquals("0", miss.text);
        assertEquals(TextColor.ANSI.WHITE, miss.color);
    }
}
