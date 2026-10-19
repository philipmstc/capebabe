package net.shchoo.scafe.scafe.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorldLockTest {
    @TempDir
    Path dir;

    @Test
    void aWorldCanOnlyBeOpenOnceAtATime() throws Exception {
        try (AccountStore accounts = new AccountStore(dir.resolve("accounts.db"))) {
            Path file = WorldStore.fileFor(dir, 1);
            WorldStore first = new WorldStore(file, accounts);
            WorldInUseException e = assertThrows(WorldInUseException.class, () -> new WorldStore(file, accounts));
            assertTrue(e.getMessage().contains("world-1.db"));
            try (WorldStore otherWorld = new WorldStore(WorldStore.fileFor(dir, 2), accounts)) {
                assertNotNull(otherWorld, "other worlds aren't affected");
            }
            first.close();
            try (WorldStore again = new WorldStore(file, accounts)) {
                assertNotNull(again, "free again once closed");
            }
        }
    }
}
