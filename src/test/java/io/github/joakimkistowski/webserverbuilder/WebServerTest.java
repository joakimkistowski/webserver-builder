package io.github.joakimkistowski.webserverbuilder;

import io.github.joakimkistowski.webserverbuilder.testrestapplication.TestRestApplication;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import lombok.RequiredArgsConstructor;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class WebServerTest {

    public static int TEST_PORT = 8062;
    private static final String TEST_FILE_URI = "/test.txt";
    private static final String TEST_REST_URI = "/api/test";
    private static final String HEADER_TEST_HEADER = "X-Test-Header";

    private static final HttpClient TEST_CLIENT = HttpClient.newHttpClient();

    @Test
    void givenServletsAndStaticFiles_whenBuildingAndStartingWebServer_thenServletsAndFilesAreServed() throws IOException {
        // given
        HttpServlet servlet0 = new TestServlet0();
        HttpServlet servlet1 = new TestServlet1();

        // when
        try (var webServer = WebServer.builder().port(TEST_PORT)
                .contextRoot("/asdf")
                .servlet(servlet0).servlet(servlet1)
                .clientBrowserCacheMaxAge(2002).maxLocalCacheSize(10 * 1024)
                .staticContentDir("statictest").build()) {
            WebServerTestUtils.startWebServerAndWaitUntilStarted(webServer);

            // then
            assertThat(webServer.isStarted()).isTrue();
            assertThat(webServer.getJetty().getServer().isStarted()).isTrue();
            HttpResponse<String> response = WebServerTestUtils.blockingSend(
                    TEST_CLIENT, WebServerTestUtils.GET("localhost", TEST_PORT, "/asdf/test0"));
            assertThat(response.body()).isEqualTo("hello 0");
            response = WebServerTestUtils.blockingSend(TEST_CLIENT, WebServerTestUtils.GET(
                    "localhost", TEST_PORT, "/asdf/test1"));
            assertThat(response.body()).isEqualTo("hello 1");
            response = WebServerTestUtils.blockingSend(TEST_CLIENT, WebServerTestUtils.GET(
                    "localhost", TEST_PORT, "/asdf" + TEST_FILE_URI));
            assertThat(response.body()).contains("test");
            assertThat(response.headers().firstValue(HttpHeader.CACHE_CONTROL.asString())).isPresent();
            assertThat(response.headers().firstValue(HttpHeader.CACHE_CONTROL.asString()).orElse(null)).contains("max-age=2002");
        }
    }

    @Test
    void givenServletWithoutAnnotation_whenBuildingServer_thenExceptionIsThrown() {
        // given
        HttpServlet servlet = new HttpServlet() {
        };
        // when, then
        assertThrows(IllegalStateException.class, () -> WebServer.builder().servlet(servlet).build());
    }

    @Test
    void givenFilterWithoutAnnotation_whenBuildingServer_thenExceptionIsThrown() {
        // given
        HttpFilter filter = new HttpFilter() {
        };
        // when, then
        assertThrows(IllegalStateException.class, () -> WebServer.builder().filter(filter).build());
    }

    @Test
    void givenWebsocketEndpointWithoutAnnotation_whenBuildingServer_thenExceptionIsThrown() {
        // given
        WebSocketEndpointFactory factory = new WebSocketEndpointFactory() {
            @Override
            public Object createNewEndpointInstanceForConnection() {
                return new NoAnnotationWebsocketEndpoint();
            }

            @Override
            public Class<?> getEndpointClass() {
                return NoAnnotationWebsocketEndpoint.class;
            }
        };
        // when, then
        assertThrows(IllegalStateException.class, () -> WebServer.builder().webSocketEndpointFactory(factory).build());
    }

    @Test
    void givenDefaultErrorHandlerAndNoStaticFiles_whenBuildingAndStartingWebServer_thenErrorsAreHandled() throws IOException {
        // when
        try (var webServer = WebServer.builder().port(TEST_PORT).staticFileServletDisabled(true).build()) {
            WebServerTestUtils.startWebServerAndWaitUntilStarted(webServer);

            // then
            assertThat(webServer.isStarted()).isTrue();
            HttpResponse<String> response = WebServerTestUtils.blockingSend(
                    TEST_CLIENT, WebServerTestUtils.GET("localhost", TEST_PORT, "/test0"));
            assertThat(response.statusCode()).isEqualTo(HttpServletResponse.SC_NOT_FOUND);
            // Contains default Jetty Error message
            assertThat(response.body()).contains("<h2>HTTP ERROR 404 Not Found</h2>");
        }
    }

    @Test
    void givenCustomErrorHandlerAndNoStaticFiles_whenBuildingAndStartingWebServer_thenErrorsAreHandled() throws IOException {
        // when
        try (var webServer = WebServer.builder().port(TEST_PORT).staticFileServletDisabled(true).errorHandler(new TestErrorHandler()).build()) {
            WebServerTestUtils.startWebServerAndWaitUntilStarted(webServer);

            // then
            assertThat(webServer.isStarted()).isTrue();
            HttpResponse<String> response = WebServerTestUtils.blockingSend(
                    TEST_CLIENT, WebServerTestUtils.GET("localhost", TEST_PORT, TEST_FILE_URI));
            assertThat(response.statusCode()).isEqualTo(HttpServletResponse.SC_NOT_FOUND);
            assertThat(response.body()).doesNotContain("<h2>HTTP ERROR 404 Not Found</h2>");
            assertThat(response.body()).contains("<h3>Custom Error: 404</h3>");
        }
    }


    @Test
    void givenJaxRsApplication_whenBuildingAndStartingWebServer_thenJaxRsApplicationWorks() throws IOException {
        // when
        try (var webServer = WebServer.builder().port(TEST_PORT).jakartaRestApplication(new TestRestApplication()).build()) {
            WebServerTestUtils.startWebServerAndWaitUntilStarted(webServer);

            // then
            assertThat(webServer.isStarted()).isTrue();
            HttpResponse<String> response = WebServerTestUtils.blockingSend(
                    TEST_CLIENT, WebServerTestUtils.GET("localhost", TEST_PORT, TEST_REST_URI));
            assertThat(response.statusCode()).isEqualTo(HttpServletResponse.SC_OK);
            assertThat(response.body()).contains("testjaxrsmessage");
        }
    }

    @Test
    void givenFilter_whenBuildingAndStartingWebServer_thenFilter() throws IOException {
        // when
        try (var webServer = WebServer.builder().port(TEST_PORT).filter(new TestFilter()).build()) {
            WebServerTestUtils.startWebServerAndWaitUntilStarted(webServer);

            // then
            assertThat(webServer.isStarted()).isTrue();
            HttpResponse<String> response = WebServerTestUtils.blockingSend(TEST_CLIENT, WebServerTestUtils.GET("localhost", TEST_PORT, TEST_FILE_URI));
            assertThat(response.statusCode()).isEqualTo(HttpServletResponse.SC_OK);
            assertThat(response.headers().allValues(HEADER_TEST_HEADER)).containsExactly("test-value");
        }
    }

    @Test
    void givenFilters_whenBuildingAndStartingWebServer_thenFilters() throws IOException {
        // when
        try (var webServer = WebServer.builder().port(TEST_PORT)
                .filters(List.of(new TestFilter())).build()) {
            WebServerTestUtils.startWebServerAndWaitUntilStarted(webServer);

            // then
            HttpResponse<String> response = WebServerTestUtils.blockingSend(TEST_CLIENT, WebServerTestUtils.GET("localhost", TEST_PORT, TEST_FILE_URI));
            assertThat(response.headers().allValues(HEADER_TEST_HEADER)).containsExactly("test-value");
        }
    }

    @Test
    void givenInconsistentWebsocketFactory_whenBuildingAndStartingWebServer_thenInstantiationException() {
        // given
        try (var webServer = WebServer.builder().port(TEST_PORT).webSocketEndpointFactory(
                new WebSocketEndpointFactory() {
                    public Object createNewEndpointInstanceForConnection() {
                        return new TestWebsocketEndpoint();
                    }

                    public Class<?> getEndpointClass() {
                        return FakeWebsocketEndpoint.class;
                    }
                }).build()) {
            WebServerTestUtils.startWebServerAndWaitUntilStarted(webServer);
            assertThat(webServer.isStarted()).isTrue();
            // when, then
            var e = assertThrows(CompletionException.class, () -> HttpClient
                    .newHttpClient()
                    .newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + TEST_PORT + "/ws"), new TestWebsocketClient(null))
                    .join());
            assertThat(e).hasCauseExactlyInstanceOf(WebSocketHandshakeException.class);

        }
    }

    @Test
    void givenWebsocketFactory_whenBuildingAndStartingWebServer_thenWebsocketWorks() throws Exception {
        // given
        try (var webServer = WebServer.builder().port(TEST_PORT).webSocketEndpointFactory(
                new WebSocketEndpointFactory() {
                    public Object createNewEndpointInstanceForConnection() {
                        return new TestWebsocketEndpoint();
                    }

                    public Class<?> getEndpointClass() {
                        return TestWebsocketEndpoint.class;
                    }
                }).build()) {
            WebServerTestUtils.startWebServerAndWaitUntilStarted(webServer);
            BlockingQueue<String> textReceiverQueue = new BlockingArrayQueue<>();
            assertThat(webServer.isStarted()).isTrue();
            // when
            WebSocket clientWebSocket = HttpClient
                    .newHttpClient()
                    .newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + TEST_PORT + "/ws"), new TestWebsocketClient(textReceiverQueue))
                    .join();
            clientWebSocket.sendText("hello", true);
            // then
            assertThat(textReceiverQueue.take()).isEqualTo("connected");
            assertThat(textReceiverQueue.take()).isEqualTo("Echo: hello");
        }
    }

    @Test
    void givenWebsocketFactoryAndHandshakeModifier_whenBuildingAndStartingWebServer_thenWebsocketWorks() throws Exception {
        // given
        AtomicReference<String> headerReceived = new AtomicReference<>("");
        try (var webServer = WebServer.builder().port(TEST_PORT).webSocketEndpointFactory(
                        new WebSocketEndpointFactory() {
                            public Object createNewEndpointInstanceForConnection() {
                                return new TestWebsocketEndpoint();
                            }

                            public Class<?> getEndpointClass() {
                                return TestWebsocketEndpoint.class;
                            }
                        }).webSocketHandshakeModifier((config, req, resp) -> headerReceived
                        .set(req.getHeaders().get(HEADER_TEST_HEADER).get(0)))
                .webSocketSubProtocols(List.of("subprotocol"))
                .build()) {
            WebServerTestUtils.startWebServerAndWaitUntilStarted(webServer);
            BlockingQueue<String> textReceiverQueue = new BlockingArrayQueue<>();
            assertThat(webServer.isStarted()).isTrue();
            // when
            WebSocket clientWebSocket = HttpClient
                    .newHttpClient()
                    .newWebSocketBuilder()
                    .subprotocols("subprotocol")
                    .header(HEADER_TEST_HEADER, "test")
                    .buildAsync(URI.create("ws://localhost:" + TEST_PORT + "/ws"), new TestWebsocketClient(textReceiverQueue))
                    .join();
            clientWebSocket.sendText("hello", true);
            // then
            assertThat(textReceiverQueue.take()).isNotBlank();
            assertThat(textReceiverQueue.take()).isNotBlank();
            assertThat(headerReceived.get()).isEqualTo("test");
        }
    }

    @WebServlet("/test0")
    private static class TestServlet0 extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().print("hello 0");
        }
    }

    @WebServlet("/test1")
    private static class TestServlet1 extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().print("hello 1");
        }
    }

    @WebFilter("/*")
    private static class TestFilter implements Filter {

        @Override
        public void init(FilterConfig filterConfig) {
        }

        @Override
        public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) {
            ((HttpServletResponse) servletResponse).addHeader(HEADER_TEST_HEADER, "test-value");
        }

        @Override
        public void destroy() {
        }
    }

    @ServerEndpoint("/ws")
    public static class TestWebsocketEndpoint {
        @OnOpen
        public void onOpen(final Session session) throws IOException {
            session.getBasicRemote().sendText("connected");
        }

        @OnMessage
        public void onMessage(final Session session, String message) throws IOException {
            session.getBasicRemote().sendText("Echo: " + message);
        }

        @OnClose
        public void onClose(final Session session) throws IOException {
            session.close();
        }
    }

    @ServerEndpoint("/ws")
    public static class FakeWebsocketEndpoint {
    }

    @RequiredArgsConstructor
    private static class TestWebsocketClient implements WebSocket.Listener {

        final BlockingQueue<String> receivedTextQueue;

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            receivedTextQueue.add(data.toString());
            return WebSocket.Listener.super.onText(webSocket, data, last);

        }
    }

    public static class NoAnnotationWebsocketEndpoint {
    }

    public static class TestErrorHandler extends ErrorHandler {
        @Override
        protected void writeErrorHtmlMessage(Request request, Writer writer, int code, String message, Throwable cause, String uri) throws IOException {
            writer.write("<h3>Custom Error: ");
            writer.write(String.valueOf(code));
            writer.write("</h3>\n");
        }
    }

}
