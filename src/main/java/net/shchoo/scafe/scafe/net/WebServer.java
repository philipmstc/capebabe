package net.shchoo.scafe.scafe.net;

import net.shchoo.scafe.scafe.client.SkillStyle;
import net.shchoo.scafe.scafe.model.Building;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.Skill;
import net.shchoo.scafe.scafe.model.SkillType;
import net.shchoo.scafe.scafe.model.Stacks;
import net.shchoo.scafe.scafe.model.Stat;
import net.shchoo.scafe.scafe.model.Tiers;
import net.shchoo.scafe.scafe.protocol.Event;
import net.shchoo.scafe.scafe.protocol.GameClock;
import net.shchoo.scafe.scafe.protocol.Intent;
import net.shchoo.scafe.scafe.protocol.Wire;
import net.shchoo.scafe.scafe.server.GameServer;
import net.shchoo.scafe.scafe.server.TutorialWorld;

import com.google.gson.Gson;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The web client's server (a spike): one plain-HTTP port that serves the page out of the jar ({@code /web/...}),
 * {@code /meta.json} (the protocol version, tick length and building pictures -- so a page always matches the
 * server it came from), and upgrades {@code /ws} to a WebSocket carrying the same JSON as {@link NetworkServer}
 * ({@link Wire}), one text message each, within the same {@link NetworkServer.Limits}; {@code /tutorial} is the
 * same, into a tutorial world of the connection's own.
 * No TLS of its own: browsers anywhere but localhost need wss://, so to go public put it behind a proxy that does
 * TLS (e.g. Caddy).
 */
public class WebServer implements AutoCloseable {
    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_REQUEST_BYTES = 16 * 1024;
    private static final int MAX_MESSAGE_BYTES = 4 * Wire.MAX_INTENT_CHARS;
    private static final int REQUEST_TIMEOUT_MILLIS = 10_000;
    private static final long WRITE_POLL_MILLIS = 15;
    private static final Map<String, String> TYPES = Map.of("html", "text/html; charset=utf-8",
            "js", "text/javascript; charset=utf-8", "css", "text/css; charset=utf-8", "json", "application/json");

    private final GameServer game;
    private final ServerSocket socket;
    private final boolean remoteAdmin;
    private final NetworkServer.Limits limits;
    private final Map<String, Integer> connectionsByAddress = new HashMap<>();
    private int connections;
    private volatile boolean running = true;

    /** {@code bind}: the address to listen on (null: every interface). */
    public WebServer(GameServer game, InetAddress bind, int port, boolean remoteAdmin, NetworkServer.Limits limits)
            throws IOException {
        this.game = game;
        this.socket = new ServerSocket(port, 50, bind);
        this.remoteAdmin = remoteAdmin;
        this.limits = limits;
    }

    public int port() {
        return socket.getLocalPort();
    }

    public void startInBackground() {
        Thread thread = new Thread(this::acceptForever, "web-accept");
        thread.setDaemon(true);
        thread.start();
    }

