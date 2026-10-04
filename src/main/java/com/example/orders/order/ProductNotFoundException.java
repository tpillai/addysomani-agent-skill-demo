package com.example.orders.order;

public class ProductNotFoundException extends RuntimeException {

    private final Long productId;

    public ProductNotFoundException(Long productId) {
        super("Product " + productId + " not found");
        this.productId = productId;
    }

    public Long getProductId() {
        return productId;
    }
}
