# webserver-builder

Web server with builder API for quick and easy construction of an embedded Jetty web server.

[![Maven Central](https://img.shields.io/maven-central/v/io.github.joakimkistowski/webserver-builder.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22io.github.joakimkistowski%22%20AND%20a:%22webserver-builder%22)
![GitHub](https://img.shields.io/github/license/joakimkistowski/webserver-builder)

`webserver-builder` supports Servlets, Jakarta-REST (former JAX-RS), WebSockets, and static file serving. The API is designed to always take instantiated objects instead of classes. This allows users to apply common design patterns, such as dependency injection using constructors.
In addition, the constructed Jetty is exposed and can be accessed at any time after  construction for further configuration.

## How to use

Get the library from maven central.
```xml
<dependency>
    <groupId>io.github.joakimkistowski</groupId>
    <artifactId>webserver-builder</artifactId>
    <version>0.3.21</version>
</dependency>
```

Then build and start a web server.

```java
WebServer webServer = WebServer.builder()
    // Set the context root, it is "/" by default
    .contextRoot("/contextroot")
    // Add a Servlet; must define its url path spec using the @WebServlet-annotation
    .servlet(new MyHttpServlet())
    // Disable static file serving, it is enabled by default
    .staticFileServletDisabled(true)
    // Pass a custom error handler, uses the built-in Jetty error handler by default
    .errorHandler(new MyErrorHandler())
    // Specify the port, it is 8080 by default
    .port(8081)
    .build()
// Start the web server
// Alternatively, use "startInBackground" to start it asynchronously in the background
webServer.start();
        ...
// The web server is auto closable, you may initialize it in a try-with-resources statement,
// making manual stopping unnecessary.
webServer.stop();
```

### Serving Servlets and Filters

Jakarta HTTPServlets and Filters can be added using the webserver using the builder API.

Servlets must specify at least one URL path using value-property of the `WebServlet`-annotation. Other properties of the annotation are ignored.  
Optionally, servlets may be annotated with the `MultipartConfig`-annotation, which is supported fully.

Similarly, Filters must specify their URL path using the value-property of the `WebFilter`-annotation,  other properties of the annotation are ignored.

### Serving Static Content

Static content is served automatically from the directory name `static` in the classpath. You can disable static file serving by calling `staticFileServletDisabled(true)` on the builder. Static file serving is pre-configured to use ETags.

You can modify the following configurations regarding static file serving:
* Static files are served from the `static` directory in the classpath.  
  Change the directory using the builder's `staticContentDir()`-method.
* Static files are cached in the web server in a 32 MiB cache.  
  You can modify the cache size by passing the new size (in bytes) to `maxLocalCacheSize()`
* Static files use ETags and set the Cache-Control header telling the browser to cache static content for 7 days.  
  You can modify the `maxAge` Cache-Control property using `clientBrowserCacheMaxAge()`. A negative max age will cause the header to be omitted.

### Serving REST APIs

The web server supports configuration of a Jakarta REST application using RestEasy.

Add the following libraries to your `pom`:
* `org.jboss.resteasy`:`resteasy-servlet-initializer`, with a version > 6.0.0
* At least one media provider for the media type you intend to use. E.g., for JSON you might add: `org.jboss.resteasy`:`resteasy-jackson2-provider` 

Then create a REST Application.

```java
@ApplicationPath("/myapipath")
public class ExampleRestApplication extends Application {
    
    // The web server builder takes instantiated objects,
    // so feel free to pass anything to the constructor. 
    public ExampleRestApplication() {
    }
    
    @Override
    public Set<Object> getSingletons() {
        return Set.of(
                // A media provider, e.g., for JSON using Jackson
                new JacksonJsonProvider(),
                // A REST resource
                new ExampleResource()
        );
    }
}
```

You can then pass the application to the web server builder:

```java
try (
        WebServer webServer = WebServer.builder()
        .jakartaRestApplication(new ExampleRestApplication())
        .build()
    ) {
        webServer.start();
        ...
// The webserver automatically stops once the try block exits
}

```

### Serving Web Sockets

The web server supports Jakarta web sockets.

Add the following library to your `pom`:
* `org.eclipse.jetty.ee10.websocket`:`jetty-ee10-websocket-jakarta-server`

To use web sockets, define an endpoint factory, which will be called to instantiate a web socket endpoint each time a websocket connection is established.

```java
public class MyWebSocketEndpointFactory implements WebSocketEndpointFactory {
    
    @Override
    public Object createNewEndpointInstanceForConnection() {
        return new MyWebsocketEndpoint();
    }
    
    @Override
    public Class<?> getEndpointClass() {
        return MyWebsocketEndpoint.class;
    }
}
```

The endpoints created by the factory are regular Jakarta web socket endpoints and, as such, must be annotated with the  `ServerEndpoint`-annotation.

Regarding web sockets, the web server builder supports:
* Specification of the web socket subprotocols for subprotocol negotiation.  
  Specify the available subprotocols using the builder's `webSocketSubProtocols()`-method.
* Customizing the web socket handshake by passing a custom `WebSocketHandshakeModifier` to the builder's `webSocketHandshakeModifier()`-method.

### Allowing Multipart File Upload

The web server supports multipart file upload via the Jakarta EE `@MultipartConfig` annotation.  
Note, that you must specify a location for the uploaded file to be stored using the annotation's `location`-Property (since Jetty 12). Set a high `fileSizeThreshold` if you want Jetty to keep uploaded files to memory instead of writing them to disk. 