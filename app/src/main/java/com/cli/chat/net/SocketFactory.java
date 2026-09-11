package com.cli.chat.net;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public interface SocketFactory {

    ServerSocket createServerSocket(int port) throws IOException;

    Socket createSocket(String host, int port) throws IOException;
}
