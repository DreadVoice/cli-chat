package com.cli.chat.net;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

public class TestKeystore implements AutoCloseable {

    private static final String PASSWORD = "changeit";

    private final Path directory;
    private final Path path;
    private final Path truststore;

    private TestKeystore(Path directory, Path path, Path truststore) {
        this.directory = directory;
        this.path = path;
        this.truststore = truststore;
    }

    public static TestKeystore create() throws Exception {
        return create("CN=localhost", "SAN=dns:localhost,ip:127.0.0.1");
    }

    public static TestKeystore create(String name, String subjectAlternativeName) throws Exception {
        Path directory = Files.createTempDirectory("cli-chat-tls-");
        Path path = directory.resolve("keystore.p12");
        Path certificate = directory.resolve("chat.cer");
        Path truststore = directory.resolve("truststore.p12");

        keytool("-genkeypair",
                "-alias", "chat",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-storetype", "PKCS12",
                "-keystore", path.toString(),
                "-storepass", PASSWORD,
                "-dname", name,
                "-ext", subjectAlternativeName,
                "-validity", "1");

        keytool("-exportcert",
                "-alias", "chat",
                "-keystore", path.toString(),
                "-storepass", PASSWORD,
                "-file", certificate.toString());

        keytool("-importcert",
                "-noprompt",
                "-alias", "chat",
                "-storetype", "PKCS12",
                "-keystore", truststore.toString(),
                "-storepass", PASSWORD,
                "-file", certificate.toString());

        return new TestKeystore(directory, path, truststore);
    }

    private static void keytool(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("keytool"));
        command.addAll(List.of(arguments));

        Process keytool = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(keytool.getInputStream().readAllBytes());
        if (keytool.waitFor() != 0) {
            throw new IllegalStateException("keytool failed: " + output);
        }
    }

    public String path() {
        return path.toString();
    }

    public String truststorePath() {
        return truststore.toString();
    }

    public String password() {
        return PASSWORD;
    }

    public SSLContext trustingClient() throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(path)) {
            store.load(in, PASSWORD.toCharArray());
        }
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }

    @Override
    public void close() throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            files.sorted(Comparator.reverseOrder()).forEach(entry -> {
                try {
                    Files.deleteIfExists(entry);
                } catch (IOException e) {
                    throw new IllegalStateException("could not delete " + entry, e);
                }
            });
        }
    }
}
