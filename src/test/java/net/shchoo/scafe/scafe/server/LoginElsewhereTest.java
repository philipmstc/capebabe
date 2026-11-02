package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.generation.WorldGenerator;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.protocol.Event;
import net.shchoo.scafe.scafe.protocol.Intent;

import net.shchoo.scafe.scafe.persistence.AccountStore;
import net.shchoo.scafe.scafe.persistence.WorldStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Logging in again as someone already playing (your phone, after your computer): the new login plays, the old stops. */
class LoginElsewhereTest {
    private GameServer server;
    private Product item;

    @BeforeEach
    void setUp() {
        World world = new World(1, "w", 1);
        world.content.apply(WorldGenerator.newSkills(world, 1));
        item = world.content.skills.get(0).productLines.get(0).recipes.get(0).product;
        server = new GameServer(world);
        server.setDiscovery(Discovery.off());
    }

    private void tick(Player... players) {
        server.tick();
        for (Player p : players) {
            p.receive();
        }
    }

    @Test
    void theNewLoginTakesOver_andTheOldOneIsToldWhyAndStops() {
        GameServer.Session computerSession = server.connect();
        Player computer = new Player(new LocalConnection(computerSession));
        Player bob = new Player(new LocalConnection(server.connect()));
        computer.send(new Intent.Join("alice"));
        bob.send(new Intent.Join("bob"));
        tick(computer, bob);
        GameCharacter alice = server.online(computer.id);
        alice.changeInventory(item, 7);
        computer.moveBy(1, 0);
        tick(computer, bob);
        assertEquals(1, alice.x);

        GameServer.Session phoneSession = server.connect();
        Player phone = new Player(new LocalConnection(phoneSession));
        phone.send(new Intent.Join("alice"));
        tick(computer, phone, bob);

        assertTrue(phone.inWorld(), "the phone's in");
        assertEquals(1, phone.x, "where she was");
        assertEquals(7, phone.inventory.get(phone.content.product(item.id)), "with what she had");
        assertTrue(computerSession.isClosed(), "the computer's logged out");
        assertNotNull(computer.loginRefused);
        assertTrue(computer.loginRefused.contains("logged in somewhere else"), computer.loginRefused);
        assertEquals(1, server.onlineCharacters().stream().filter(c -> c.name.equals("alice")).count(), "only one alice");
        assertTrue(bob.others.values().stream().anyMatch(e -> e.name().equals("alice")), "bob still sees her");

        // the phone plays on; the computer's moves do nothing now
        computer.moveBy(1, 0);
        phone.moveBy(0, 1);
        tick(computer, phone, bob);
        assertEquals(1, server.online(phone.id).x);
        assertEquals(1, server.online(phone.id).y);
    }

    @Test
    void savedWorlds_theNewLoginGetsWhatTheOldOneSaved(@TempDir Path dir) throws Exception {
        try (AccountStore accounts = new AccountStore(dir.resolve("accounts.db"));
             WorldStore store = new WorldStore(dir.resolve("world.db"), accounts)) {
            World world = store.loadOrCreateWorld(1, "w", 2);
            GameServer saved = new GameServer(world, store);
            saved.setDiscovery(Discovery.off());
            saved.applyContent(WorldGenerator.newSkills(world, 1));
            Product thing = world.content.skills.get(0).productLines.get(0).recipes.get(0).product;
            GameServer.Session first = saved.connect();
            Player computer = new Player(new LocalConnection(first));
            computer.send(new Intent.Join("alice"));
            saved.tick();
            computer.receive();
            saved.online(computer.id).changeInventory(thing, 4);
            computer.moveBy(0, 1);
            saved.tick();
            computer.receive();

            Player phone = new Player(new LocalConnection(saved.connect()));
            phone.send(new Intent.Join("alice"));
            saved.tick();
            phone.receive();
            assertTrue(first.isClosed());
            assertTrue(phone.inWorld());
            assertEquals(1, phone.y, "saved on the way out, loaded on the way in");
            assertEquals(4, phone.inventory.get(phone.content.product(thing.id)));
            assertEquals(1, saved.onlineCharacters().stream().filter(c -> c.name.equals("alice")).count());
        }
    }
}
