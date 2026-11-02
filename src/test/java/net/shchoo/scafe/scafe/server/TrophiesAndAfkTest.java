package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.client.GameLog;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.generation.WorldGenerator;
import net.shchoo.scafe.scafe.model.Building;
import net.shchoo.scafe.scafe.model.Direction;
import net.shchoo.scafe.scafe.model.MonsterType;
import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.Recipe;
import net.shchoo.scafe.scafe.model.Stacks;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.protocol.Intent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Trophies go on display at the Gym (a slot, or a membership); AFK crafting stops at half the inventory; logging out
 * AFK leaves you in the world until the AFK's over.
 */
class TrophiesAndAfkTest {
    private final List<String> log = new ArrayList<>();
    private final Consumer<String> listener = log::add;
    private GameServer server;
    private Player player;
    private GameCharacter alice;
    private Building gym;
    private Product drop;
    private Recipe raw;

    @BeforeEach
    void setUp() {
        GameLog.subscribe(listener);
        World world = new World(1, "w", 7);
        server = new GameServer(world);
        server.setDiscovery(Discovery.off());
        server.setRandom(new CombatTest.Rigged(0.7, 0));
        server.applyContent(WorldGenerator.newSkills(world, 1));
        server.applyContent(WorldGenerator.newMonsterType(world));
        server.applyContent(WorldGenerator.newDrop(world, world.content.monsters.get(0).id));
        drop = world.content.monsters.get(0).drops.get(0).product();
        raw = world.content.craftingSkills().get(0).productLines.get(0).recipes.get(0);
        server.applyContent(WorldGenerator.newNode(world, new Position(2, 0), Node.UNLIMITED, Node.UNLIMITED, 0,
                r -> r.equals(raw) ? 1 : 0));
        server.ensureBuildings();
        gym = world.content.buildings().stream().filter(b -> b.kind() == Building.Kind.GYM).findFirst().orElseThrow();
        player = new Player(new LocalConnection(server.connect()));
        player.send(new Intent.Join("alice"));
        tick();
        alice = server.online(player.id);
    }

    @AfterEach
    void tearDown() {
        GameLog.unsubscribe(listener);
    }

