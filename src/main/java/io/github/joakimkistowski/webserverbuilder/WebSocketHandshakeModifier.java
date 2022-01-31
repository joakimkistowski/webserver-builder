package io.github.joakimkistowski.webserverbuilder;

import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;

/**
 * <p>
 * Websocket handshake modifier that may be used to add custom behavior to the websocket handshake process
 * for all of the server's websocket endpoints.
 * </p>
 * <p>
 * A common use case for a custom handshake modifier is reading from or writing to the HTTP headers of handshake
 * requests/responses.
 * </p>
 * <p>
 * Note: The handshake modifier uses a functional interface and may be implemented as a lambda.
 * </p>
 */
@FunctionalInterface
public interface WebSocketHandshakeModifier {
    /**
     * Modify the handshake.
     * @param sec The server endpoint config.
     * @param request The handshake HTTP request.
     * @param response The handshake response.
     */
    void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response);
}
