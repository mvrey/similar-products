package com.inditex.similarproducts;

import com.inditex.similarproducts.support.FakeProductApi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Runs the whole application on a random port against a fake product API.
 * <p>
 * All tests share one application context, so its caches and circuit breakers keep their state between
 * tests. To stay independent, each test uses its own range of product ids (1x, 2x, 3x...).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "product-api.read-timeout=2s",
        "product-catalog.lookup-timeout=500ms"
})
class SimilarProductsEndToEndTest {

    private static final FakeProductApi productApi = new FakeProductApi();

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void productApiUrl(DynamicPropertyRegistry registry) {
        registry.add("product-api.base-url", productApi::baseUrl);
    }

    @AfterAll
    static void stopProductApi() {
        productApi.close();
    }

    @Test
    void returnsSimilarProductsInSimilarityOrder() throws Exception {
        productApi.respond("/product/1/similarids", 200, "[2,3,4]")
                .respond("/product/2", 200, product("2", "Dress", "19.99", true))
                .respond("/product/3", 200, product("3", "Blazer", "29.99", false))
                .respond("/product/4", 200, product("4", "Boots", "39.99", true));

        HttpResponse<String> response = get("/product/1/similar");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/json"));
        JSONAssert.assertEquals("""
                [
                  {"id":"2","name":"Dress","price":19.99,"availability":true},
                  {"id":"3","name":"Blazer","price":29.99,"availability":false},
                  {"id":"4","name":"Boots","price":39.99,"availability":true}
                ]
                """, response.body(), JSONCompareMode.STRICT);
    }

    @Test
    void skipsSimilarProductsThatAreMissingOrFailing() throws Exception {
        productApi.respond("/product/10/similarids", 200, "[11,12,13]")
                .respond("/product/11", 200, product("11", "Shirt", "9.99", true))
                .respond("/product/12", 404, "{\"message\":\"Product not found\"}")
                .respond("/product/13", 500, "");

        HttpResponse<String> response = get("/product/10/similar");

        assertThat(response.statusCode()).isEqualTo(200);
        JSONAssert.assertEquals("""
                [{"id":"11","name":"Shirt","price":9.99,"availability":true}]
                """, response.body(), JSONCompareMode.STRICT);
    }

    @Test
    void skipsSlowProductAndServesItFromCacheOnceItArrives() throws Exception {
        productApi.respond("/product/20/similarids", 200, "[21,22]")
                .respond("/product/21", 200, product("21", "Shirt", "9.99", true))
                .respond("/product/22", 200, product("22", "Coat", "89.99", true), Duration.ofSeconds(1));

        HttpResponse<String> first = get("/product/20/similar");
        JSONAssert.assertEquals("[{\"id\":\"21\"}]", first.body(), JSONCompareMode.STRICT_ORDER);

        // The upstream call keeps running after the first response and fills the cache
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> JSONAssert.assertEquals(
                "[{\"id\":\"21\"},{\"id\":\"22\"}]", get("/product/20/similar").body(), JSONCompareMode.STRICT_ORDER));
        assertThat(productApi.hits("/product/22")).isEqualTo(1);
    }

    @Test
    void servesRepeatedRequestsFromCache() throws Exception {
        productApi.respond("/product/30/similarids", 200, "[31]")
                .respond("/product/31", 200, product("31", "Boots", "39.99", true));

        get("/product/30/similar");
        get("/product/30/similar");
        get("/product/30/similar");

        assertThat(productApi.hits("/product/30/similarids")).isEqualTo(1);
        assertThat(productApi.hits("/product/31")).isEqualTo(1);
    }

    @Test
    void returnsEmptyListWhenThereAreNoSimilarProducts() throws Exception {
        productApi.respond("/product/40/similarids", 200, "[]");

        HttpResponse<String> response = get("/product/40/similar");

        assertThat(response.statusCode()).isEqualTo(200);
        JSONAssert.assertEquals("[]", response.body(), JSONCompareMode.STRICT);
    }

    @Test
    void returns404ForUnknownProduct() throws Exception {
        HttpResponse<String> response = get("/product/999/similar");

        assertThat(response.statusCode()).isEqualTo(404);
        JSONAssert.assertEquals("""
                {"status":404,"title":"Product not found","instance":"/product/999/similar"}
                """, response.body(), JSONCompareMode.LENIENT);
    }

    @Test
    void returns502WhenSimilarIdsServiceFails() throws Exception {
        productApi.respond("/product/50/similarids", 500, "");

        HttpResponse<String> response = get("/product/50/similar");

        assertThat(response.statusCode()).isEqualTo(502);
        JSONAssert.assertEquals("{\"status\":502}", response.body(), JSONCompareMode.LENIENT);
    }

    @Test
    void returns502WhenSimilarIdsServiceSendsAnInvalidId() throws Exception {
        productApi.respond("/product/60/similarids", 200, "[\"61\",\"\"]");

        HttpResponse<String> response = get("/product/60/similar");

        assertThat(response.statusCode()).isEqualTo(502);
    }

    @Test
    void exposesCacheAndResilienceMetrics() throws Exception {
        productApi.respond("/product/70/similarids", 200, "[]");
        get("/product/70/similar");

        assertThat(get("/actuator/metrics/cache.gets?tag=cache:similar-ids").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/metrics/resilience4j.circuitbreaker.state").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/metrics/resilience4j.bulkhead.available.concurrent.calls").statusCode())
                .isEqualTo(200);
        assertThat(get("/actuator/metrics/product.catalog.product.breakers.open").statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String product(String id, String name, String price, boolean availability) {
        return """
                {"id":"%s","name":"%s","price":%s,"availability":%s}
                """.formatted(id, name, price, availability);
    }
}
