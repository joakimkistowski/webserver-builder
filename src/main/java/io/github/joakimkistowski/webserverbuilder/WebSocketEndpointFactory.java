package io.github.joakimkistowski.webserverbuilder;

/**
 * A Factory that is called to create websocket endpoints. It is called to instantiate an endpoint each
 * time a websocket connection is established. The endpoints created by the factory must be annotated with the
 * {@link jakarta.websocket.server.ServerEndpoint}-annotation.
 */
public interface WebSocketEndpointFactory {
    /**
     * Create a new Jakarta websocket endpoint.
     * @return The endpoint.
     */
    Object createNewEndpointInstanceForConnection();

    /**
     * The endpoint class. Must match the class of objects created by {@link #createNewEndpointInstanceForConnection()}.
     * @return The endpoint class.
     */
    Class<?> getEndpointClass();
}
