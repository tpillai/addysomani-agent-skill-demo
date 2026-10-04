package com.example.orders.order;

import com.example.orders.order.PlaceOrderRequest.OrderLineRequest;
import com.example.orders.product.Product;
import com.example.orders.product.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class OrderServiceTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-04T10:15:30.123456789Z"), ZoneOffset.UTC);
    private final OrderService orderService = new OrderService(orderRepository, productRepository, clock);

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
    void placeOrderRejectsDuplicateProductBeforeLoadingAnything() {
        PlaceOrderRequest request = new PlaceOrderRequest(List.of(
                new OrderLineRequest(1L, 1),
                new OrderLineRequest(2L, 1),
                new OrderLineRequest(1L, 2)));

        assertThatThrownBy(() -> orderService.placeOrder(request))
                .isInstanceOf(DuplicateProductException.class)
                .hasMessage("Product 1 appears on more than one line");
        verifyNoInteractions(productRepository, orderRepository);
    }

    @Test
    void placeOrderStampsCreatedAtFromClockTruncatedToMicroseconds() {
        Product hub = product(2L, "USB-C hub", "34.50", 100);
        given(productRepository.findAllById(List.of(2L))).willReturn(List.of(hub));

        OrderResponse response = orderService.placeOrder(new PlaceOrderRequest(List.of(new OrderLineRequest(2L, 1))));

        assertThat(response.createdAt()).isEqualTo(Instant.parse("2026-10-04T10:15:30.123456Z"));
    }

    @Test
    void placeOrderAllowsQuantityEqualToStock() {
        Product monitor = product(3L, "27-inch monitor", "249.00", 8);
        given(productRepository.findAllById(List.of(3L))).willReturn(List.of(monitor));

        orderService.placeOrder(new PlaceOrderRequest(List.of(new OrderLineRequest(3L, 8))));

        assertThat(monitor.getStock()).isZero();
    }

    @Test
    void placeOrderRejectsUnknownProductWithoutChangingAnything() {
        Product keyboard = product(1L, "Mechanical keyboard", "89.99", 25);
        given(productRepository.findAllById(List.of(1L, 99L))).willReturn(List.of(keyboard));
        PlaceOrderRequest request = new PlaceOrderRequest(List.of(
                new OrderLineRequest(1L, 2),
                new OrderLineRequest(99L, 1)));

        assertThatThrownBy(() -> orderService.placeOrder(request))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessage("Product 99 not found")
                .extracting("productId").isEqualTo(99L);
        assertThat(keyboard.getStock()).isEqualTo(25);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void placeOrderRejectsQuantityAboveStockWithoutChangingAnything() {
        Product keyboard = product(1L, "Mechanical keyboard", "89.99", 25);
        Product monitor = product(3L, "27-inch monitor", "249.00", 8);
        given(productRepository.findAllById(List.of(1L, 3L))).willReturn(List.of(keyboard, monitor));
        PlaceOrderRequest request = new PlaceOrderRequest(List.of(
                new OrderLineRequest(1L, 2),
                new OrderLineRequest(3L, 10)));

        assertThatThrownBy(() -> orderService.placeOrder(request))
                .isInstanceOfSatisfying(InsufficientStockException.class, ex -> {
                    assertThat(ex).hasMessage("Insufficient stock for product 3: requested 10, available 8");
                    assertThat(ex.getProductId()).isEqualTo(3L);
                    assertThat(ex.getRequested()).isEqualTo(10);
                    assertThat(ex.getAvailable()).isEqualTo(8);
                });
        assertThat(keyboard.getStock()).isEqualTo(25);
        assertThat(monitor.getStock()).isEqualTo(8);
        verify(orderRepository, never()).save(any());
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
