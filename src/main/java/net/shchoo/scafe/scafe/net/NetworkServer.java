package net.shchoo.scafe.scafe.net;

import net.shchoo.scafe.scafe.protocol.Event;
import net.shchoo.scafe.scafe.protocol.Wire;
import net.shchoo.scafe.scafe.server.GameServer;

import javax.net.ssl.SSLServerSocket;
import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Accepts clients over TLS ({@link Tls}) and bridges each one to a {@link GameServer.Session} (JSON lines, see
 * {@link Wire}), within {@link Limits}: connections per address and in total, time to log in, messages per second.
 */
public class NetworkServer implements AutoCloseable {
    public static final int DEFAULT_PORT = 43594;
    private static final long WRITE_POLL_MILLIS = 15;
    private static final long ACCEPT_RETRY_MILLIS = 250;

    /**
     * {@code perAddress}: connections from one IP at once (friends sharing a house share an IP).
     * {@code loginMillis}: time to finish logging in before being dropped (idle players already in are fine).
     * {@code messagesPerSecond} / {@code burst}: messages a client may send (more are dropped; {@code floodLimit}
     * dropped ones in a row and it's disconnected).
     */
    public record Limits(int perAddress, int total, long loginMillis, double messagesPerSecond, int burst, int floodLimit) {
        public static Limits defaults() {
            return new Limits(1, 32, 5 * 60_000, 40, 80, 400);
        }

        public Limits withPerAddress(int perAddress) {
            return new Limits(perAddress, total, loginMillis, messagesPerSecond, burst, floodLimit);
        }
    }

    private final GameServer game;
    private final ServerSocket socket;
    private final boolean remoteAdmin;
    private final Limits limits;
    private final Map<String, Integer> connectionsByAddress = new HashMap<>();
    private int connections;
    private volatile boolean running = true;

    /** remoteAdmin: whether network players may use admin (world-building) commands. */
    public NetworkServer(GameServer game, int port, boolean remoteAdmin, Tls.Server tls, Limits limits) throws IOException {
        this(game, null, port, remoteAdmin, tls, limits);
    }

    /** {@code bind}: the one address to listen on (e.g. the Tailscale one), or null for every interface. */
    public NetworkServer(GameServer game, InetAddress bind, int port, boolean remoteAdmin, Tls.Server tls, Limits limits)
            throws IOException {
        this.game = game;
        SSLServerSocket secure = (SSLServerSocket) tls.context().getServerSocketFactory().createServerSocket(port, 50, bind);
        secure.setEnabledProtocols(new String[]{Tls.PROTOCOL});
        this.socket = secure;
        this.remoteAdmin = remoteAdmin;
        this.limits = limits;
    }

    public int port() {
        return socket.getLocalPort();
    }

    /** Where it's listening: an address, or "every interface". */
    public String listeningOn() {
        InetAddress address = socket.getInetAddress();
        return address == null || address.isAnyLocalAddress() ? "every interface" : address.getHostAddress();
    }

    /** Accepts clients until closed. Blocks. */
    public void acceptForever() {
        while (running) {
            try {
                Socket client = socket.accept();
                client.setTcpNoDelay(true);
                String address = client.getInetAddress().getHostAddress();
                String refusal = admit(address);
                if (refusal != null) {
                    refuse(client, refusal);
                } else {
                    serve(client, address);
                }
            } catch (SocketException e) {
                if (running) {
                    e.printStackTrace();
                    backOff();
                }
            } catch (IOException e) {
                e.printStackTrace();
                backOff();
            }
        }
    }

    /** After a failed accept: a moment's pause, so a socket that keeps failing can't spin a core (and flood the log). */
    private static void backOff() {
        try {
            Thread.sleep(ACCEPT_RETRY_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void startInBackground() {
        Thread thread = new Thread(this::acceptForever, "network-accept");
        thread.setDaemon(true);
        thread.start();
    }

    /** Counts the connection in, or says why not. */
    private synchronized String admit(String address) {
        if (connections >= limits.total()) {
            return "The server is full. Try again later.";
        }
        int fromHere = connectionsByAddress.getOrDefault(address, 0);
        if (fromHere >= limits.perAddress()) {
            return "There's already someone connected from your address (one connection each).";
        }
        connectionsByAddress.put(address, fromHere + 1);
        connections++;
        return null;
    }

    private synchronized void release(String address) {
        connectionsByAddress.computeIfPresent(address, (a, n) -> n <= 1 ? null : n - 1);
        connections--;
    }

    /** Tells a turned-away client why (over TLS, on its own thread so a slow handshake can't hold up others). */
    private void refuse(Socket client, String reason) {
        Thread thread = new Thread(() -> {
            try {
                client.setSoTimeout(5_000);
                Writer out = new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8);
                out.write(Wire.encode(new Event.LoginRefused(reason)));
                out.write('\n');
                out.flush();
            } catch (IOException ignored) {
                // they'll just see the connection close
            } finally {
                closeQuietly(client);
            }
        }, "net-refuse");
        thread.setDaemon(true);
        thread.start();
    }

    private void serve(Socket client, String address) throws IOException {
        GameServer.Session session = game.connectRemote(remoteAdmin, address);
        String who = String.valueOf(client.getRemoteSocketAddress());
        // the TLS handshake and logging in both have to happen within this, or the connection is dropped
        client.setSoTimeout((int) limits.loginMillis());

        Thread reader = new Thread(() -> {
            try {
                Reader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                double tokens = limits.burst();
                long last = System.nanoTime();
                int droppedInARow = 0;
                while (true) {
                    String line;
                    try {
                        line = Wire.readLine(in, Wire.MAX_INTENT_CHARS);
                    } catch (SocketTimeoutException e) {
                        if (!session.inWorld()) {
                            throw new IOException("didn't log in within " + limits.loginMillis() / 1000 + "s");
                        }
                        client.setSoTimeout(0);
                        continue;
                    }
                    if (line == null) {
                        break;
                    }
                    if (session.inWorld()) {
                        client.setSoTimeout(0);
                    }
                    long now = System.nanoTime();
                    tokens = Math.min(limits.burst(), tokens + (now - last) / 1e9 * limits.messagesPerSecond());
                    last = now;
                    if (tokens < 1) {
                        if (++droppedInARow > limits.floodLimit()) {
                            throw new IOException("sending too fast");
                        }
                        continue;
                    }
                    tokens--;
                    droppedInARow = 0;
                    if (!line.isBlank()) {
                        session.send(Wire.decodeIntent(line));
                    }
                }
            } catch (IOException | RuntimeException e) {
                System.err.println("Dropping " + who + ": " + e.getMessage());
            } finally {
                session.close();
                closeQuietly(client);
                release(address);
            }
        }, "net-in-" + who);

        Thread writer = new Thread(() -> {
            try {
                Writer out = new BufferedWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8));
                while (!client.isClosed()) {
                    boolean wrote = false;
                    for (Event event : session.drain()) {
                        out.write(Wire.encode(event));
                        out.write('\n');
                        wrote = true;
                    }
                    if (wrote) {
                        out.flush();
                    } else if (session.isClosed()) {
                        // the server ended it (e.g. refused a login) and everything it said has been sent
                        closeQuietly(client);
                    } else {
                        Thread.sleep(WRITE_POLL_MILLIS);
                    }
                }
            } catch (IOException | InterruptedException e) {
                session.close();
                closeQuietly(client);
            }
        }, "net-out-" + who);

        reader.setDaemon(true);
        writer.setDaemon(true);
        reader.start();
        writer.start();
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // already gone
        }
    }

    @Override
    public void close() throws IOException {
        running = false;
        socket.close();
    }
}
