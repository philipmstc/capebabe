package net.shchoo.scafe.scafe.client;

import net.shchoo.scafe.scafe.protocol.Event;

/** One line of the chat / log: what kind it is (for Compact chat), and for chat, who said it in which colour. */
public record LogEntry(Event.MessageKind kind, String text, String speaker, int speakerColor) {

    public static LogEntry of(Event.MessageKind kind, String text) {
        return new LogEntry(kind, text, null, 0);
    }

    public static LogEntry chat(String speaker, int speakerColor, String text) {
        return new LogEntry(Event.MessageKind.CHAT, text, speaker, speakerColor);
    }

    /** As plain text: "speaker: text" for chat. */
    public String display() {
        return speaker == null ? text : speaker + ": " + text;
    }
}
