package com.example.orders.order;

import com.example.orders.order.PlaceOrderRequest.OrderLineRequest;
import com.example.orders.product.Product;
import com.example.orders.product.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final Clock clock;

    public OrderService(OrderRepository orderRepository, ProductRepository productRepository, Clock clock) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.clock = clock;
    }

    @Transactional
    public OrderResponse placeOrder(PlaceOrderRequest request) {
        List<Long> productIds = request.lines().stream().map(OrderLineRequest::productId).toList();
        rejectDuplicates(productIds);
        Map<Long, Product> products = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        for (OrderLineRequest line : request.lines()) {
            if (!products.containsKey(line.productId())) {
                throw new ProductNotFoundException(line.productId());
            }
        }
        for (OrderLineRequest line : request.lines()) {
            Product product = products.get(line.productId());
            if (line.quantity() > product.getStock()) {
                throw new InsufficientStockException(product.getId(), line.quantity(), product.getStock());
            }
        }

        // Truncate to the database's timestamp precision so POST and GET return the same createdAt
        Order order = new Order(Instant.now(clock).truncatedTo(ChronoUnit.MICROS));
        for (OrderLineRequest line : request.lines()) {
            Product product = products.get(line.productId());
            product.decreaseStock(line.quantity());
            order.addLine(product, line.quantity());
        }
        return OrderResponse.from(orderRepository.save(order));
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long id) {
        return orderRepository.findWithLinesById(id)
                .map(OrderResponse::from)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    private static void rejectDuplicates(List<Long> productIds) {
        Set<Long> seen = new HashSet<>();
        for (Long productId : productIds) {
            if (!seen.add(productId)) {
                throw new DuplicateProductException(productId);
            }
        }
    }
}
