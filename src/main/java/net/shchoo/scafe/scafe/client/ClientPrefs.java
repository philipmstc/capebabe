package net.shchoo.scafe.scafe.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * What the login screen fills in next time: the last server and name you got in with
 * ({@code <data dir>/client.properties}). Never passwords.
 */
public record ClientPrefs(String server, String name) {
    private static final String FILE = "client.properties";

    public static ClientPrefs load(Path dataDir) {
        Path file = dataDir.resolve(FILE);
        Properties props = new Properties();
        if (Files.exists(file)) {
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(in);
            } catch (IOException e) {
                // start blank
            }
        }
        return new ClientPrefs(props.getProperty("server"), props.getProperty("name"));
    }

    /** Best effort: forgetting the last server isn't worth stopping the game for. */
    public void save(Path dataDir, String server, String name) {
        Properties props = new Properties();
        props.setProperty("server", server == null ? "" : server);
        if (name != null) {
            props.setProperty("name", name);
        }
        try {
            Files.createDirectories(dataDir);
            try (Writer out = Files.newBufferedWriter(dataDir.resolve(FILE), StandardCharsets.UTF_8)) {
                props.store(out, "capebabe login screen: last server and name");
            }
        } catch (IOException ignored) {
            // next time it just starts blank
        }
    }
}
