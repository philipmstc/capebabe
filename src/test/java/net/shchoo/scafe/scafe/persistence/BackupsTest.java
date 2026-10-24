package net.shchoo.scafe.scafe.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class BackupsTest {
    @TempDir
    Path dir;

    private Path database(String name, int rows) throws Exception {
        Path file = dir.resolve(name);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement s = c.createStatement()) {
            s.execute("create table t (n integer)");
            for (int i = 0; i < rows; i++) {
                s.execute("insert into t values (" + i + ")");
            }
        }
        return file;
    }

    private static int rows(Path file) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select count(*) from t")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private List<Path> copies(Path folder, String prefix) throws Exception {
        try (Stream<Path> listing = Files.list(folder)) {
            return listing.filter(p -> p.getFileName().toString().startsWith(prefix)).sorted().toList();
        }
    }

    @Test
    void copiesEveryDatabaseWhileItsOpen() throws Exception {
        Path world = database("world-1.db", 5);
        Path accounts = database("accounts.db", 2);
        Path missing = dir.resolve("world-2.db");
        try (Connection open = DriverManager.getConnection("jdbc:sqlite:" + world)) {
            open.setAutoCommit(false);
            open.createStatement().execute("insert into t values (99)");
            List<Path> made = new Backups(dir.resolve("backups"), 3, List.of(world, accounts, missing)).runNow();
            assertEquals(2, made.size(), "a world that doesn't exist yet is skipped");
            assertEquals(5, rows(made.get(0)), "the last committed state, not the half-done transaction");
            assertEquals(2, rows(made.get(1)));
            open.rollback();
        }
    }

    @Test
    void keepsOnlyTheNewest() throws Exception {
        Path world = database("world-1.db", 1);
        Path folder = dir.resolve("backups");
        Files.createDirectories(folder);
        for (String old : List.of("2026-10-01_000000", "2026-10-02_000000", "2026-10-03_000000")) {
            Files.writeString(folder.resolve("world-1-" + old + ".db"), "old");
        }
        Files.writeString(folder.resolve("world-1-notes.db"), "not a backup");
        new Backups(folder, 2, List.of(world)).runNow();
        List<Path> left = copies(folder, "world-1-2");
        assertEquals(2, left.size(), left.toString());
        assertEquals("world-1-2026-10-03_000000.db", left.get(0).getFileName().toString(), "the newest old one stays");
        assertTrue(Files.exists(folder.resolve("world-1-notes.db")), "only its own copies are touched");
    }
}
