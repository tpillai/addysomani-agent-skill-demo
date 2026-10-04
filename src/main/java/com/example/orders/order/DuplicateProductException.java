package com.example.orders.order;

public class DuplicateProductException extends RuntimeException {

    private final Long productId;

    public DuplicateProductException(Long productId) {
        super("Product " + productId + " appears on more than one line");
        this.productId = productId;
    }

    public Long getProductId() {
        return productId;
    }
}
