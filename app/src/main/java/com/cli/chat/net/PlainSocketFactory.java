package com.cli.chat.net;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class PlainSocketFactory implements SocketFactory {

    @Override
    public ServerSocket createServerSocket(int port) throws IOException {
        return new ServerSocket(port);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return new Socket(host, port);
    }
}
