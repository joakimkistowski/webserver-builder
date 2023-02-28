package io.github.joakimkistowski.webserverbuilder.testrestapplication;

import com.fasterxml.jackson.jakarta.rs.json.JacksonJsonProvider;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.MediaType;

import java.util.Set;

@ApplicationPath("/api")
public class TestRestApplication extends Application {
    @Override
    public Set<Object> getSingletons() {
        return Set.of(new JacksonJsonProvider(), new TestRestApi());
    }

    @Path("/")
    @Produces({MediaType.APPLICATION_JSON})
    @Consumes({MediaType.APPLICATION_JSON})
    public static class TestRestApi {
        @GET
        @Path("/test")
        public TestDto getInfo() {
            return new TestDto("testjaxrsmessage");
        }

    }
}