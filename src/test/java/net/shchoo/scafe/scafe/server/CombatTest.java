package net.shchoo.scafe.scafe.server;

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
        for (int i = 0; i < 5; i++) {
            tick();
        }
        assertEquals(new Position(0, 0), alice.position());
        assertEquals(alice.maxHp, alice.hp);
        assertNull(alice.fightingId);
        assertTrue(log.contains("brute knocked you out. You wake up at the origin."));
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
}
