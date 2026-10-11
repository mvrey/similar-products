package com.inditex.similarproducts.infrastructure.client;

import com.inditex.similarproducts.domain.exception.ProductCatalogUnavailableException;
import com.inditex.similarproducts.domain.exception.ProductNotFoundException;
import com.inditex.similarproducts.domain.model.Product;
import com.inditex.similarproducts.domain.model.ProductId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpProductCatalogTest {

    private static final String BASE_URL = "http://localhost:3001";

    private MockRestServiceServer server;
    private HttpProductCatalog catalog;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        catalog = new HttpProductCatalog(builder.build());
    }

    @Test
    void findsSimilarIdsInOrder() {
        server.expect(requestTo(BASE_URL + "/product/1/similarids"))
                .andRespond(withSuccess("[2,3,4]", MediaType.APPLICATION_JSON));

        assertThat(catalog.findSimilarIds(new ProductId("1")))
                .containsExactly(new ProductId("2"), new ProductId("3"), new ProductId("4"));
    }

    @Test
    void throwsNotFoundWhenProductHasNoSimilarIds() {
        server.expect(requestTo(BASE_URL + "/product/99/similarids"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> catalog.findSimilarIds(new ProductId("99")))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void throwsUnavailableWhenSimilarIdsServiceFails() {
        server.expect(requestTo(BASE_URL + "/product/1/similarids"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> catalog.findSimilarIds(new ProductId("1")))
                .isInstanceOf(ProductCatalogUnavailableException.class);
    }

    @Test
    void throwsUnavailableWhenSimilarIdsContainABlankId() {
        server.expect(requestTo(BASE_URL + "/product/1/similarids"))
                .andRespond(withSuccess("[\"2\",\"\"]", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> catalog.findSimilarIds(new ProductId("1")))
                .isInstanceOf(ProductCatalogUnavailableException.class);
    }

    @Test
    void throwsUnavailableWhenSimilarIdsContainANullId() {
        server.expect(requestTo(BASE_URL + "/product/1/similarids"))
                .andRespond(withSuccess("[\"2\",null]", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> catalog.findSimilarIds(new ProductId("1")))
                .isInstanceOf(ProductCatalogUnavailableException.class);
    }

    @Test
    void findsProductDetail() {
        server.expect(requestTo(BASE_URL + "/product/1"))
                .andRespond(withSuccess("""
                        {"id":"1","name":"Shirt","price":9.99,"availability":true}
                        """, MediaType.APPLICATION_JSON));

        Optional<Product> product = catalog.findById(new ProductId("1"));

        assertThat(product).hasValueSatisfying(p -> {
            assertThat(p.id()).isEqualTo(new ProductId("1"));
            assertThat(p.name()).isEqualTo("Shirt");
            assertThat(p.price()).isEqualByComparingTo("9.99");
            assertThat(p.availability()).isTrue();
        });
    }

    @Test
    void returnsEmptyWhenProductNotFound() {
        server.expect(requestTo(BASE_URL + "/product/5"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(catalog.findById(new ProductId("5"))).isEmpty();
    }

    @Test
    void throwsUnavailableWhenProductServiceFails() {
        server.expect(requestTo(BASE_URL + "/product/6"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> catalog.findById(new ProductId("6")))
                .isInstanceOf(ProductCatalogUnavailableException.class);
    }

    @Test
    void throwsUnavailableWhenProductDetailIsInvalid() {
        server.expect(requestTo(BASE_URL + "/product/7"))
                .andRespond(withSuccess("""
                        {"id":"7","name":"","price":1.0,"availability":true}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> catalog.findById(new ProductId("7")))
                .isInstanceOf(ProductCatalogUnavailableException.class);
    }

    @Test
    void throwsUnavailableWhenProductAvailabilityIsMissing() {
        server.expect(requestTo(BASE_URL + "/product/8"))
                .andRespond(withSuccess("""
                        {"id":"8","name":"Shirt","price":9.99}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> catalog.findById(new ProductId("8")))
                .isInstanceOf(ProductCatalogUnavailableException.class);
    }

    @Test
    void throwsUnavailableWhenProductPriceIsMissing() {
        server.expect(requestTo(BASE_URL + "/product/9"))
                .andRespond(withSuccess("""
                        {"id":"9","name":"Shirt","availability":true}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> catalog.findById(new ProductId("9")))
                .isInstanceOf(ProductCatalogUnavailableException.class);
    }
}
