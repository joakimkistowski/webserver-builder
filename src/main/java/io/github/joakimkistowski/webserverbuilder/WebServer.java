package io.github.joakimkistowski.webserverbuilder;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.websocket.server.ServerEndpointConfig;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.ee10.servlet.DefaultServlet;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.jboss.resteasy.core.SynchronousDispatcher;
import org.jboss.resteasy.core.providerfactory.ResteasyProviderFactoryImpl;
import org.jboss.resteasy.plugins.server.servlet.HttpServletDispatcher;
import org.jboss.resteasy.spi.Dispatcher;
import org.jboss.resteasy.spi.ResteasyProviderFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

/**
 * <p>
 * Web server with builder API for quick and easy construction.
 * Supports Servlets, Jakarta-REST (former JAX-RS), WebSockets, and static file serving.
 * This WebServer is a convenience API, constructed Jetty is exposed and can be accessed at any time after
 * construction using {@link #getJetty()}.
 * </p>
 * <p>
 *      When using the Webserver builder API, elements added to the server must meet the following criteria:
 * </p>
 *     <ul>
 *         <li>Servlets: must specify their URL path using value-property of the {@link WebServlet}-annotation,
 *           other properties of the annotation are ignored.<br>
 *           Optionally, servlets may be annotated with the {@link MultipartConfig}-annotation, which is supported fully.</li>
 *         <li>Filters: must specify their URL path using the value-property of the {@link WebFilter}-annotation,
 *           other properties of the annotation are ignored.</li>
 *         <li>Websocket Endpoints: The Builder-API requires an endpoint factory, which will be called to instantiate an endpoint each
 *         time a websocket connection is established. The endpoints created by the factory must be annotated with the
 *         {@link ServerEndpoint}-annotation.</li>
 *     </ul>
 *
 * <p>
 *     The Web server uses the following defaults:
 * </p>
 *     <ul>
 *         <li>Static file serving is turned on by default</li>
 *         <li>Static files are served from the "static" directory in the classpath</li>
 *         <li>Static files are cached in the web server in a 32 MiB cache</li>
 *         <li>Static files use ETags and set the Cache-Control header to be cached by browsers for 7 days</li>
 *         <li>The default port is 8080</li>
 *     </ul>
 */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class WebServer implements AutoCloseable {

    private static final String PATH_DEFAULT_STATIC_ROOT_DIR = "static";
    private static final String DEFAULT_CONTEXT_ROOT = "/";
    private static final int DEFAULT_PORT = 8080;
    private static final int DEFAULT_CLIENT_BROWSER_MAX_AGE = 7 * 24 * 60 * 60;
    private static final int DEFAULT_MAX_LOCAL_CACHE_SIZE = 32 * 1024 * 1024;

    private WebServerJetty jetty;

    private final List<HttpServlet> servlets;
    private final List<Filter> filters;
    private final List<WebSocketEndpointFactory> webSocketEndpointFactories;
    private final List<String> webSocketSubProtocols;
    private final WebSocketHandshakeModifier webSocketHandshakeModifier;
    private final Application jaxRsApplication;
    private final ErrorHandler errorHandler;
    private final String contextRoot;
    private final String staticContentDir;
    private final int clientBrowserCacheMaxAge;
    private final int maxLocalCacheSize;
    private final boolean staticFileServletDisabled;
    private final int port;

    /**
     * Starts the web server asynchronously as a background thread.
     * You may call {@link #join()} later to join it with your current thread.
     */
    public synchronized void startInBackground() {
        try {
            this.jetty.getServer().start();
        } catch (Exception e) {
            log.error("Error starting server", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Starts the web server synchronously and joins it into the current thread.
     */
    public void start() {
        startInBackground();
        join();
    }

    /**
     * Join the running webserver to the current thread.
     */
    public void join() {
        try {
            this.jetty.getServer().join();
        } catch (InterruptedException e) {
            log.error("Interrupted", e);
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Checks if the server has started.
     * @return True if started. False if not started or still starting.
     */
    public synchronized boolean isStarted() {
        return this.jetty.getServer().isStarted();
    }

    /**
     * Stops the server.
     */
    public synchronized void stop() {
        log.info("Shutting down...");
        try {
            this.jetty.getServer().stop();
        } catch (Exception e) {
            log.error("Error stopping server", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Create a builder to construct a new web server.
     * @return The web server builder.
     */
    public static WebServerBuilder builder() {
        return new WebServerBuilder();
    }

    /**
     * Stop the web server if started.
     */
    @Override
    public void close() {
        if (isStarted()) {
            stop();
        }
    }

    /**
     * Get the container for the underlying Jetty server and its servlet context handler.
     * @return The jetty container.
     */
    public WebServerJetty getJetty() {
        return jetty;
    }

    private void init() {
        this.jetty = new WebServerJetty(new Server(port));

        Arrays.stream(this.jetty.getServer().getConnectors()).map(Connector::getConnectionFactories)
                .forEach(factories -> factories.stream().filter(f -> f instanceof HttpConnectionFactory)
                        .forEach(f -> ((HttpConnectionFactory) f).getHttpConfiguration().setSendServerVersion(false)));
        this.jetty.getContextHandler().setContextPath(servletCompatibleContextRoot(contextRoot));
        this.jetty.getContextHandler().setBaseResource(ResourceFactory.root().newClassLoaderResource(staticContentDir));

        if (servlets != null) {
            servlets.forEach(this::addServlet);
        }

        if (filters != null) {
            filters.forEach(this::addRequestFilter);
        }

        if (webSocketEndpointFactories != null) {
            webSocketEndpointFactories.forEach(ef -> addWebSocketEndpointFactory(ef, webSocketSubProtocols, webSocketHandshakeModifier));
        }

        if (jaxRsApplication != null) {
            configureAndAddJaxRsApplication(jaxRsApplication.getClass());
        }
        if (!staticFileServletDisabled) {
            configureAndAddDefaultServlet();
        }

        this.jetty.getServer().setErrorHandler(errorHandler);
        this.jetty.getServer().setHandler(this.jetty.getContextHandler());
    }

    private <S extends HttpServlet> void addServlet(S servlet) {
        ServletHolder holder = new ServletHolder(servlet);
        var multipartAnnotation = servlet.getClass().getAnnotation(MultipartConfig.class);
        if (multipartAnnotation != null) {
            holder.getRegistration().setMultipartConfig(new MultipartConfigElement(multipartAnnotation));
        }
        WebServlet annotation = servlet.getClass().getAnnotation(WebServlet.class);
        if (annotation == null) {
            throw new IllegalStateException("Servlets must be annotated with @WebServlet");
        }
        Arrays.stream(annotation.value())
                .forEach(path -> this.jetty.getContextHandler().addServlet(holder, path));
    }

    private <F extends Filter> void addRequestFilter(F filter) {
        WebFilter annotation = filter.getClass().getAnnotation(WebFilter.class);
        if (annotation == null) {
            throw new IllegalStateException("Filters must be annotated with @WebFilter");
        }
        Arrays.stream(annotation.value()).forEach(path -> this.jetty.getContextHandler()
                .addFilter(new FilterHolder(filter), path, EnumSet.of(DispatcherType.REQUEST)));
    }

    private <F extends WebSocketEndpointFactory> void addWebSocketEndpointFactory(
            F factory, List<String> webSocketSubProtocols, WebSocketHandshakeModifier handshakeModifier
    ) {
        ServerEndpoint annotation = factory.getEndpointClass().getAnnotation(ServerEndpoint.class);
        if (annotation == null) {
            throw new IllegalStateException("Endpoints created by the WebSocketEndpointFactory must be annotated with @ServerEndpoint");
        }
        ServerEndpointConfig config = ServerEndpointConfig.Builder
                .create(factory.getEndpointClass(), factory.getEndpointClass().getAnnotation(ServerEndpoint.class).value())
                .configurator(new WebSocketServerConfigurator(factory, handshakeModifier)).subprotocols(webSocketSubProtocols).build();
        JakartaWebSocketServletContainerInitializer.configure(this.jetty.getContextHandler(),
                (servletContext, wsContainer) -> wsContainer.addEndpoint(config));
    }

    private <A extends jakarta.ws.rs.core.Application> void configureAndAddJaxRsApplication(Class<A> jaxRsApplicationClass) {
        var providerFactory = new WebServerResteasyProviderFactory<>(this.jaxRsApplication);
        this.jetty.getContextHandler().getServletContext().setAttribute(ResteasyProviderFactory.class.getName(), providerFactory);
        this.jetty.getContextHandler().getServletContext()
                .setAttribute(Dispatcher.class.getName(), new SynchronousDispatcher(providerFactory));

        ServletHolder holder = new ServletHolder(HttpServletDispatcher.class);
        holder.setInitParameter("jakarta.ws.rs.Application", jaxRsApplicationClass.getCanonicalName());
        this.jetty.getContextHandler().setInitParameter("resteasy.servlet.mapping.prefix",
                jaxRsApplicationClass.getAnnotation(ApplicationPath.class).value());
        this.jetty.getContextHandler().addServlet(holder, jaxRsApplicationClass.getAnnotation(ApplicationPath.class).value() + "/*");
    }

    private void configureAndAddDefaultServlet() {
        ServletHolder holder = new ServletHolder(DefaultServlet.class);
        holder.setInitParameter("acceptRanges", String.valueOf(true));
        holder.setInitParameter("dirAllowed", String.valueOf(false));
        holder.setInitParameter("welcomeServlets", String.valueOf(false));
        holder.setInitParameter("etags", String.valueOf(true));
        if (clientBrowserCacheMaxAge >= 0) {
            log.info("Sending max age header for static files. Max age is {} days",
                    ((double) clientBrowserCacheMaxAge) / (24.0 * 60.0 * 60.0));
            holder.setInitParameter("cacheControl", "max-age=" + clientBrowserCacheMaxAge);
        } else {
            log.info("Not sending max age header for static files");
        }
        log.info("Using in-memory cache with size of {} KiB for static files", ((double) maxLocalCacheSize) / 1024.0);
        holder.setInitParameter("maxCacheSize", String.valueOf(maxLocalCacheSize));
        holder.setInitParameter("maxCachedFileSize", String.valueOf(maxLocalCacheSize / 2));
        holder.setInitParameter("maxCachedFiles", String.valueOf(maxLocalCacheSize / 1024));
        this.jetty.getContextHandler().addServlet(holder, "/*");
    }

    private static String servletCompatibleContextRoot(String contextRoot) {
        return contextRoot.isBlank() ? "/" : contextRoot;
    }

    /**
     * Web Server Builder.
     */
    public static class WebServerBuilder {
        private final ArrayList<HttpServlet> servlets = new ArrayList<>();
        private final ArrayList<Filter> filters = new ArrayList<>();
        private final ArrayList<WebSocketEndpointFactory> webSocketEndpointFactories = new ArrayList<>();
        private List<String> webSocketSubProtocols = new ArrayList<>();
        private WebSocketHandshakeModifier webSocketHandshakeModifier = null;
        private Application jakartaRestApplication = null;
        private ErrorHandler errorHandler = null;
        private String contextRoot = DEFAULT_CONTEXT_ROOT;
        private String staticContentDir = PATH_DEFAULT_STATIC_ROOT_DIR;
        private int clientBrowserCacheMaxAge = DEFAULT_CLIENT_BROWSER_MAX_AGE;
        private int maxLocalCacheSize = DEFAULT_MAX_LOCAL_CACHE_SIZE;
        private boolean staticFileServletDisabled = false;
        private int port = DEFAULT_PORT;

        private WebServerBuilder() {
        }

        /**
         * <p>Add a servlet to the web server.</p>
         * <p>
         *     Servlets must specify their URL path using value-property of the {@link WebServlet}-annotation,
         *     other properties of the annotation are ignored.<br>
         *     Optionally, servlets may be annotated with the {@link MultipartConfig}-annotation, which is supported fully.
         * </p>
         * @param servlet The servlet to add.
         * @return The web server builder.
         */
        public WebServerBuilder servlet(HttpServlet servlet) {
            this.servlets.add(servlet);
            return this;
        }

        /**
         * <p>Add servlets to the web server.</p>
         * <p>
         *     Servlets must specify their URL path using value-property of the {@link WebServlet}-annotation,
         *     other properties of the annotation are ignored.<br>
         *     Optionally, servlets may be annotated with the {@link MultipartConfig}-annotation, which is supported fully.
         * </p>
         * @param servlets The servlets to add.
         * @return The web server builder.
         */
        public WebServerBuilder servlets(Collection<? extends HttpServlet> servlets) {
            this.servlets.addAll(servlets);
            return this;
        }

        /**
         * Clears the servlets from the builder.
         * @return The web server builder.
         */
        public WebServerBuilder clearServlets() {
            this.servlets.clear();
            return this;
        }

        /**
         * <p>Add a Filter to the web server. It is added as a REQUEST filter.</p>
         * <p>
         *     Filters must specify their URL path using the value-property of the {@link WebFilter}-annotation,
         *           other properties of the annotation are ignored.
         * </p>
         * @param filter The filter to add.
         * @return The web server builder.
         */
        public WebServerBuilder filter(Filter filter) {
            this.filters.add(filter);
            return this;
        }

        /**
         * <p>Add Filters to the web server. Filters are added as REQUEST filters.</p>
         * <p>
         *     Filters must specify their URL path using the value-property of the {@link WebFilter}-annotation,
         *           other properties of the annotation are ignored.
         * </p>
         * @param filters The filters to add.
         * @return The web server builder.
         */
        public WebServerBuilder filters(Collection<? extends Filter> filters) {
            this.filters.addAll(filters);
            return this;
        }

        /**
         * Clears the filters from the builder.
         * @return The web server builder.
         */
        public WebServerBuilder clearFilters() {
            this.filters.clear();
            return this;
        }

        /**
         * <p>
         *     Adds a web socket endpoint factory to the web server.
         * </p>
         * <p>
         *     The factory is be called to instantiate an endpoint each time a websocket connection is established.
         *     The endpoints created by the factory must be annotated with the {@link ServerEndpoint}-annotation.
         * </p>
         * @param webSocketEndpointFactory The factory to add.
         * @return The web server builder.
         */
        public WebServerBuilder webSocketEndpointFactory(WebSocketEndpointFactory webSocketEndpointFactory) {
            this.webSocketEndpointFactories.add(webSocketEndpointFactory);
            return this;
        }

        /**
         * <p>
         *     Adds web socket endpoint factories to the web server.
         * </p>
         * <p>
         *     The factories are called to instantiate an endpoint each time a websocket connection is established.
         *     The endpoints created by the factories must be annotated with the {@link ServerEndpoint}-annotation.
         * </p>
         * @param webSocketEndpointFactories The factories to add.
         * @return The web server builder.
         */
        public WebServerBuilder webSocketEndpointFactories(Collection<? extends WebSocketEndpointFactory> webSocketEndpointFactories) {
            this.webSocketEndpointFactories.addAll(webSocketEndpointFactories);
            return this;
        }

        /**
         * Clears the web socket factories from the builder.
         * @return The web server builder.
         */
        public WebServerBuilder clearWebSocketEndpointFactories() {
            this.webSocketEndpointFactories.clear();
            return this;
        }

        /**
         * Configures the websocket protocols to be used in web socket protocol negotiation for all web socket endpoints.
         * @param webSocketSubProtocols The web socket protocols.
         * @return The web server builder.
         */
        public WebServerBuilder webSocketSubProtocols(List<String> webSocketSubProtocols) {
            this.webSocketSubProtocols = webSocketSubProtocols;
            return this;
        }

        /**
         * <p>
         *     Configures a custom websocket handshake modifier that may be used to add custom behavior to the websocket handshake process
         *     for all of the server's websocket endpoints.
         * </p>
         * <p>
         *     A common use case for a custom handshake modifier is reading from or writing to the HTTP headers of handshake
         *     requests/responses.
         * </p>
         * <p>
         *     Note: The handshake modifier uses a functional interface and may be implemented as a lambda.
         * </p>
         * @param webSocketHandshakeModifier The web socket handshake modifier.
         * @return The web server builder.
         */
        public WebServerBuilder webSocketHandshakeModifier(WebSocketHandshakeModifier webSocketHandshakeModifier) {
            this.webSocketHandshakeModifier = webSocketHandshakeModifier;
            return this;
        }

        /**
         * Configures a RestEasy REST-Application for the web server.
         * @param jakartaRestApplication The REST-Application.
         * @return The web server builder.
         */
        public WebServerBuilder jakartaRestApplication(Application jakartaRestApplication) {
            this.jakartaRestApplication = jakartaRestApplication;
            return this;
        }

        /**
         * Configures the web server's error handler. The Jetty error handler is used by default.
         * @param errorHandler The error handler.
         * @return The web server builder.
         */
        public WebServerBuilder errorHandler(ErrorHandler errorHandler) {
            this.errorHandler = errorHandler;
            return this;
        }

        /**
         * Configures the context root. It is "/" by default.
         * @param contextRoot The context root.
         * @return The web server builder.
         */
        public WebServerBuilder contextRoot(String contextRoot) {
            this.contextRoot = contextRoot;
            return this;
        }

        /**
         * Configures the directory for static content. The directory path is relative to the classpath. It is "static" by default.
         * @param staticContentDir The static content directory.
         * @return The web server builder.
         */
        public WebServerBuilder staticContentDir(String staticContentDir) {
            this.staticContentDir = staticContentDir;
            return this;
        }

        /**
         * Configures the Cache-Control maxAge that is sent to browsers when serving static files. It is 7 days by default.
         * @param clientBrowserCacheMaxAge The Cache-Control maxAge.
         * @return The web server builder.
         */
        public WebServerBuilder clientBrowserCacheMaxAge(int clientBrowserCacheMaxAge) {
            this.clientBrowserCacheMaxAge = clientBrowserCacheMaxAge;
            return this;
        }

        /**
         * Configures the maximum local cache size for static content (in bytes). It is 32 MiB by default.
         * @param maxLocalCacheSize The cache size.
         * @return The web server builder.
         */
        public WebServerBuilder maxLocalCacheSize(int maxLocalCacheSize) {
            this.maxLocalCacheSize = maxLocalCacheSize;
            return this;
        }

        /**
         * Disables static file serving when set to true. Static file serving is enabled by default.
         * @param staticFileServletDisabled If static file serving is to be disabled.
         * @return The web server builder.
         */
        public WebServerBuilder staticFileServletDisabled(boolean staticFileServletDisabled) {
            this.staticFileServletDisabled = staticFileServletDisabled;
            return this;
        }

        /**
         * Sets the web server port. It is 8080 by default
         * @param port The port.
         * @return The web server builder.
         */
        public WebServerBuilder port(int port) {
            this.port = port;
            return this;
        }

        /**
         * Constructs the final web server.
         * Note that the server's internal Jetty may still be further modified before starting the server.
         * @return The web server, ready to start!
         */
        public WebServer build() {
            WebServer ws = new WebServer(
                    Collections.unmodifiableList(this.servlets),
                    Collections.unmodifiableList(this.filters),
                    Collections.unmodifiableList(this.webSocketEndpointFactories),
                    Collections.unmodifiableList(this.webSocketSubProtocols),
                    this.webSocketHandshakeModifier, this.jakartaRestApplication, this.errorHandler, this.contextRoot, this.staticContentDir,
                    this.clientBrowserCacheMaxAge, this.maxLocalCacheSize, this.staticFileServletDisabled, this.port
            );
            ws.init();
            return ws;
        }
    }

    @RequiredArgsConstructor
    private static class WebServerResteasyProviderFactory<A> extends ResteasyProviderFactoryImpl {

        private final A jaxRsApplication;

        @Override
        @SuppressWarnings("unchecked")
        public <T> T createProviderInstance(Class<? extends T> clazz) {
            var jaxRsClass = this.jaxRsApplication.getClass();
            if (jaxRsClass.equals(clazz)) {
                return (T) this.jaxRsApplication;
            }
            return super.createProviderInstance(clazz);
        }
    }

    @RequiredArgsConstructor
    private static class WebSocketServerConfigurator extends ServerEndpointConfig.Configurator {
        private final WebSocketEndpointFactory endpointInstanceFactory;
        private final WebSocketHandshakeModifier handshakeModifier;

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getEndpointInstance(Class<T> endpointClass) throws InstantiationException {
            if (!endpointInstanceFactory.getEndpointClass().equals(endpointClass)) {
                throw new InstantiationException("getEndpointClass() returns wrong class");
            }
            T endpoint = (T) endpointInstanceFactory.createNewEndpointInstanceForConnection();
            if (!endpoint.getClass().equals(endpointClass)) {
                throw new InstantiationException("Factory created instance is not of expected type (" + endpointClass + ")");
            }
            return endpoint;
        }

        @Override
        public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response) {
            if (handshakeModifier != null) {
                handshakeModifier.modifyHandshake(sec, request, response);
            }
        }
    }
}
