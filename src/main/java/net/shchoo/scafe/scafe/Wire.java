package net.shchoo.scafe.scafe;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

/**
 * Network encoding: one JSON object per line, {@code {"type": "Move", "data": {...}}}. Only the record types
 * permitted by {@link Intent} / {@link Event} can be decoded, so a client can't make us build anything else.
 */
public final class Wire {
    public static final int MAX_INTENT_CHARS = 64 * 1024;
    public static final int MAX_EVENT_CHARS = 64 * 1024 * 1024;

    private static final Gson GSON = new Gson();
    private static final Map<String, Class<?>> INTENTS = byName(Intent.class);
    private static final Map<String, Class<?>> EVENTS = byName(Event.class);

    private Wire() {}

    private static Map<String, Class<?>> byName(Class<?> sealed) {
        Map<String, Class<?>> types = new HashMap<>();
        for (Class<?> type : sealed.getPermittedSubclasses()) {
            types.put(type.getSimpleName(), type);
        }
        return types;
    }

    public static String encode(Object message) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("type", message.getClass().getSimpleName());
        envelope.add("data", GSON.toJsonTree(message));
        return envelope.toString();
    }

    public static Intent decodeIntent(String line) {
        return (Intent) decode(line, INTENTS);
    }

    public static Event decodeEvent(String line) {
        return (Event) decode(line, EVENTS);
    }

    private static Object decode(String line, Map<String, Class<?>> types) {
        JsonObject envelope = JsonParser.parseString(line).getAsJsonObject();
        String type = envelope.get("type").getAsString();
        Class<?> cls = types.get(type);
        if (cls == null) {
            throw new IllegalArgumentException("Unknown message type " + type);
        }
        JsonElement data = envelope.get("data");
        return GSON.fromJson(data == null ? new JsonObject() : data, cls);
    }

    /** Reads up to and excluding the next newline; null at end of stream. Refuses lines longer than {@code max}. */
    public static String readLine(Reader in, int max) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                return line.toString();
            }
            if (line.length() >= max) {
                throw new IOException("Line too long (> " + max + " chars)");
            }
            line.append((char) c);
        }
        return line.length() == 0 ? null : line.toString();
    }
}
