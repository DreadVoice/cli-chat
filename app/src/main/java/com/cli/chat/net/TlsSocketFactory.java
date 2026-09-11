package com.cli.chat.net;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cli.chat.common.exception.TlsException;

public class TlsSocketFactory implements SocketFactory {

    private static final Logger log = LoggerFactory.getLogger(TlsSocketFactory.class);

    private static final String[] PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

    private final SSLContext context;

    private TlsSocketFactory(SSLContext context) {
        this.context = context;
    }

    public static TlsSocketFactory fromKeystore(String keystorePath, String password) throws TlsException {
        Path path = Path.of(keystorePath);
        char[] secret = password == null ? new char[0] : password.toCharArray();
        try (InputStream in = Files.newInputStream(path)) {
            KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
            keystore.load(in, secret);

            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(keystore, secret);

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), null, null);

            log.info("TLS enabled from keystore {}", path);
            return new TlsSocketFactory(context);
        } catch (IOException | GeneralSecurityException e) {
            throw new TlsException("could not load the keystore " + path, e);
        }
    }

    @Override
    public ServerSocket createServerSocket(int port) throws IOException {
        SSLServerSocket socket = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(port);
        socket.setEnabledProtocols(PROTOCOLS);
        return socket;
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(host, port);
        socket.setEnabledProtocols(PROTOCOLS);
        socket.startHandshake();
        return socket;
    }
}
