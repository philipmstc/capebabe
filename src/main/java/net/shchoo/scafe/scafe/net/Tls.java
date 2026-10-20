package net.shchoo.scafe.scafe.net;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Encryption for network play (TLS 1.3), so passwords aren't readable on the way.
 * <ul>
 * <li>The host has a self-signed key, made with the JDK's {@code keytool} the first time it hosts and kept in
 * {@code <data dir>/tls/}. It's the same key from then on, so its fingerprint doesn't change.</li>
 * <li>Clients can't check a self-signed certificate against anyone, so they trust a server the first time
 * they connect to it and remember its fingerprint in {@code <data dir>/known-servers}, like SSH. If it's
 * different next time, they refuse to connect: either the host made a new key, or someone is in the middle.</li>
 * </ul>
 */
public final class Tls {
    private Tls() {}

    static final String PROTOCOL = "TLSv1.3";
    private static final String ALIAS = "capebabe";

    /** The host's side: its key (made on first use), and the fingerprint friends will see. */
    public record Server(SSLContext context, String fingerprint) {}

    /** What the client found out about the server while connecting. */
    public record Client(SSLContext context, Trust trust) {}

    /** The server's fingerprint, and whether this was the first time we'd seen it. Set during the handshake. */
    public static final class Trust {
        private volatile String fingerprint;
        private volatile boolean firstVisit;

        public String fingerprint() {
            return fingerprint;
        }

        public boolean firstVisit() {
            return firstVisit;
        }
    }

    public static Server server(Path dataDir) throws IOException, GeneralSecurityException {
        Path dir = dataDir.resolve("tls");
        Path keystore = dir.resolve("server.p12");
        Path passwordFile = dir.resolve("server.p12.pass");
        if (!Files.exists(keystore)) {
            Files.createDirectories(dir);
            byte[] random = new byte[16];
            new SecureRandom().nextBytes(random);
            String password = HexFormat.of().formatHex(random);
            makeKey(keystore, password);
            Files.writeString(passwordFile, password, StandardCharsets.UTF_8);
        }
        char[] password = Files.readString(passwordFile, StandardCharsets.UTF_8).trim().toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            store.load(in, password);
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, password);
        SSLContext context = SSLContext.getInstance(PROTOCOL);
        context.init(keys.getKeyManagers(), null, null);
        return new Server(context, fingerprint((X509Certificate) store.getCertificate(ALIAS)));
    }

    /** A new EC key + self-signed certificate (10 years) via keytool, which every JDK (and Temurin JRE) ships. */
    private static void makeKey(Path keystore, String password) throws IOException {
        String exe = System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool";
        Path keytool = Path.of(System.getProperty("java.home"), "bin", exe);
        if (!Files.exists(keytool)) {
            throw new IOException("Can't make the server's encryption key: no keytool in " + keytool.getParent()
                    + ". Run the host with a full JDK.");
        }
        Process process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", ALIAS,
                "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA", "-validity", "3650",
                "-dname", "CN=capebabe server", "-keystore", keystore.toString(), "-storetype", "PKCS12",
                "-storepass", password, "-keypass", password, "-noprompt")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("keytool couldn't make the server's key: " + output.trim());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted making the server's key", e);
        }
    }

    /**
     * The client's side for connecting to {@code server} ("host:port"): trusts it on first sight and remembers
     * it in {@code knownServers}; refuses a different certificate later.
     */
    public static Client client(Path knownServers, String server) throws GeneralSecurityException {
        Trust trust = new Trust();
        X509TrustManager pinning = new X509TrustManager() {
            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain == null || chain.length == 0) {
                    throw new CertificateException("The server didn't send a certificate.");
                }
                String seen = fingerprint(chain[0]);
                trust.fingerprint = seen;
                try {
                    String known = knownFingerprint(knownServers, server);
                    if (known == null) {
                        remember(knownServers, server, seen);
                        trust.firstVisit = true;
                    } else if (!known.equals(seen)) {
                        throw new CertificateException("The server at " + server + " isn't the one you connected to before"
                                + " (its fingerprint changed to " + shortFingerprint(seen) + "). If the host made a new key,"
                                + " remove its line from " + knownServers + " and connect again.");
                    }
                } catch (IOException e) {
                    throw new CertificateException("Couldn't read or save " + knownServers + ": " + e.getMessage(), e);
                }
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("clients don't present certificates");
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext context = SSLContext.getInstance(PROTOCOL);
        context.init(null, new TrustManager[]{pinning}, null);
        return new Client(context, trust);
    }

    /** SHA-256 of the certificate, as colon-separated hex. */
    public static String fingerprint(X509Certificate certificate) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            return HexFormat.ofDelimiter(":").withUpperCase().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The first 8 bytes: enough for people to compare by eye. */
    public static String shortFingerprint(String fingerprint) {
        return fingerprint == null ? "?" : fingerprint.substring(0, Math.min(23, fingerprint.length()));
    }

    private static synchronized String knownFingerprint(Path file, String server) throws IOException {
        if (!Files.exists(file)) {
            return null;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 2 && parts[0].equalsIgnoreCase(server)) {
                return parts[1];
            }
        }
        return null;
    }

    private static synchronized void remember(Path file, String server, String fingerprint) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8)) : new ArrayList<>();
        lines.add(server.toLowerCase() + " " + fingerprint);
        Files.write(file, lines, StandardCharsets.UTF_8);
    }
}
