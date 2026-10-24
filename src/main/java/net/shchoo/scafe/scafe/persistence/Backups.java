package net.shchoo.scafe.scafe.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Copies of the databases (accounts, the world) into a folder, made safely while the game is running: each with
 * SQLite's {@code VACUUM INTO} on a connection of its own, which reads one consistent snapshot without stopping
 * anyone. Named {@code world-1-2026-10-24_0300.db}; only the newest {@code keep} of each are kept.
 */
public class Backups {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");

    private final Path folder;
    private final int keep;
    private final List<Path> files;

    public Backups(Path folder, int keep, List<Path> files) {
        this.folder = folder;
        this.keep = Math.max(1, keep);
        this.files = List.copyOf(files);
    }

    /** Backs up every file that exists now; says what went wrong instead of throwing (a failed backup mustn't stop the game). */
    public List<Path> runNow() {
        List<Path> made = new ArrayList<>();
        String stamp = LocalDateTime.now().format(STAMP);
        for (Path file : files) {
            if (!Files.exists(file)) {
                continue;
            }
            try {
                Files.createDirectories(folder);
                Path copy = folder.resolve(baseName(file) + "-" + stamp + ".db");
                copy(file, copy);
                made.add(copy);
                prune(file);
            } catch (IOException | SQLException e) {
                System.err.println("Couldn't back up " + file + ": " + e.getMessage());
            }
        }
        if (!made.isEmpty()) {
            System.out.println("Backed up " + made.size() + " file(s) to " + folder.toAbsolutePath());
        }
        return made;
    }

    /** {@link #runNow} every {@code interval}, on a background thread, for as long as the game runs. */
    public void every(Duration interval) {
        Thread thread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(interval.toMillis());
                } catch (InterruptedException e) {
                    return;
                }
                runNow();
            }
        }, "backups");
        thread.setDaemon(true);
        thread.start();
    }

    static void copy(Path from, Path to) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + from.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("vacuum into '" + to.toAbsolutePath().toString().replace("'", "''") + "'");
        }
    }

    /** "world-1.db" -> "world-1". */
    private static String baseName(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".db") ? name.substring(0, name.length() - 3) : name;
    }

    /** Keeps the newest {@link #keep} copies of {@code file} (the names sort by time). */
    private void prune(Path file) throws IOException {
        String prefix = baseName(file) + "-";
        List<Path> copies;
        try (Stream<Path> listing = Files.list(folder)) {
            copies = listing.filter(p -> {
                String name = p.getFileName().toString();
                return name.startsWith(prefix) && name.endsWith(".db")
                        && name.substring(prefix.length()).matches("\\d{4}-\\d{2}-\\d{2}_\\d{6}\\.db");
            }).sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
        }
        for (int i = 0; i < copies.size() - keep; i++) {
            Files.deleteIfExists(copies.get(i));
        }
    }
}