    private void tick() {
        server.tick();
        player.receive();
    }

    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            tick();
        }
    }

    /** Beside the gym, facing it. */
    private void atTheGym() {
        for (Position tile : gym.tiles()) {
            Position below = new Position(tile.x(), tile.y() + 1);
            if (server.passable(below) && server.world.content.solidBuildingAt(below) == null) {
                alice.moveTo(below);
                alice.face(Direction.UP);
                tick();
                player.send(new Intent.Move(0, -1));
                tick();
                return;
            }
        }
        fail("nowhere to stand");
    }

    @Test
    void findingADropNoLongerAddsASlot_puttingItOnDisplayAtTheGymDoes() {
        alice.changeInventory(drop, 2);
        tick();
        assertEquals(Stacks.BASE_SLOTS, player.inventorySlots(), "carrying trophies adds nothing");

        player.send(new Intent.DisplayTrophy(drop.id, true));
        tick();
        assertEquals(2, alice.inventory.get(drop), "not at a Gym");
        assertTrue(log.contains("Trophies go on display at a Gym."), log.toString());

        atTheGym();
        player.send(new Intent.DisplayTrophy(drop.id, true));
        tick();
        assertEquals(1, alice.inventory.get(drop), "it's on display now");
        assertEquals(Stacks.BASE_SLOTS + 1, alice.inventorySlots());
        assertEquals(Stacks.BASE_SLOTS + 1, player.inventorySlots(), "the client knows");
        assertEquals(List.of(drop), alice.displayedTrophies);

        player.send(new Intent.DisplayTrophy(drop.id, false));
        tick();
        assertEquals(0, alice.inventory.getOrDefault(drop, 0));
        assertEquals(GameCharacter.GYM_ATTACKS_PER_TROPHY, alice.gymAttacks);
        assertEquals(GameCharacter.GYM_ATTACKS_PER_TROPHY, player.gymAttacks);
        assertEquals(Stacks.BASE_SLOTS + 1, alice.inventorySlots(), "a membership's no slot");

        alice.changeInventory(raw.product, 1);
        player.send(new Intent.DisplayTrophy(raw.product.id, true));
        tick();
        assertTrue(log.contains("Only trophies (monster drops) go on display at the Gym."), log.toString());
    }

    @Test
    void everyTwentyCraftsHealsOneHp() {
        Node node = server.world.content.nodes().iterator().next();
        alice.hp = 3;
        for (int craft = 1; craft <= 41; craft++) {
            alice.perform(node, false);
            alice.changeInventory(raw.product, -alice.inventory.getOrDefault(raw.product, 0));
            assertEquals(3 + craft / GameCharacter.CRAFTS_PER_HP, alice.hp, "after " + craft + " crafts");
        }
        tick();
        assertEquals(5, player.hp, "the client knows");
        alice.hp = alice.maxHp();
        for (int craft = 0; craft < 25; craft++) {
            alice.perform(node, false);
            alice.changeInventory(raw.product, -alice.inventory.getOrDefault(raw.product, 0));
        }
        assertEquals(alice.maxHp(), alice.hp, "never past full");
    }

    @Test
    void anOldSavesTrophiesFoundStillCountAsSlots() {
        MonsterType monster = server.world.content.monsters.get(0);
        monster.drops.add(new MonsterType.Drop(drop, 2, "bob"));
        monster.drops.add(new MonsterType.Drop(drop, 4, "bob"));
        Player bob = new Player(new LocalConnection(server.connect()));
        bob.send(new Intent.Join("bob"));
        server.tick();
        bob.receive();
        assertEquals(Stacks.BASE_SLOTS + 2, server.online(bob.id).inventorySlots(), "slots from before, kept");
        assertEquals(Stacks.BASE_SLOTS + 2, bob.inventorySlots());
    }

    @Test
    void noMembershipNoTraining_eachSwingTakesAnAttack() {
        atTheGym();
        player.perform();
        tick();
        assertNull(alice.training);
        assertTrue(log.stream().anyMatch(l -> l.startsWith("Training here takes a membership")), log.toString());

        alice.gymAttacks = 3;
        player.perform();
        ticks(20);
        assertEquals(0, alice.gymAttacks, "three swings");
        assertNull(alice.training, "and that's it");
        assertTrue(log.contains("Your Gym membership's used up: put another trophy on display for more."), log.toString());
    }

    @Test
    void afkCraftingStopsAtHalfTheInventory() {
        player.craft(new Position(2, 0), true);
        ticks(80);
        int half = Stacks.afkCapacity(alice.inventorySlots());
        assertNull(alice.currentAction, "stopped");
        assertEquals(half, alice.slotsUsed(), "a stack a craft at Lv. 1, up to half");
        assertTrue(log.contains("Your inventory's half full: that's as far as AFK crafting goes."), log.toString());

        player.craft(new Position(2, 0), true);
        ticks(3);
        assertNull(alice.currentAction, "won't start again at half full");
        player.craft(new Position(2, 0), false);
        ticks(4);
        assertTrue(alice.slotsUsed() > half, "crafting for real fills it on");
    }

    @Test
    void loggingOutAfk_youStayAndCarryOnTillItsOver() {
        Player bob = new Player(new LocalConnection(server.connect()));
        bob.send(new Intent.Join("bob"));
        player.craft(new Position(2, 0), true);
        ticks(4);
        assertNotNull(alice.currentAction);
        player.send(new Intent.Leave());
        tick();
        bob.receive();
        assertSame(alice, server.online(alice.id), "still in the world");
        assertTrue(alice.offline);
        assertNotNull(alice.currentAction, "still crafting");
        int made = alice.slotsUsed();
        ticks(6);
        assertTrue(alice.slotsUsed() > made, "and making things");

        ticks(80);
        assertNull(server.online(alice.id), "half full: gone");
        assertFalse(alice.offline);
    }

    @Test
    void loggingInAgain_takesOverWhereItsGotTo() {
        player.craft(new Position(2, 0), true);
        ticks(4);
        player.send(new Intent.Leave());
        ticks(4);
        Player phone = new Player(new LocalConnection(server.connect()));
        phone.send(new Intent.Join("alice"));
        server.tick();
        phone.receive();
        assertTrue(phone.inWorld());
        assertSame(alice, server.online(phone.id), "the same character, not a fresh one");
        assertFalse(alice.offline);
        assertNotNull(alice.currentAction, "still crafting");
        assertNotNull(phone.currentAction, "and the new screen shows it");
        assertEquals(1, server.onlineCharacters().stream().filter(c -> c.name.equals("alice")).count());
    }

    @Test
    void loggingOutAtTheGym_trainingCarriesOnTillTheMembershipsUsedUp() {
        alice.gymAttacks = 4;
        atTheGym();
        player.perform();
        tick();
        assertNotNull(alice.training);
        player.send(new Intent.Leave());
        tick();
        assertSame(alice, server.online(alice.id), "still training");
        assertNotNull(alice.training);
        ticks(20);
        assertEquals(0, alice.gymAttacks);
        assertNull(server.online(alice.id), "the membership's used up: gone");
    }
}
