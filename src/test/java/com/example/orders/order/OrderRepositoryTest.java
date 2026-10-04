package com.example.orders.order;

import com.example.orders.product.Product;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class OrderRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void findWithLinesByIdLoadsLinesAndProducts() {
        Long orderId = persistOrderWithTwoLines();
        entityManager.clear();

        Order order = orderRepository.findWithLinesById(orderId).orElseThrow();

        assertThat(Hibernate.isInitialized(order.getLines())).isTrue();
        assertThat(order.getLines()).hasSize(2);
        assertThat(order.getLines())
                .allSatisfy(line -> assertThat(Hibernate.isInitialized(line.getProduct())).isTrue());
        assertThat(order.getLines())
                .extracting(line -> line.getProduct().getName())
                .containsExactly("Webcam", "Desk lamp");
    }

    @Test
    void findWithLinesByIdReturnsEmptyForUnknownOrder() {
        assertThat(orderRepository.findWithLinesById(-1L)).isEmpty();
    }

    @Test
    void loadedOrderMapsToResponseWithLineTotalsAndTotal() {
        Long orderId = persistOrderWithTwoLines();
        entityManager.clear();

        OrderResponse response = OrderResponse.from(orderRepository.findWithLinesById(orderId).orElseThrow());

        assertThat(response.id()).isEqualTo(orderId);
        assertThat(response.createdAt()).isEqualTo(Instant.parse("2026-10-04T10:15:30Z"));
        assertThat(response.lines()).hasSize(2);
        assertThat(response.lines().get(0).productName()).isEqualTo("Webcam");
        assertThat(response.lines().get(0).unitPrice()).isEqualByComparingTo("59.99");
        assertThat(response.lines().get(0).quantity()).isEqualTo(2);
        assertThat(response.lines().get(0).lineTotal()).isEqualByComparingTo("119.98");
        assertThat(response.lines().get(1).lineTotal()).isEqualByComparingTo("34.50");
        assertThat(response.total()).isEqualByComparingTo("154.48");
    }

    private Long persistOrderWithTwoLines() {
        Product webcam = entityManager.persist(new Product("Webcam", new BigDecimal("59.99"), 10));
        Product lamp = entityManager.persist(new Product("Desk lamp", new BigDecimal("34.50"), 10));
        Order order = new Order(Instant.parse("2026-10-04T10:15:30Z"));
        order.addLine(webcam, 2);
        order.addLine(lamp, 1);
        Long id = entityManager.persistAndFlush(order).getId();
        return id;
    }
}
