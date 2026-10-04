package com.example.orders.order;

import com.example.orders.order.PlaceOrderRequest.OrderLineRequest;
import com.example.orders.product.Product;
import com.example.orders.product.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OrderServiceTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final OrderService orderService = new OrderService(orderRepository, productRepository);

    @BeforeEach
    void saveReturnsTheOrder() {
        given(orderRepository.save(any(Order.class))).willAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void placeOrderDecrementsStockForEachLine() {
        Product keyboard = product(1L, "Mechanical keyboard", "89.99", 25);
        Product hub = product(2L, "USB-C hub", "34.50", 100);
        given(productRepository.findAllById(List.of(1L, 2L))).willReturn(List.of(keyboard, hub));

        orderService.placeOrder(new PlaceOrderRequest(List.of(
                new OrderLineRequest(1L, 2),
                new OrderLineRequest(2L, 1))));

        assertThat(keyboard.getStock()).isEqualTo(23);
        assertThat(hub.getStock()).isEqualTo(99);
    }

    @Test
    void placeOrderSnapshotsUnitPriceAndComputesTotal() {
        Product keyboard = product(1L, "Mechanical keyboard", "89.99", 25);
        Product hub = product(2L, "USB-C hub", "34.50", 100);
        given(productRepository.findAllById(List.of(1L, 2L))).willReturn(List.of(keyboard, hub));

        OrderResponse response = orderService.placeOrder(new PlaceOrderRequest(List.of(
                new OrderLineRequest(1L, 2),
                new OrderLineRequest(2L, 1))));

        assertThat(response.lines()).extracting(OrderResponse.OrderLineResponse::productId).containsExactly(1L, 2L);
        assertThat(response.lines().get(0).productName()).isEqualTo("Mechanical keyboard");
        assertThat(response.lines().get(0).unitPrice()).isEqualByComparingTo("89.99");
        assertThat(response.lines().get(0).lineTotal()).isEqualByComparingTo("179.98");
        assertThat(response.lines().get(1).unitPrice()).isEqualByComparingTo("34.50");
        assertThat(response.total()).isEqualByComparingTo("214.48");
        assertThat(response.createdAt()).isNotNull();
    }

    @Test
    void placeOrderSavesTheOrderWithItsLines() {
        Product hub = product(2L, "USB-C hub", "34.50", 100);
        given(productRepository.findAllById(List.of(2L))).willReturn(List.of(hub));

        orderService.placeOrder(new PlaceOrderRequest(List.of(new OrderLineRequest(2L, 3))));

        verify(orderRepository).save(argThat(order ->
                order.getLines().size() == 1 && order.getLines().get(0).getQuantity() == 3));
    }

    @Test
    void getOrderReturnsOrderWithLinesAndTotal() {
        Order order = new Order(Instant.parse("2026-10-04T10:15:30Z"));
        order.addLine(product(1L, "Mechanical keyboard", "89.99", 25), 2);
        ReflectionTestUtils.setField(order, "id", 7L);
        given(orderRepository.findWithLinesById(7L)).willReturn(Optional.of(order));

        OrderResponse response = orderService.getOrder(7L);

        assertThat(response.id()).isEqualTo(7L);
        assertThat(response.lines()).hasSize(1);
        assertThat(response.total()).isEqualByComparingTo("179.98");
    }

    @Test
    void getOrderThrowsWhenOrderDoesNotExist() {
        given(orderRepository.findWithLinesById(42L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getOrder(42L))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessage("Order 42 not found");
    }

    private static Product product(Long id, String name, String price, int stock) {
        Product product = new Product(name, new BigDecimal(price), stock);
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }
}
