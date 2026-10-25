package net.shchoo.scafe.scafe.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The settings only the screen cares about, kept on this computer ({@code <data dir>/settings.properties}) and
 * shared by all your characters. The ones the server needs (color, running, auto-investigate) stay on it.
 */
public record DisplaySettings(boolean showProductTiers, boolean showTimeEstimate, boolean compactChat, boolean lightMode) {
    static final String FILE = "settings.properties";

    public static DisplaySettings of(Player player) {
        return new DisplaySettings(player.showProductTiers, player.showTimeEstimate, player.compactChat, player.lightMode);
    }

    /** All off if there's no file yet (or it can't be read). */
    public static DisplaySettings load(Path dataDir) {
        Path file = dataDir.resolve(FILE);
        Properties props = new Properties();
        if (Files.exists(file)) {
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(in);
            } catch (IOException e) {
                // defaults
            }
        }
        return new DisplaySettings(on(props, "contrast-tiers"), on(props, "time-to-goal"), on(props, "compact-chat"),
                on(props, "light-mode"));
    }

    private static boolean on(Properties props, String key) {
        return Boolean.parseBoolean(props.getProperty(key, "false"));
    }

    public void applyTo(Player player) {
        player.showProductTiers = showProductTiers;
        player.showTimeEstimate = showTimeEstimate;
        player.compactChat = compactChat;
        player.lightMode = lightMode;
    }

    /** Best effort: losing a display setting isn't worth stopping the game for. */
    public void save(Path dataDir) {
        Properties props = new Properties();
        props.setProperty("contrast-tiers", String.valueOf(showProductTiers));
        props.setProperty("time-to-goal", String.valueOf(showTimeEstimate));
        props.setProperty("compact-chat", String.valueOf(compactChat));
        props.setProperty("light-mode", String.valueOf(lightMode));
        try {
            Files.createDirectories(dataDir);
            try (Writer out = Files.newBufferedWriter(dataDir.resolve(FILE), StandardCharsets.UTF_8)) {
                props.store(out, "capebabe display settings (all characters)");
            }
        } catch (IOException ignored) {
            // they're just not remembered
        }
    }
}
