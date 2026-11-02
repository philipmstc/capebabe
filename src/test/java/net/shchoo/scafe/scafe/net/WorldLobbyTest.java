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
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WorldLobbyTest {
    private static final InetAddress HERE = InetAddress.getLoopbackAddress();

    @TempDir
    Path data;
    private WebServer world;
    private WorldLobby lobby;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {
        GameServer game = new GameServer(new World(1, "w", 1));
        game.setDiscovery(Discovery.off());
        world = new WebServer(game, HERE, 0, false, NetworkServer.Limits.defaults());
        world.startInBackground();
        WorldLobby.announce(data, "gabes-world", "Gabe's World", HERE, world.port());
        lobby = new WorldLobby(data, HERE, 0);
        lobby.startInBackground();
    }

    @AfterEach
    void tearDown() throws Exception {
        lobby.close();
        world.close();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + lobby.port() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** A port nothing's listening on. */
    private static int closedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, HERE)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void listsTheWorldsThatAnswer() throws Exception {
        WorldLobby.announce(data, "killed", "Killed", HERE, closedPort());
        HttpResponse<String> page = get("/");
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("worlds.json"), page.body());
        assertEquals("[{\"name\":\"Gabe\\u0027s World\",\"path\":\"/w/gabes-world/\"}]", get("/worlds.json").body());
    }

    @Test
    void aWorldsPagesComeFromIt() throws Exception {
        HttpResponse<String> page = get("/w/gabes-world/");
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("<canvas"), page.body());
        assertEquals(200, get("/w/gabes-world/main.js").statusCode());
        assertTrue(get("/w/gabes-world/meta.json").body().contains("\"protocol\":" + Intent.PROTOCOL_VERSION));
        assertEquals(404, get("/w/gabes-world/missing.js").statusCode(), "the world's own answer");
    }

    @Test
    void noSlash_goesToTheOneWithASlash_keepingTheQuery() throws Exception {
        HttpResponse<String> response = get("/w/gabes-world?layout=phone");
        assertEquals(302, response.statusCode());
        assertEquals("/w/gabes-world/?layout=phone", response.headers().firstValue("location").orElse(""));
    }

    @Test
    void worldsNotRunningAndOtherPaths() throws Exception {
        WorldLobby.announce(data, "killed", "Killed", HERE, closedPort());
        assertEquals(502, get("/w/killed/").statusCode());
        assertEquals(404, get("/w/nobody/").statusCode());
        assertEquals(404, get("/w/..%2fsecrets/").statusCode());
        assertEquals(404, get("/elsewhere").statusCode());
    }

    @Test
    void anAddressIsOneWorlds_soAKilledWorldsFileGoesWhenItsPortIsReused() throws Exception {
        WorldLobby.announce(data, "new-world", "New World", HERE, world.port());
        assertFalse(Files.exists(data.resolve("running").resolve("gabes-world.properties")));
        assertTrue(get("/worlds.json").body().contains("New World"));
    }

    @Test
    void aWebSocketGetsThrough() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        WebSocket socket = http.newWebSocketBuilder().buildAsync(
                URI.create("ws://localhost:" + lobby.port() + "/w/gabes-world/tutorial"), new WebSocket.Listener() {
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
        assertNotNull(message, "the world answered");
        assertInstanceOf(Event.Welcome.class, Wire.decodeEvent(message));
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get();
    }
}
