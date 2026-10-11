package com.inditex.similarproducts.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal stand-in for the external product APIs, built on the JDK's own HTTP server.
 * Unknown paths answer 404, like the real mocks.
 */
public class FakeProductApi implements AutoCloseable {

    private record Response(int status, String body, Duration delay) {
    }

    private final HttpServer server;
    private final Map<String, Response> responses = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

    public FakeProductApi() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public FakeProductApi respond(String path, int status, String body) {
        return respond(path, status, body, Duration.ZERO);
    }

    public FakeProductApi respond(String path, int status, String body, Duration delay) {
        responses.put(path, new Response(status, body, delay));
        return this;
    }

    public int hits(String path) {
        return hits.getOrDefault(path, new AtomicInteger()).get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        hits.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
        Response response = responses.getOrDefault(path, new Response(404, "", Duration.ZERO));

        try {
            Thread.sleep(response.delay());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
