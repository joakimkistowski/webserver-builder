package io.github.joakimkistowski.webserverbuilder.testservlets;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collection;

@WebServlet("/multipart")
@MultipartConfig(maxFileSize = 1024, fileSizeThreshold = 1024, location = "/tmp")
public class MultipartServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException {
        try {
            Collection<Part> parts = req.getParts();
            if (parts == null || parts.isEmpty()) {
                throw new IllegalStateException("Null/empty multipart data");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Error getting parts from multipart upload", e);
        }
        resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }
}
