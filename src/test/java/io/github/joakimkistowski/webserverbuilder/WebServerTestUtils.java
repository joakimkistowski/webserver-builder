package io.github.joakimkistowski.webserverbuilder;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class WebServerTestUtils {

    private static final String HTTP_CLIENT_DEFAULT_SCHEME = "http";

    public static void startWebServerAndWaitUntilStarted(WebServer server) {
        server.startInBackground();
        while (!server.isStarted()) {
        }
    }

    public static HttpResponse<String> blockingSend(HttpClient client, HttpRequest request) throws IOException {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted sending with HTTP Client", e);
        }
    }

    public static HttpRequest GET(String host, int port, String uri) {
        return GET(HTTP_CLIENT_DEFAULT_SCHEME, host, port, uri);
    }

    public static HttpRequest GET(String scheme, String host, int port, String uri) {
        return HttpRequest.newBuilder(uriOf(scheme, host, port, uri)).GET().build();
    }

    public static URI uriOf(String scheme, String host, int port, String uri) {
        String tail = uri;
        if (!tail.startsWith("/")) {
            tail = "/" + tail;
        }
        return URI.create(scheme + "://" + host + ":" + port + tail);
    }
}
