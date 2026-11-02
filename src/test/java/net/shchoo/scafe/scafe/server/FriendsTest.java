package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.client.GameLog;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.generation.WorldGenerator;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.persistence.AccountStore;
import net.shchoo.scafe.scafe.persistence.WorldStore;
import net.shchoo.scafe.scafe.protocol.Event;
import net.shchoo.scafe.scafe.protocol.Intent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class FriendsTest {
    private final List<String> log = new ArrayList<>();
    private final Consumer<String> listener = log::add;
    private final List<Event> aliceHeard = new ArrayList<>(), bobHeard = new ArrayList<>();
    private GameServer server;
    private Player alice, bob;
    private GameCharacter aliceC, bobC;

    @BeforeEach
    void setUp() {
        GameLog.subscribe(listener);
        World world = new World(1, "w", 7);
        server = new GameServer(world);
        server.setDiscovery(Discovery.off());
        server.applyContent(WorldGenerator.newSkills(world, 1));
        alice = new Player(new LocalConnection(server.connect()));
        bob = new Player(new LocalConnection(server.connect()));
        alice.send(new Intent.Join("alice"));
        bob.send(new Intent.Join("bob"));
        tick();
        aliceC = server.online(alice.id);
        bobC = server.online(bob.id);
        aliceC.outboxTap(aliceHeard::add);
        bobC.outboxTap(bobHeard::add);
    }

    @AfterEach
    void tearDown() {
        GameLog.unsubscribe(listener);
    }

    private void tick() {
        server.tick();
        alice.receive();
        bob.receive();
    }

    private Event.FriendInfo lastAbout(List<Event> heard, String name) {
        Event.FriendInfo found = null;
        for (Event event : heard) {
            if (event instanceof Event.Friends friends) {
                found = friends.friends().stream().filter(f -> f.name().equals(name)).findFirst().orElse(null);
            }
        }
        return found;
    }

    @Test
    void friendingSomeonePlaying_theListHasTheirLevelsAndWhereTheyAre() {
        alice.send(new Intent.AddFriend("bob"));
        tick();
        assertTrue(aliceC.friends.contains("bob"));
        Event.FriendInfo info = lastAbout(aliceHeard, "bob");
        assertTrue(info.online());
        assertEquals(bobC.position(), info.position());
        assertEquals(1, info.combatLevel());
        assertEquals(server.world.content.skills.size(), info.totalLevel(), "Lv. 1 in everything");
        assertEquals(1, info.levels().get(server.world.content.craftingSkills().get(0).id));
        assertFalse(bobC.friends.contains("alice"), "one way");

        alice.send(new Intent.RemoveFriend("bob"));
        tick();
        assertTrue(aliceC.friends.isEmpty());
    }

    @Test
    void notYourselfNorSomeoneWhoIsntPlaying() {
        alice.send(new Intent.AddFriend("alice"));
        alice.send(new Intent.AddFriend("nobody"));
        tick();
        assertTrue(aliceC.friends.isEmpty());
    }

    @Test
    void aWhisperReachesBothEnds_andOnlyThem() {
        alice.send(new Intent.AddFriend("bob"));
        alice.send(new Intent.Whisper("bob", "psst"));
        tick();
        Event.Whisper expected = new Event.Whisper("alice", "bob", aliceC.colorIndex, "psst");
        assertTrue(aliceHeard.contains(expected));
        assertTrue(bobHeard.contains(expected));
    }

    @Test
    void noWhisperingStrangers_norFriendsWhoveGone() {
        alice.send(new Intent.Whisper("bob", "psst"));
        tick();
        assertTrue(bobHeard.stream().noneMatch(e -> e instanceof Event.Whisper), "not a friend");
        alice.send(new Intent.AddFriend("bob"));
        tick();
        bob.send(new Intent.Leave());
        tick();
        alice.send(new Intent.Whisper("bob", "psst"));
        tick();
        assertTrue(log.contains("bob isn't online."), log.toString());
    }

    @Test
    void aFriendWhosLoggedOut_showsAsLastSeen_withNoPosition() {
        alice.send(new Intent.AddFriend("bob"));
        tick();
        bob.send(new Intent.Leave());
        for (int i = 0; i < 10; i++) {
            tick();
        }
        Event.FriendInfo info = lastAbout(aliceHeard, "bob");
        assertFalse(info.online());
        assertNull(info.position());
        assertEquals(server.world.content.skills.size(), info.totalLevel());
    }

    @Test
    void friendsAreSaved_andSomeoneLoggedOutIsFoundByName(@TempDir Path dir) throws Exception {
        try (AccountStore accounts = new AccountStore(dir.resolve("accounts.db"));
             WorldStore store = new WorldStore(dir.resolve("world.db"), accounts)) {
            store.loadOrCreateWorld(1, "w", 3);
            GameCharacter saved = store.loadOrCreateCharacter("carol");
            saved.friends.add("dave");
            saved.friends.add("erin");
            store.saveCharacter(saved);
            assertEquals(List.of("dave", "erin"), new ArrayList<>(store.loadCharacterNamed("carol").friends));
            assertNull(store.loadCharacterNamed("nobody"));
        }
    }
}
