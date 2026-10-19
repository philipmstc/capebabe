package net.shchoo.scafe.scafe.net;

import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.generation.WorldGenerator;
import net.shchoo.scafe.scafe.model.ContentBatch;
import net.shchoo.scafe.scafe.model.Direction;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.protocol.Event;
import net.shchoo.scafe.scafe.protocol.Intent;
import net.shchoo.scafe.scafe.protocol.Wire;
import net.shchoo.scafe.scafe.server.Discovery;
import net.shchoo.scafe.scafe.server.GameServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class NetworkTest {
    private NetworkServer network;

    @AfterEach
    void tearDown() throws IOException {
        if (network != null) {
            network.close();
        }
    }

    @Test
    void everyIntentRoundTrips() {
        List<Intent> intents = List.of(
                new Intent.Join("alice"), new Intent.Login(Intent.PROTOCOL_VERSION, "alice", "s3cret!"),
                new Intent.EnterWorld("open sesame"), new Intent.Leave(), new Intent.Move(1, -1),
                new Intent.WalkTo(new Position(-4, 7)), new Intent.Perform(), new Intent.AddGoal(), new Intent.Attack(), new Intent.SetColor(3), new Intent.SetRunning(true),
                new Intent.Admin("3", List.of("1", "5", "10")));
        for (Intent intent : intents) {
            assertEquals(intent, Wire.decodeIntent(Wire.encode(intent)));
        }
    }

    @Test
    void eventsRoundTripIncludingAWholeWorld() {
        World world = new World(1, "w", 3);
        world.content.apply(WorldGenerator.newSkills(world, 5));
        world.content.apply(WorldGenerator.newNode(world, new Position(1, 2), 2, 3, 4));
        world.content.apply(WorldGenerator.newMonsterType(world));
        world.content.apply(WorldGenerator.newDrop(world, world.content.monsters.get(0).id));
        Event.CharacterSnapshot you = new Event.CharacterSnapshot(7, "alice", new Position(1, 2),
                Map.of(1, 5000L), Map.of(3, 2), List.of(new Event.GoalDef(1, 20, 3)), Set.of(9), null, 8, 10, Direction.LEFT, 3, true, true);
        List<Event> events = List.of(
                new Event.Welcome("w", 12, List.copyOf(world.content.history()), you,
                        List.of(Event.EntityInfo.player(8, "bob", new Position(0, 0), Direction.UP, 4, 10, 10, 5),
                                Event.EntityInfo.monster(-3, "monster0", new Position(2, 2), 77, 3, 9)),
                        List.of(new Event.NodeStatus(9, 0, true, 1))),
                new Event.Tick(13), new Event.ActionStopped(), new Event.Message("hi \"there\"\n"),
                new Event.XpGained(1, 200, 5200, new Position(1, 2)),
                new Event.ContentAdded(world.content.history().get(0).withOrigin(new ContentBatch.Origin("alice", 1760000000000L))),
                new Event.ContentAdded(world.content.history().get(world.content.version() - 1)),
                new Event.FightStarted(-3), new Event.FightEnded(), new Event.Hit(-3, 2, new Position(2, 2)),
                new Event.HpChanged(4, 10),
                new Event.EntityMoved(8, new Position(1, 0), Direction.RIGHT), new Event.StepsCleared(new Position(0, 0), Direction.DOWN),
                new Event.ColorChanged(2), new Event.RunningChanged(true),
                new Event.LoginAccepted(true, false), new Event.LoginRefused("Wrong world password."));
        for (Event event : events) {
            assertEquals(event, Wire.decodeEvent(Wire.encode(event)));
        }
    }

    @Test
    void onlyKnownTypesDecode() {
        assertThrows(IllegalArgumentException.class, () -> Wire.decodeIntent("{\"type\":\"Welcome\",\"data\":{}}"));
        assertThrows(IllegalArgumentException.class, () -> Wire.decodeIntent("{\"type\":\"java.lang.Runtime\",\"data\":{}}"));
    }

    @Test
    void longLinesAreRefused() {
        String huge = "x".repeat(100) + "\n";
        assertThrows(IOException.class, () -> Wire.readLine(new StringReader(huge), 50));
    }

    @Test
    void twoPlayersOverTcp() throws Exception {
        GameServer server = new GameServer(new World(1, "net", 8));
        server.setDiscovery(Discovery.off());
        network = new NetworkServer(server, 0, true);
        network.startInBackground();
        Player alice = new Player(new SocketConnection("localhost", network.port()));
        Player bob = new Player(new SocketConnection("localhost", network.port()));
        logIn(alice, "alice");
        logIn(bob, "bob");
        tickUntil(server, () -> alice.joined && bob.joined && alice.others.containsKey(bob.id), alice, bob);

        alice.send(new Intent.Admin("1", List.of("3")));
        tickUntil(server, () -> bob.content.skills.size() == 3, alice, bob);
        assertEquals(server.world.content.history(), bob.content.history());
        assertEquals(Direction.DOWN, alice.others.get(bob.id).facing());

        bob.moveBy(-1, 0);
        tickUntil(server, () -> alice.others.get(bob.id).position().equals(new Position(-1, 0)), alice, bob);
        assertEquals(Direction.LEFT, alice.others.get(bob.id).facing(), "others see which way you face");

        bob.send(new Intent.Leave());
        tickUntil(server, () -> !alice.others.containsKey(bob.id), alice, bob);
    }

    @Test
    void remotePlayersCanBeDeniedAdmin() throws Exception {
        GameServer server = new GameServer(new World(1, "net", 8));
        server.setDiscovery(Discovery.off());
        network = new NetworkServer(server, 0, false);
        network.startInBackground();
        Player alice = new Player(new SocketConnection("localhost", network.port()));
        logIn(alice, "alice");
        tickUntil(server, () -> alice.joined, alice);
        alice.send(new Intent.Admin("1", List.of("3")));
        for (int i = 0; i < 5; i++) {
            server.tick();
            Thread.sleep(20);
            alice.receive();
        }
        assertTrue(server.world.content.skills.isEmpty());
    }

    private static void tickUntil(GameServer server, BooleanSupplier done, Player... players) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            server.tick();
            Thread.sleep(20);
            for (Player player : players) {
                player.receive();
            }
            if (done.getAsBoolean()) {
                return;
            }
        }
        fail("timed out");
    }

    /** No world password on these servers: log in, then go straight in. */
    private static void logIn(Player player, String name) {
        player.login(name, "password-" + name);
        player.enterWorld(null);
    }

    private GameServer passwordedServer() throws Exception {
        GameServer server = new GameServer(new World(1, "net", 8));
        server.setDiscovery(Discovery.off());
        server.setWorldPassword("open sesame");
        network = new NetworkServer(server, 0, false);
        network.startInBackground();
        return server;
    }

    private Player connect() throws Exception {
        return new Player(new SocketConnection("localhost", network.port()));
    }

    @Test
    void theWorldPasswordIsAskedForAndChecked() throws Exception {
        GameServer server = passwordedServer();
        Player alice = connect();
        alice.login("alice", "hunter22");
        tickUntil(server, () -> alice.loginAccepted != null, alice);
        assertTrue(alice.loginAccepted.worldPasswordNeeded());
        assertTrue(alice.loginAccepted.newAccount());

        alice.enterWorld("open says me");
        tickUntil(server, () -> alice.loginRefused != null, alice);
        assertEquals("Wrong world password.", alice.loginRefused);
        assertFalse(alice.inWorld());

        alice.enterWorld("open sesame");
        tickUntil(server, alice::inWorld, alice);
        alice.send(new Intent.Leave());
        tickUntil(server, () -> server.onlineCount() == 0, alice);

        Player again = connect();
        again.login("alice", "wrong");
        tickUntil(server, () -> again.loginRefused != null, again);
        assertEquals("Wrong name or password.", again.loginRefused, "the account's password was set on the way in");
        again.login("alice", "hunter22");
        tickUntil(server, () -> again.loginAccepted != null, again);
        assertFalse(again.loginAccepted.newAccount());
    }

    @Test
    void aNewNameIsntSavedUntilTheWorldPasswordPasses() throws Exception {
        GameServer server = passwordedServer();
        Player stranger = connect();
        stranger.login("phili", "mine-now");
        tickUntil(server, () -> stranger.loginAccepted != null, stranger);
        stranger.enterWorld("guess");
        tickUntil(server, () -> stranger.loginRefused != null, stranger);

        Player owner = connect();
        owner.login("phili", "the-real-one");
        owner.enterWorld("open sesame");
        tickUntil(server, owner::inWorld, owner);
    }

    @Test
    void oldClientsAndOtherVersionsAreToldToUpdate() throws Exception {
        GameServer server = passwordedServer();
        Player old = connect();
        old.send(new Intent.Join("alice"));
        tickUntil(server, () -> old.loginRefused != null, old);
        assertTrue(old.loginRefused.contains("newer version"));

        Player newer = connect();
        newer.send(new Intent.Login(Intent.PROTOCOL_VERSION + 1, "bob", "password"));
        tickUntil(server, () -> newer.loginRefused != null, newer);
        assertTrue(newer.loginRefused.contains("version " + (Intent.PROTOCOL_VERSION + 1)), newer.loginRefused);
        assertTrue(newer.loginRefused.contains("server needs updating"), newer.loginRefused);
    }

    @Test
    void tooManyWrongPasswordsLocksTheAddressOut() throws Exception {
        GameServer server = passwordedServer();
        Player alice = connect();
        alice.login("alice", "hunter22");
        tickUntil(server, () -> alice.loginAccepted != null, alice);
        for (int i = 0; i < GameServer.MAX_FAILED_LOGINS; i++) {
            alice.enterWorld("guess " + i);
        }
        tickUntil(server, () -> "Too many failed logins. Try again in a few minutes.".equals(alice.loginRefused), alice);

        Player later = connect();
        later.login("bob", "password");
        tickUntil(server, () -> later.loginRefused != null, later);
        assertTrue(later.loginRefused.startsWith("Too many failed logins"), "the whole address is locked out for a while");
    }
}
