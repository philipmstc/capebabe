package net.shchoo.scafe.scafe.client;

import net.shchoo.scafe.scafe.server.LocalConnection;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.server.GameServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DisplaySettingsTest {
    @TempDir
    Path data;

    @Test
    void contrastTiersOnTheRestOffUntilSaved() {
        assertEquals(new DisplaySettings(true, false, false, false, ItemSort.AS_GOT), DisplaySettings.load(data));
    }

    @Test
    void savedHereAndReadBackForAnyCharacter() {
        Player alice = new Player(new LocalConnection(new GameServer(new World(1, "w", 0)).connect()));
        alice.onDisplaySettingsChanged = () -> DisplaySettings.of(alice).save(data);
        alice.toggleShowProductTiers();
        alice.toggleLightMode();
        alice.stepItemSort(2);
        assertTrue(Files.exists(data.resolve(DisplaySettings.FILE)), "saved as soon as it changes");

        Player bob = new Player(new LocalConnection(new GameServer(new World(2, "x", 0)).connect()));
        DisplaySettings.load(data).applyTo(bob);
        assertTrue(bob.showProductTiers);
        assertTrue(bob.lightMode);
        assertFalse(bob.compactChat);
        assertFalse(bob.showTimeEstimate);
        assertEquals(ItemSort.TYPE, bob.itemSort, "the sort too");
    }
}
