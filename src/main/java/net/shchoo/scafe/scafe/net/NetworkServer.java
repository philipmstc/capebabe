package net.shchoo.scafe.scafe.net;

import net.shchoo.scafe.scafe.protocol.Event;
import net.shchoo.scafe.scafe.protocol.Wire;
import net.shchoo.scafe.scafe.server.GameServer;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

/** Accepts TCP clients and bridges each one to a {@link GameServer.Session} (JSON lines, see {@link Wire}). */
public class NetworkServer implements AutoCloseable {
    public static final int DEFAULT_PORT = 43594;
    private static final long WRITE_POLL_MILLIS = 15;

    private final GameServer game;
    private final ServerSocket socket;
    private final boolean remoteAdmin;
    private volatile boolean running = true;

    /** remoteAdmin: whether network players may use admin (world-building) commands. */
    public NetworkServer(GameServer game, int port, boolean remoteAdmin) throws IOException {
        this.game = game;
        this.socket = new ServerSocket(port);
        this.remoteAdmin = remoteAdmin;
    }

    public int port() {
        return socket.getLocalPort();
    }

    /** Accepts clients until closed. Blocks. */
    public void acceptForever() {
        while (running) {
            try {
                Socket client = socket.accept();
                client.setTcpNoDelay(true);
                serve(client);
            } catch (SocketException e) {
                if (running) {
                    e.printStackTrace();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void startInBackground() {
        Thread thread = new Thread(this::acceptForever, "network-accept");
        thread.setDaemon(true);
        thread.start();
    }

    private void serve(Socket client) throws IOException {
        GameServer.Session session = game.connect(remoteAdmin);
        Reader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        Writer out = new BufferedWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8));
        String who = String.valueOf(client.getRemoteSocketAddress());

        Thread reader = new Thread(() -> {
            try {
                String line;
                while ((line = Wire.readLine(in, Wire.MAX_INTENT_CHARS)) != null) {
                    if (!line.isBlank()) {
                        session.send(Wire.decodeIntent(line));
                    }
                }
            } catch (IOException | RuntimeException e) {
                System.err.println("Dropping " + who + ": " + e.getMessage());
            } finally {
                session.close();
                closeQuietly(client);
            }
        }, "net-in-" + who);

        Thread writer = new Thread(() -> {
            try {
                while (!client.isClosed()) {
                    boolean wrote = false;
                    for (Event event : session.drain()) {
                        out.write(Wire.encode(event));
                        out.write('\n');
                        wrote = true;
                    }
                    if (wrote) {
                        out.flush();
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
