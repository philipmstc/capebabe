package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.client.GameLog;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.generation.WorldGenerator;
import net.shchoo.scafe.scafe.model.Building;
import net.shchoo.scafe.scafe.model.Direction;
import net.shchoo.scafe.scafe.model.EquipSlot;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.Skill;
import net.shchoo.scafe.scafe.model.Stat;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.protocol.Intent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class GymTest {
    private final List<String> log = new ArrayList<>();
    private final Consumer<String> listener = log::add;
    private GameServer server;
    private Player player;
    private GameCharacter alice;
    private Building gym;
    private Product sword;
    private Product shield;

    @BeforeEach
    void setUp() {
        GameLog.subscribe(listener);
        World world = new World(1, "w", 7);
        server = new GameServer(world);
        server.setDiscovery(Discovery.off());
        // every swing lands, and rounds up
        server.setRandom(new CombatTest.Rigged(0.7, 0));
        server.applyContent(WorldGenerator.newSkills(world, 1));
        server.applyContent(WorldGenerator.newMonsterType(world));
        server.ensureBuildings();
        gym = world.content.buildings().stream().filter(b -> b.kind() == Building.Kind.GYM).findFirst().orElseThrow();
        Skill skill = world.content.craftingSkills().get(0);
        sword = skill.productLines.get(0).recipes.get(1).product;
        shield = skill.productLines.get(0).recipes.get(0).product;
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

    private long combatXp() {
        return alice.getExperience(server.world.content.combatSkill());
    }

    @Test
    void itsInTheWorld() {
        assertEquals(Building.Kind.GYM, server.world.content.solidBuildingAt(gym.corner()).kind());
        assertEquals(15, gym.tiles().size(), "5 x 3");
    }

    @Test
    void trainingTakesYourArmourOffAndWearsYourWeaponForAQuarterOfTheXp() {
        alice.changeInventory(sword, 10);
        alice.changeInventory(shield, 10);
        player.equip(EquipSlot.WEAPON, player.content.product(sword.id));
        player.equip(EquipSlot.ARMOR, player.content.product(shield.id));
        tick();
        atTheGym();
        int hp = alice.hp;
        player.perform();
        tick();
        assertNotNull(alice.training);
        assertNull(alice.armor, "armour off first");
        assertEquals(10, alice.inventory.get(shield), "kept, just not worn");
        assertEquals(Combat.TRAINING, player.actionLabel());

        long xp = combatXp();
        for (int i = 0; i < 4 * Combat.SWING_TICKS; i++) {
            tick();
        }
        int swings = 10 - alice.inventory.get(sword);
        assertTrue(swings >= 3, "a swing every " + Combat.SWING_TICKS + " ticks: " + swings);
        // tier 1 weapon: 2, plus a quarter of combat level 1, rounded up: 3 a hit, 1 xp each
        assertEquals(xp + 3L * swings, combatXp(), "a quarter of a fight's xp");
        assertEquals(hp, alice.hp, "it never hits back");
        assertNotNull(alice.training, "and it never falls");
        assertEquals(3L * swings, alice.stats.get(Stat.DAMAGE_DEALT), "damage dealt");
        assertTrue(alice.stats.get(Stat.TIME_TRAINED) >= 4 * Combat.SWING_TICKS, "time trained, a tick at a time");
        assertEquals(alice.stats.get(Stat.DAMAGE_DEALT), player.stats.get(Stat.DAMAGE_DEALT), "the client's told");
    }

    @Test
    void movingOrQAgainStopsIt() {
        atTheGym();
        player.perform();
        tick();
        assertNotNull(alice.training);
        player.perform();
        tick();
        assertNull(alice.training);
        assertNull(player.currentAction);
        assertTrue(log.contains("You stop training."));

        player.perform();
        tick();
        assertNotNull(alice.training);
        player.send(new Intent.Move(0, 1));
        tick();
        tick();
        assertNull(alice.training, "walked off");
    }

    @Test
    void puttingArmourBackOnStopsIt() {
        alice.changeInventory(shield, 5);
        atTheGym();
        player.perform();
        tick();
        player.equip(EquipSlot.ARMOR, player.content.product(shield.id));
        tick();
        tick();
        assertNull(alice.training);
        assertTrue(log.contains("You can't train in armour."));
    }
}
