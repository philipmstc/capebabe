package net.shchoo.scafe.scafe;

import java.util.List;

/** A connection to a {@link GameServer} running in the same JVM (single player). */
public class LocalConnection implements GameConnection {
    private final GameServer.Session session;

    public LocalConnection(GameServer.Session session) {
        this.session = session;
    }

    @Override
    public void send(Intent intent) {
        session.send(intent);
    }

    @Override
    public List<Event> poll() {
        return session.drain();
    }

    @Override
    public void close() {
        session.close();
    }
}
