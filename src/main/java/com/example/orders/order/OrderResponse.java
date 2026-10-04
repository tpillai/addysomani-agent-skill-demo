package com.example.orders.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(Long id, Instant createdAt, List<OrderLineResponse> lines, BigDecimal total) {

    static OrderResponse from(Order order) {
        List<OrderLineResponse> lines = order.getLines().stream().map(OrderLineResponse::from).toList();
        BigDecimal total = lines.stream().map(OrderLineResponse::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new OrderResponse(order.getId(), order.getCreatedAt(), lines, total);
    }

    public record OrderLineResponse(Long productId, String productName, BigDecimal unitPrice, int quantity,
                                    BigDecimal lineTotal) {

        static OrderLineResponse from(OrderLine line) {
            BigDecimal lineTotal = line.getUnitPrice().multiply(BigDecimal.valueOf(line.getQuantity()));
            return new OrderLineResponse(line.getProduct().getId(), line.getProduct().getName(),
                    line.getUnitPrice(), line.getQuantity(), lineTotal);
        }
    }
}
