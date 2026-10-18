package net.shchoo.scafe.scafe.protocol;

import java.util.List;

/** The client's line to a server: in-process ({@link LocalConnection}) now, over the network later. */
public interface GameConnection {
    void send(Intent intent);

    /** Events received since the last call, oldest first. */
    List<Event> poll();

    void close();
}
