package com.example.orders.order;

public class OrderNotFoundException extends RuntimeException {

    private final Long orderId;

    public OrderNotFoundException(Long orderId) {
        super("Order " + orderId + " not found");
        this.orderId = orderId;
    }

    public Long getOrderId() {
        return orderId;
    }
}
