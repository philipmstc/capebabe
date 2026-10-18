package net.shchoo.scafe.scafe.net;

import net.shchoo.scafe.scafe.protocol.Event;
import net.shchoo.scafe.scafe.protocol.GameConnection;
import net.shchoo.scafe.scafe.protocol.Intent;
import net.shchoo.scafe.scafe.protocol.Wire;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/** A connection to a {@link NetworkServer} over TCP. */
public class SocketConnection implements GameConnection {
    private final Socket socket;
    private final Writer out;
    private final Queue<Event> received = new ConcurrentLinkedQueue<>();

    public SocketConnection(String host, int port) throws IOException {
        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
        out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        Reader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        Thread reader = new Thread(() -> {
            try {
                String line;
                while ((line = Wire.readLine(in, Wire.MAX_EVENT_CHARS)) != null) {
                    if (!line.isBlank()) {
                        received.add(Wire.decodeEvent(line));
                    }
                }
                received.add(new Event.Message("Disconnected from server."));
            } catch (IOException | RuntimeException e) {
                received.add(new Event.Message("Disconnected from server: " + e.getMessage()));
            }
        }, "net-in");
        reader.setDaemon(true);
        reader.start();
    }

    /** "host" or "host:port". */
    public static SocketConnection connect(String address) throws IOException {
        int colon = address.lastIndexOf(':');
        if (colon < 0) {
            return new SocketConnection(address, NetworkServer.DEFAULT_PORT);
        }
        return new SocketConnection(address.substring(0, colon), Integer.parseInt(address.substring(colon + 1)));
    }

    @Override
    public synchronized void send(Intent intent) {
        try {
            out.write(Wire.encode(intent));
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            received.add(new Event.Message("Couldn't reach the server: " + e.getMessage()));
        }
    }

    @Override
    public List<Event> poll() {
        List<Event> events = new ArrayList<>();
        Event event;
        while ((event = received.poll()) != null) {
            events.add(event);
        }
        return events;
    }

    @Override
    public void close() {
        send(new Intent.Leave());
        try {
            socket.close();
        } catch (IOException ignored) {
            // already gone
        }
    }
}
