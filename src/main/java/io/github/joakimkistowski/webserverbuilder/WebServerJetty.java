package io.github.joakimkistowski.webserverbuilder;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;

/**
 * Container for the underlying Jetty server and its servlet context handler.
 */
public class WebServerJetty {
    private final Server server;
    private final ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);

    /**
     * Construct a Jetty container.
     * @param server The Jetty server.
     */
    WebServerJetty(Server server) {
        this.server = server;
    }

    /**
     * Get the Jetty server used by the web server.
     * @return The Jetty server.
     */
    public Server getServer() {
        return server;
    }

    /**
     * Get the servlet context handler used in the Jetty server.
     * @return The servlet context handler.
     */
    public ServletContextHandler getContextHandler() {
        return contextHandler;
    }
}
