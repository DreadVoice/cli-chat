package com.cli.chat.net;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cli.chat.common.exception.TlsException;

public class TlsSocketFactory implements SocketFactory {

    private static final Logger log = LoggerFactory.getLogger(TlsSocketFactory.class);

    private static final String[] PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

    private final SSLContext context;
    private final boolean verifyHostname;

    private TlsSocketFactory(SSLContext context, boolean verifyHostname) {
        this.context = context;
        this.verifyHostname = verifyHostname;
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
            return new TlsSocketFactory(context, true);
        } catch (IOException | GeneralSecurityException e) {
            throw new TlsException("could not load the keystore " + path, e);
        }
    }

    public static TlsSocketFactory fromTruststore(String truststorePath, String password) throws TlsException {
        Path path = Path.of(truststorePath);
        char[] secret = password == null ? new char[0] : password.toCharArray();
        try (InputStream in = Files.newInputStream(path)) {
            KeyStore truststore = KeyStore.getInstance(KeyStore.getDefaultType());
            truststore.load(in, secret);

            TrustManagerFactory trust = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trust.init(truststore);

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trust.getTrustManagers(), null);

            log.info("trusting the certificates in {}", path);
            return new TlsSocketFactory(context, true);
        } catch (IOException | GeneralSecurityException e) {
            throw new TlsException("could not load the truststore " + path, e);
        }
    }

    public static TlsSocketFactory insecure() throws TlsException {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustEverything(), null);

            log.warn("certificate checks are off, the connection is encrypted but not authenticated");
            return new TlsSocketFactory(context, false);
        } catch (GeneralSecurityException e) {
            throw new TlsException("could not set up an unchecked TLS context", e);
        }
    }

    private static TrustManager[] trustEverything() {
        return new TrustManager[] {
            new X509TrustManager() {

                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }
        };
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
        if (verifyHostname) {
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
        }
        socket.startHandshake();
        return socket;
    }
}