    private void acceptForever() {
        while (running) {
            try {
                Socket client = socket.accept();
                client.setTcpNoDelay(true);
                Thread thread = new Thread(() -> handle(client), "web-" + client.getRemoteSocketAddress());
                thread.setDaemon(true);
                thread.start();
            } catch (IOException e) {
                if (running) {
                    e.printStackTrace();
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    /** {@code path} is {@code target} without its query. */
    record Request(String method, String target, String path, Map<String, String> headers) {
        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private void handle(Socket client) {
        try (client) {
            client.setSoTimeout(REQUEST_TIMEOUT_MILLIS);
            InputStream in = new BufferedInputStream(client.getInputStream());
            OutputStream out = new BufferedOutputStream(client.getOutputStream());
            Request request = readRequest(in);
            if (request == null) {
                return;
            }
            if ((request.path().equals("/ws") || request.path().equals("/tutorial"))
                    && "websocket".equalsIgnoreCase(request.header("upgrade"))) {
                webSocket(client, in, out, request);
            } else if (!request.method().equals("GET")) {
                respond(out, 405, "text/plain", "GET only".getBytes(StandardCharsets.UTF_8));
            } else {
                serve(out, request.path());
            }
        } catch (IOException | RuntimeException e) {
            // a browser that went away, or nonsense: nothing to tell anyone
        }
    }

    /** The request line and headers (the path without its query), or null if the connection closed first. */
    static Request readRequest(InputStream in) throws IOException {
        int[] budget = {MAX_REQUEST_BYTES};
        String first = readLine(in, budget);
        if (first == null || first.isEmpty()) {
            return null;
        }
        String[] parts = first.split(" ");
        if (parts.length < 3) {
            throw new IOException("bad request line");
        }
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = readLine(in, budget)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
        }
        String target = parts[1];
        int query = target.indexOf('?');
        return new Request(parts[0], target, query < 0 ? target : target.substring(0, query), headers);
    }

    private static String readLine(InputStream in, int[] budget) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (--budget[0] < 0) {
                throw new IOException("request too long");
            }
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                line.write(c);
            }
        }
        return c == -1 && line.size() == 0 ? null : line.toString(StandardCharsets.ISO_8859_1);
    }

    /** "/" is the page; "/meta.json" what the page needs to know about this server; otherwise a file in /web/. */
    private void serve(OutputStream out, String path) throws IOException {
        if (path.equals("/meta.json")) {
            respond(out, 200, TYPES.get("json"), meta().getBytes(StandardCharsets.UTF_8));
            return;
        }
        String name = path.equals("/") ? "index.html" : path.substring(1);
        if (!name.matches("[a-z0-9_-]+\\.(html|js|css|json)")) {
            respond(out, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream file = open(name)) {
            if (file == null) {
                respond(out, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            respond(out, 200, TYPES.get(name.substring(name.lastIndexOf('.') + 1)), file.readAllBytes());
        }
    }

    /**
     * Where the page's files come from: the jar's /web/, or -- with the environment variable CAPEBABE_WEB_DIR set to
     * a folder (e.g. src/main/resources/web) -- that folder, read fresh each time, for working on the page without
     * rebuilding. Null if there's no such file.
     */
    static InputStream open(String name) throws IOException {
        String dir = System.getenv("CAPEBABE_WEB_DIR");
        if (dir != null && !dir.isBlank()) {
            java.nio.file.Path file = java.nio.file.Path.of(dir, name);
            return java.nio.file.Files.isRegularFile(file) ? java.nio.file.Files.newInputStream(file) : null;
        }
        return WebServer.class.getResourceAsStream("/web/" + name);
    }

    /**
     * The protocol version, tick length, every building's picture, and the game's tables the page works things out
     * with (levels, tiers, stacks, stats), so it never has its own copy to go out of date.
     */
    static String meta() {
        Map<String, Object> buildings = new LinkedHashMap<>();
        for (Building.Kind kind : Building.Kind.values()) {
            buildings.put(kind.name(), Map.of("label", kind.label, "action", kind.action, "picture", kind.picture(),
                    "paint", kind.paint(), "keeper", kind.keeper == 0 ? "" : String.valueOf(kind.keeper)));
        }
        Map<String, Object> yields = new LinkedHashMap<>();
        for (SkillType type : SkillType.values()) {
            List<List<Integer>> tiers = new ArrayList<>();
            for (int tier = 0; tier < Tiers.LEVEL_NEEDED.length; tier++) {
                tiers.add(List.of(Tiers.yieldLevel(type, tier, 2), Tiers.yieldLevel(type, tier, 3)));
            }
            yields.put(type.name(), tiers);
        }
        List<Map<String, Object>> stats = new ArrayList<>();
        for (Stat stat : Stat.values()) {
            stats.add(Map.of("name", stat.name(), "label", stat.label, "ticks", stat.ticks));
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("protocol", Intent.PROTOCOL_VERSION);
        meta.put("tickMillis", GameClock.TICK_MILLIS);
        meta.put("frameMillis", GameClock.FRAME_MILLIS);
        meta.put("buildings", buildings);
        meta.put("xpPerLevel", Skill.XP_PER_LEVEL);
        meta.put("levelNeeded", Tiers.LEVEL_NEEDED);
        meta.put("yieldLevels", yields);
        meta.put("baseSlots", Stacks.BASE_SLOTS);
        meta.put("maxQueuedSteps", Intent.MAX_QUEUED_STEPS);
        meta.put("playerColorNames", SkillStyle.PLAYER_COLOR_NAMES);
        meta.put("stats", stats);
        meta.put("prestigeDamage", Product.PRESTIGE_DAMAGE);
        return new Gson().toJson(meta);
    }

    static void respond(OutputStream out, int status, String type, byte[] body) throws IOException {
        String reason = switch (status) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 502 -> "Bad Gateway";
            default -> "Error";
        };
        out.write(("HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: " + type + "\r\nContent-Length: "
                + body.length + "\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private void webSocket(Socket client, InputStream in, OutputStream out, Request request) throws IOException {
        String key = request.header("sec-websocket-key");
        if (key == null) {
            respond(out, 400, "text/plain", "No Sec-WebSocket-Key".getBytes(StandardCharsets.UTF_8));
            return;
        }
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept(key) + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        String address = client.getInetAddress().getHostAddress();
        String refusal = admit(address);
        if (refusal != null) {
            synchronized (out) {
                sendFrame(out, 0x1, Wire.encode(new Event.LoginRefused(refusal)).getBytes(StandardCharsets.UTF_8));
                sendFrame(out, 0x8, new byte[0]);
                out.flush();
            }
            return;
        }
        try {
            if (request.path().equals("/tutorial")) {
                tutorial(client, in, out, address);
            } else {
                bridge(client, in, out, address, game.connectRemote(remoteAdmin, address));
            }
        } finally {
            release(address);
        }
    }

    /**
     * {@code /tutorial}: a tutorial world of this connection's own ({@link TutorialWorld}: fixed seed, scripted
     * discoveries, nothing saved), running while it's open. The page joins it by name (Join; no account needed, as
     * nothing's kept), and may use its admin commands (/6 moves the script on). At most {@link #MAX_TUTORIALS} at once.
     */
    private void tutorial(Socket client, InputStream in, OutputStream out, String address) throws IOException {
        synchronized (this) {
            if (tutorials >= MAX_TUTORIALS) {
                synchronized (out) {
                    sendFrame(out, 0x1, Wire.encode(new Event.LoginRefused("Too many tutorials running just now. Try again in a bit."))
                            .getBytes(StandardCharsets.UTF_8));
                    sendFrame(out, 0x8, new byte[0]);
                    out.flush();
                }
                return;
            }
            tutorials++;
        }
        GameServer world = TutorialWorld.create();
        world.start();
        try {
            bridge(client, in, out, address, world.connect());
        } finally {
            world.stop();
            synchronized (this) {
                tutorials--;
            }
        }
    }

    /** Tutorial worlds that can run at once (each has its own game thread). */
    static final int MAX_TUTORIALS = 8;
    private int tutorials;

    /** Sec-WebSocket-Accept for a Sec-WebSocket-Key. */
    static String accept(String key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1").digest((key + WEBSOCKET_GUID).getBytes(StandardCharsets.ISO_8859_1));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Messages in to a game session, its events out, until either end stops (as {@link NetworkServer} does). */
    private void bridge(Socket client, InputStream in, OutputStream out, String address, GameServer.Session session)
            throws IOException {
        client.setSoTimeout((int) limits.loginMillis());
        Thread writer = new Thread(() -> {
            try {
                while (!client.isClosed()) {
                    List<Event> events = session.drain();
                    if (!events.isEmpty()) {
                        synchronized (out) {
                            for (Event event : events) {
                                sendFrame(out, 0x1, Wire.encode(event).getBytes(StandardCharsets.UTF_8));
                            }
                            out.flush();
                        }
                    } else if (session.isClosed()) {
                        synchronized (out) {
                            sendFrame(out, 0x8, new byte[0]);
                            out.flush();
                        }
                        closeQuietly(client);
                    } else {
                        Thread.sleep(WRITE_POLL_MILLIS);
                    }
                }
            } catch (IOException | InterruptedException e) {
                session.close();
                closeQuietly(client);
            }
        }, "web-out-" + address);
        writer.setDaemon(true);
        writer.start();
        try {
            double tokens = limits.burst();
            long last = System.nanoTime();
            int droppedInARow = 0;
            while (true) {
                String message;
                try {
                    message = readMessage(in, out);
                } catch (SocketTimeoutException e) {
                    if (!session.inWorld()) {
                        throw new IOException("didn't log in within " + limits.loginMillis() / 1000 + "s");
                    }
                    client.setSoTimeout(0);
                    continue;
                }
                if (message == null) {
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
                if (!message.isBlank()) {
                    session.send(Wire.decodeIntent(message));
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("Dropping web client " + address + ": " + e.getMessage());
        } finally {
            session.close();
            closeQuietly(client);
        }
    }

    /**
     * The next whole text message (fragments joined; pings answered, pongs skipped on the way), or null once the
     * client closes. Client frames must be masked.
     */
    static String readMessage(InputStream in, OutputStream out) throws IOException {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        while (true) {
            int b0 = in.read();
            if (b0 < 0) {
                return null;
            }
            int b1 = readByte(in);
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0f;
            if ((b1 & 0x80) == 0) {
                throw new IOException("unmasked frame from a client");
            }
            long length = b1 & 0x7f;
            if (length == 126) {
                length = (readByte(in) << 8) | readByte(in);
            } else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) {
                    length = (length << 8) | readByte(in);
                }
            }
            if (length < 0 || message.size() + length > MAX_MESSAGE_BYTES) {
                throw new IOException("message too long");
            }
            byte[] mask = in.readNBytes(4);
            byte[] payload = in.readNBytes((int) length);
            if (mask.length < 4 || payload.length < length) {
                return null;
            }
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i & 3];
            }
            switch (opcode) {
                case 0x8 -> {
                    synchronized (out) {
                        sendFrame(out, 0x8, new byte[0]);
                        out.flush();
                    }
                    return null;
                }
                case 0x9 -> {
                    synchronized (out) {
                        sendFrame(out, 0xA, payload);
                        out.flush();
                    }
                }
                case 0xA -> {
                    // a pong: nothing to do
                }
                case 0x0, 0x1 -> {
                    message.write(payload);
                    if (fin) {
                        return message.toString(StandardCharsets.UTF_8);
                    }
                }
                default -> throw new IOException("unsupported frame type " + opcode);
            }
        }
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException();
        }
        return b;
    }

    /** One unmasked, unfragmented frame (callers hold {@code out}'s lock and flush). */
    static void sendFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
        out.write(0x80 | opcode);
        if (payload.length < 126) {
            out.write(payload.length);
        } else if (payload.length < 65536) {
            out.write(126);
            out.write(payload.length >>> 8);
            out.write(payload.length & 0xff);
        } else {
            out.write(127);
            long length = payload.length;
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) (length >>> shift) & 0xff);
            }
        }
        out.write(payload);
    }

    /** Counts the connection in, or says why not (the same rules as {@link NetworkServer}). */
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

    static void closeQuietly(Socket socket) {
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
