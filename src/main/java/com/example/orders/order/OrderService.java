package com.example.orders.order;

import com.example.orders.order.PlaceOrderRequest.OrderLineRequest;
import com.example.orders.product.Product;
import com.example.orders.product.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    public OrderService(OrderRepository orderRepository, ProductRepository productRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
    }

    @Transactional
    public OrderResponse placeOrder(PlaceOrderRequest request) {
        List<Long> productIds = request.lines().stream().map(OrderLineRequest::productId).toList();
        Map<Long, Product> products = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        Order order = new Order(Instant.now());
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
}
