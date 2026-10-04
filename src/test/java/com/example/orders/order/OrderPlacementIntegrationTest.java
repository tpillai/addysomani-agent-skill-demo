package com.example.orders.order;

import com.example.orders.product.Product;
import com.example.orders.product.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs placeOrder through the real transaction and H2 database. The unit and
 * WebMvc tests mock the service and repositories, so only this test proves that
 * stock changes are persisted, rolled back on failure, and protected by @Version.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderPlacementIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void placingAnOrderPersistsTheStockDecrease() {
        Product webcam = productRepository.save(new Product("Webcam", new BigDecimal("59.99"), 10));

        ResponseEntity<Map> response = placeOrder(webcam.getId(), 3);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(stockOf(webcam)).isEqualTo(7);
    }

    @Test
    void placedOrderReadsBackWithTheSameBody() {
        Product webcam = productRepository.save(new Product("Webcam", new BigDecimal("59.99"), 10));

        ResponseEntity<Map> created = placeOrder(webcam.getId(), 2);
        ResponseEntity<Map> fetched = restTemplate.getForEntity(created.getHeaders().getLocation(), Map.class);

        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isEqualTo(created.getBody());
    }

    @Test
    void rejectedOrderChangesNoStockAndSavesNoOrder() {
        Product webcam = productRepository.save(new Product("Webcam", new BigDecimal("59.99"), 10));
        Product lamp = productRepository.save(new Product("Desk lamp", new BigDecimal("34.50"), 1));
        long ordersBefore = orderRepository.count();

        ResponseEntity<Map> response = post("""
                {"lines":[{"productId":%d,"quantity":2},{"productId":%d,"quantity":5}]}"""
                .formatted(webcam.getId(), lamp.getId()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stockOf(webcam)).isEqualTo(10);
        assertThat(stockOf(lamp)).isEqualTo(1);
        assertThat(orderRepository.count()).isEqualTo(ordersBefore);
    }

    @Test
    void concurrentOrdersNeverOversellOrLoseAnUpdate() throws Exception {
        int initialStock = 3;
        int requests = 10;
        Product monitor = productRepository.save(new Product("Monitor", new BigDecimal("249.00"), initialStock));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requests);
        List<Future<HttpStatusCode>> results = new ArrayList<>();
        try {
            Callable<HttpStatusCode> order = () -> {
                start.await();
                return placeOrder(monitor.getId(), 1).getStatusCode();
            };
            for (int i = 0; i < requests; i++) {
                results.add(executor.submit(order));
            }
            start.countDown();

            List<HttpStatusCode> statuses = new ArrayList<>();
            for (Future<HttpStatusCode> result : results) {
                statuses.add(result.get());
            }

            long created = statuses.stream().filter(HttpStatus.CREATED::equals).count();
            long conflicts = statuses.stream().filter(HttpStatus.CONFLICT::equals).count();
            assertThat(created + conflicts).as("only 201 or 409, got %s", statuses).isEqualTo(requests);
            assertThat(created).isBetween(1L, (long) initialStock);
            assertThat(stockOf(monitor)).isEqualTo(initialStock - created);
        } finally {
            executor.shutdownNow();
        }
    }

    private ResponseEntity<Map> placeOrder(Long productId, int quantity) {
        return post("""
                {"lines":[{"productId":%d,"quantity":%d}]}""".formatted(productId, quantity));
    }

    private ResponseEntity<Map> post(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity("/api/orders", new HttpEntity<>(json, headers), Map.class);
    }

    private int stockOf(Product product) {
        return productRepository.findById(product.getId()).orElseThrow().getStock();
    }
}
