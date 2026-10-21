package net.shchoo.scafe.scafe.net;

import net.shchoo.scafe.scafe.protocol.Event;
import net.shchoo.scafe.scafe.protocol.GameConnection;
import net.shchoo.scafe.scafe.protocol.Intent;
import net.shchoo.scafe.scafe.protocol.Wire;

import javax.net.ssl.SSLSocket;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/** A connection to a {@link NetworkServer}, over TLS ({@link Tls}). */
public class SocketConnection implements GameConnection {
    private static final int HANDSHAKE_TIMEOUT_MILLIS = 15_000;

    private final SSLSocket socket;
    private final Writer out;
    private final Queue<Event> received = new ConcurrentLinkedQueue<>();
    private volatile boolean open = true;

    /** Connects and finishes the TLS handshake (so an untrusted server fails here, with the reason). */
    public SocketConnection(String host, int port, Tls.Client tls) throws IOException {
        socket = (SSLSocket) tls.context().getSocketFactory().createSocket(host, port);
        socket.setEnabledProtocols(new String[]{Tls.PROTOCOL});
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);
        try {
            socket.startHandshake();
        } catch (IOException e) {
            closeQuietly();
            throw e;
        }
        socket.setSoTimeout(0);
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
            } finally {
                open = false;
            }
        }, "net-in");
        reader.setDaemon(true);
        reader.start();
    }

    /** "host" or "host:port", as "host:port" (the key the client remembers a server's fingerprint under). */
    public static String normalize(String address) {
        return address.lastIndexOf(':') < 0 ? address + ":" + NetworkServer.DEFAULT_PORT : address;
    }

    /** "host" or "host:port". */
    public static SocketConnection connect(String address, Tls.Client tls) throws IOException {
        String hostPort = normalize(address);
        int colon = hostPort.lastIndexOf(':');
        return new SocketConnection(hostPort.substring(0, colon), Integer.parseInt(hostPort.substring(colon + 1)), tls);
    }

    private void closeQuietly() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // already gone
        }
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
    public boolean isOpen() {
        return open;
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
