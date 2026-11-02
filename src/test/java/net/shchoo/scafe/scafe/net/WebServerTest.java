package net.shchoo.scafe.scafe.net;

import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.protocol.Event;
import net.shchoo.scafe.scafe.protocol.Intent;
import net.shchoo.scafe.scafe.protocol.Wire;
import net.shchoo.scafe.scafe.server.Discovery;
import net.shchoo.scafe.scafe.server.GameServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WebServerTest {
    private GameServer game;
    private WebServer web;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {
        game = new GameServer(new World(1, "w", 1));
        game.setDiscovery(Discovery.off());
        web = new WebServer(game, InetAddress.getLoopbackAddress(), 0, false, NetworkServer.Limits.defaults());
        web.startInBackground();
    }

    @AfterEach
    void tearDown() throws Exception {
        web.close();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + web.port() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesThePageAndWhatItNeedsToKnow() throws Exception {
        HttpResponse<String> page = get("/");
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("<canvas"), page.body());
        assertTrue(page.headers().firstValue("content-type").orElse("").startsWith("text/html"));
        assertEquals(200, get("/main.js").statusCode());
        String meta = get("/meta.json").body();
        assertTrue(meta.contains("\"protocol\":" + Intent.PROTOCOL_VERSION), meta);
        assertTrue(meta.contains("\"BANK\""), meta);
        assertTrue(meta.contains("\"xpPerLevel\":[0,1047,"), "the game's tables, so the page has no copy of its own: " + meta);
        assertTrue(meta.contains("\"yieldLevels\"") && meta.contains("\"baseSlots\":15"), meta);
        for (String module : new String[]{"content.js", "game.js", "term.js", "sprites.js", "mapview.js", "ui.js"}) {
            assertEquals(200, get("/" + module).statusCode(), module);
        }
    }

    @Test
    void nothingElse() throws Exception {
        assertEquals(404, get("/missing.js").statusCode());
        assertEquals(404, get("/..%2fweb%2findex.html").statusCode());
        assertEquals(404, get("/web/index.html").statusCode());
    }

    @Test
    void aWebSocketLogsInAndGetsTheWorld() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        WebSocket socket = http.newWebSocketBuilder().buildAsync(URI.create("ws://localhost:" + web.port() + "/ws"),
                new WebSocket.Listener() {
                    private final StringBuilder partial = new StringBuilder();

                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        partial.append(data);
                        if (last) {
                            received.add(partial.toString());
                            partial.setLength(0);
                        }
                        ws.request(1);
                        return null;
                    }
                }).get(5, TimeUnit.SECONDS);
        socket.sendText(Wire.encode(new Intent.Login(Intent.PROTOCOL_VERSION, "alice", "password1")), true).get();
        assertInstanceOf(Event.LoginAccepted.class, next(received));
        socket.sendText(Wire.encode(new Intent.EnterWorld(null)), true).get();
        Event welcome = next(received);
        assertInstanceOf(Event.Welcome.class, welcome);
        assertEquals("alice", ((Event.Welcome) welcome).you().name());

        socket.sendText(Wire.encode(new Intent.Move(1, 0)), true).get();
        Event moved;
        do {
            moved = next(received);
        } while (!(moved instanceof Event.EntityMoved));
        assertEquals(1, ((Event.EntityMoved) moved).position().x(), "a step right");
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get();
    }

    @Test
    void tutorialIsAWorldOfItsOwn_joinedByName() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        WebSocket socket = http.newWebSocketBuilder().buildAsync(URI.create("ws://localhost:" + web.port() + "/tutorial"),
                new WebSocket.Listener() {
                    private final StringBuilder partial = new StringBuilder();

                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        partial.append(data);
                        if (last) {
                            received.add(partial.toString());
                            partial.setLength(0);
                        }
                        ws.request(1);
                        return null;
                    }
                }).get(5, TimeUnit.SECONDS);
        socket.sendText(Wire.encode(new Intent.Join("newbie")), true).get();
        String message = received.poll(5, TimeUnit.SECONDS);
        assertNotNull(message, "its own world ticks by itself");
        Event welcome = Wire.decodeEvent(message);
        assertInstanceOf(Event.Welcome.class, welcome);
        assertEquals("Tutorial", ((Event.Welcome) welcome).worldName());
        assertEquals("newbie", ((Event.Welcome) welcome).you().name());
        assertTrue(game.world.content.skills.isEmpty(), "the real world's untouched");
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get();
    }

    /** Ticks the game until the next message arrives. */
    private Event next(BlockingQueue<String> received) throws Exception {
        for (int i = 0; i < 200; i++) {
            game.tick();
            String message = received.poll(20, TimeUnit.MILLISECONDS);
            if (message != null) {
                return Wire.decodeEvent(message);
            }
        }
        fail("nothing arrived");
        return null;
    }

    @Test
    void framesRoundTrip_fragmentsJoin_andPingsArePonged() throws Exception {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        masked(wire, 0x01, false, "hel");
        masked(wire, 0x09, true, "p");
        masked(wire, 0x00, true, "lo");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals("hello", WebServer.readMessage(new ByteArrayInputStream(wire.toByteArray()), out));
        byte[] pong = out.toByteArray();
        assertEquals(0x8A, pong[0] & 0xff, "a pong");
        assertEquals('p', pong[2]);

        String big = "x".repeat(70_000);
        ByteArrayOutputStream sent = new ByteArrayOutputStream();
        WebServer.sendFrame(sent, 0x1, big.getBytes(StandardCharsets.UTF_8));
        byte[] frame = sent.toByteArray();
        assertEquals(127, frame[1], "a 64-bit length");
        assertEquals(10 + 70_000, frame.length);
    }

    @Test
    void theHandshakeAnswer() {
        // RFC 6455's example
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WebServer.accept("dGhlIHNhbXBsZSBub25jZQ=="));
    }

    private static void masked(ByteArrayOutputStream out, int opcode, boolean fin, String text) {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        byte[] mask = {1, 2, 3, 4};
        out.write((fin ? 0x80 : 0) | opcode);
        out.write(0x80 | payload.length);
        out.writeBytes(mask);
        for (int i = 0; i < payload.length; i++) {
            out.write(payload[i] ^ mask[i & 3]);
        }
    }
}
