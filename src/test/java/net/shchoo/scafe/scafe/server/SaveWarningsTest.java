package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.client.GameLog;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.model.ContentBatch;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.protocol.Intent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Saves that fail tell whoever's affected, once, and again when they work. */
class SaveWarningsTest {
    private final List<String> log = new ArrayList<>();
    private boolean charactersFail, contentFails, oracleFails;
    private GameServer server;
    private Player alice;

    @BeforeEach
    void setUp() {
        server = new GameServer(new World(1, "w", 3), new Storage() {
            @Override
            public void saveContent(ContentBatch batch, Grant grant) throws Exception {
                if (contentFails) {
                    throw new Exception("disk full");
                }
            }

            @Override
            public GameCharacter loadOrCreateCharacter(String accountName) {
                return new GameCharacter(1, accountName);
            }

            @Override
            public void saveCharacter(GameCharacter character) throws Exception {
                if (charactersFail) {
                    throw new Exception("FOREIGN KEY constraint failed");
                }
            }

            @Override
            public String passwordHash(String accountName) {
                return null;
            }

            @Override
            public void setPasswordHash(String accountName, String hash) {}

            @Override
            public void saveOracle(Oracle.Saved saved) throws Exception {
                if (oracleFails) {
                    throw new Exception("read-only");
                }
            }
        });
        server.setDiscovery(Discovery.off());
        GameLog.subscribe(log::add);
        alice = new Player(new LocalConnection(server.connect()));
        alice.send(new Intent.Join("alice"));
        tick();
    }

    private void tick() {
        server.tick();
        alice.receive();
    }

    private long warnings(String what) {
        return log.stream().filter(m -> m.startsWith("WARNING: couldn't save " + what)).count();
    }

    @Test
    void aCharacterThatCantBeSavedIsWarnedOnce_andToldWhenItWorksAgain() {
        charactersFail = true;
        for (int i = 0; i < GameServer.AUTOSAVE_TICKS * 2 + 1; i++) {
            tick();
        }
        assertEquals(1, warnings("your character"), "once, not every save");
        assertTrue(log.stream().anyMatch(m -> m.contains("FOREIGN KEY constraint failed") && m.contains("tell whoever hosts")));
        charactersFail = false;
        for (int i = 0; i < GameServer.AUTOSAVE_TICKS; i++) {
            tick();
        }
        assertTrue(log.contains("Your character is saving again."));
    }

    @Test
    void newContentThatCantBeSavedWarnsWhoFoundIt() {
        contentFails = true;
        alice.send(new Intent.Admin("1", List.of("1")));
        tick();
        assertEquals(1, log.stream().filter(m -> m.startsWith("WARNING: couldn't save something new")).count());
        assertTrue(server.world.content.craftingSkills().isEmpty(), "not applied: nothing to lose");
    }

    @Test
    void theWorldThatCantBeSavedWarnsEveryone() {
        oracleFails = true;
        for (int i = 0; i < GameServer.AUTOSAVE_TICKS * 2; i++) {
            tick();
        }
        assertEquals(1, warnings("the world"));
        oracleFails = false;
        for (int i = 0; i < GameServer.AUTOSAVE_TICKS; i++) {
            tick();
        }
        assertTrue(log.contains("The world is saving again."));
    }
}
