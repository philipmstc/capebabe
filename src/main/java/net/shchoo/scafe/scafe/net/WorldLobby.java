package net.shchoo.scafe.scafe.net;

import com.google.gson.Gson;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One web address for every world on this computer: "/" lists the running worlds, and {@code /w/<world>/...} is passed
 * on, WebSockets included, to that world's {@link WebServer}. Each world is its own process, so they say they're
 * running with a file each in {@code <data>/running/} ({@link #announce}); one that was killed leaves its file behind,
 * so a world only counts as running while its port answers. Plain HTTP, like {@link WebServer}.
 */
public class WorldLobby implements AutoCloseable {
    private static final Pattern WORLD_PATH = Pattern.compile("/w/([a-z0-9-]+)(/.*)?");
    private static final int REQUEST_TIMEOUT_MILLIS = 10_000;
    private static final int CONNECT_TIMEOUT_MILLIS = 2_000;
    private static final int ALIVE_TIMEOUT_MILLIS = 300;

    private final Path data;
    private final ServerSocket socket;
    private volatile boolean running = true;

    /** {@code data}: the data folder the worlds run from; {@code bind}: the address to listen on. */
    public WorldLobby(Path data, InetAddress bind, int port) throws IOException {
        this.data = data;
        this.socket = new ServerSocket(port, 50, bind);
    }

    public int port() {
        return socket.getLocalPort();
    }

    /** A world's web client, as announced: {@code id} is its part of the address. */
    record RunningWorld(String id, String name, String host, int port) {
    }

    /**
     * Puts a world's web server on the list, in place of anything that had that address before it was killed.
     * Returns the file, for the world to delete as it stops.
     */
    public static Path announce(Path data, String id, String name, InetAddress address, int port) throws IOException {
        Path folder = data.resolve("running");
        Files.createDirectories(folder);
        String host = address.getHostAddress();
        for (RunningWorld other : announced(data)) {
            if (other.host().equals(host) && other.port() == port) {
                Files.deleteIfExists(folder.resolve(other.id() + ".properties"));
            }
        }
        Properties world = new Properties();
        world.setProperty("name", name);
        world.setProperty("host", host);
        world.setProperty("port", String.valueOf(port));
        Path file = folder.resolve(id + ".properties");
        try (OutputStream out = Files.newOutputStream(file)) {
            world.store(out, "capebabe: this world's web client, while it's running");
        }
        return file;
    }

    private static List<RunningWorld> announced(Path data) throws IOException {
        Path folder = data.resolve("running");
        List<RunningWorld> worlds = new ArrayList<>();
        if (!Files.isDirectory(folder)) {
            return worlds;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.properties")) {
            for (Path file : files) {
                String fileName = file.getFileName().toString();
                RunningWorld world = read(file, fileName.substring(0, fileName.length() - ".properties".length()));
                if (world != null) {
                    worlds.add(world);
                }
            }
        }
        return worlds;
    }

    /** The world in this file, or null if it's gone or only half written. */
    private static RunningWorld read(Path file, String id) throws IOException {
        Properties world = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            world.load(in);
        } catch (NoSuchFileException e) {
            return null;
        }
        String name = world.getProperty("name"), host = world.getProperty("host"), port = world.getProperty("port");
        return name == null || host == null || port == null ? null : new RunningWorld(id, name, host, Integer.parseInt(port));
    }

    private List<RunningWorld> running() throws IOException {
        List<RunningWorld> worlds = new ArrayList<>(announced(data));
        worlds.removeIf(world -> !answers(world));
        worlds.sort(Comparator.comparing(RunningWorld::name, String.CASE_INSENSITIVE_ORDER));
        return worlds;
    }

    private static boolean answers(RunningWorld world) {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(world.host(), world.port()), ALIVE_TIMEOUT_MILLIS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public void startInBackground() {
        Thread thread = new Thread(this::acceptForever, "lobby-accept");
        thread.setDaemon(true);
        thread.start();
    }

    public void acceptForever() {
        while (running) {
            try {
                Socket client = socket.accept();
                client.setTcpNoDelay(true);
                Thread thread = new Thread(() -> handle(client), "lobby-" + client.getRemoteSocketAddress());
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

    private void handle(Socket client) {
        try (client) {
            client.setSoTimeout(REQUEST_TIMEOUT_MILLIS);
            InputStream in = new BufferedInputStream(client.getInputStream());
            OutputStream out = new BufferedOutputStream(client.getOutputStream());
            WebServer.Request request = WebServer.readRequest(in);
            if (request == null) {
                return;
            }
            Matcher world = WORLD_PATH.matcher(request.path());
            if (!request.method().equals("GET")) {
                WebServer.respond(out, 405, "text/plain", "GET only".getBytes(StandardCharsets.UTF_8));
            } else if (request.path().equals("/")) {
                page(out);
            } else if (request.path().equals("/worlds.json")) {
                WebServer.respond(out, 200, "application/json", worldsJson().getBytes(StandardCharsets.UTF_8));
            } else if (!world.matches()) {
                notFound(out);
            } else if (world.group(2) == null) {
                // the page's files are relative to it, so it has to end in a slash
                redirect(out, request.path() + "/" + request.target().substring(request.path().length()));
            } else {
                forward(client, in, out, request, world.group(1), world.group(2));
            }
        } catch (IOException | RuntimeException e) {
            // a browser that went away, or nonsense: nothing to tell anyone
        }
    }

    private static void page(OutputStream out) throws IOException {
        try (InputStream file = WebServer.open("lobby.html")) {
            if (file == null) {
                notFound(out);
                return;
            }
            WebServer.respond(out, 200, "text/html; charset=utf-8", file.readAllBytes());
        }
    }

    private String worldsJson() throws IOException {
        List<Map<String, String>> worlds = new ArrayList<>();
        for (RunningWorld world : running()) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("name", world.name());
            entry.put("path", "/w/" + world.id() + "/");
            worlds.add(entry);
        }
        return new Gson().toJson(worlds);
    }

    private static void notFound(OutputStream out) throws IOException {
        WebServer.respond(out, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
    }

    private static void redirect(OutputStream out, String location) throws IOException {
        out.write(("HTTP/1.1 302 Found\r\nLocation: " + location + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    /** Sends the request on to the world without {@code /w/<world>}, then just copies bytes both ways until one end stops. */
    private void forward(Socket client, InputStream in, OutputStream out, WebServer.Request request, String id, String path)
            throws IOException {
        Path file = data.resolve("running").resolve(id + ".properties");
        RunningWorld world = Files.exists(file) ? read(file, id) : null;
        if (world == null) {
            notFound(out);
            return;
        }
        try (Socket backend = new Socket()) {
            try {
                backend.connect(new InetSocketAddress(world.host(), world.port()), CONNECT_TIMEOUT_MILLIS);
            } catch (IOException e) {
                WebServer.respond(out, 502, "text/plain", (world.name() + " isn't running just now.").getBytes(StandardCharsets.UTF_8));
                return;
            }
            backend.setTcpNoDelay(true);
            client.setSoTimeout(0);
            OutputStream toWorld = backend.getOutputStream();
            StringBuilder head = new StringBuilder("GET " + path + request.target().substring(request.path().length()) + " HTTP/1.1\r\n");
            request.headers().forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
            toWorld.write(head.append("\r\n").toString().getBytes(StandardCharsets.ISO_8859_1));
            toWorld.flush();
            Thread upstream = new Thread(() -> {
                copy(in, toWorld);
                WebServer.closeQuietly(backend);
            }, "lobby-up-" + id);
            upstream.setDaemon(true);
            upstream.start();
            copy(backend.getInputStream(), client.getOutputStream());
        }
    }

    private static void copy(InputStream from, OutputStream to) {
        byte[] buffer = new byte[8192];
        try {
            int read;
            while ((read = from.read(buffer)) >= 0) {
                to.write(buffer, 0, read);
                to.flush();
            }
        } catch (IOException e) {
            // one end went away: the caller closes the other
        }
    }

    @Override
    public void close() throws IOException {
        running = false;
        socket.close();
    }
}
