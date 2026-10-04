package com.example.orders.product;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductTest {

    @Test
    void decreaseStockSubtractsQuantity() {
        Product product = new Product("USB-C hub", new BigDecimal("34.50"), 10);

        product.decreaseStock(3);

        assertThat(product.getStock()).isEqualTo(7);
    }

    @Test
    void decreaseStockAllowsReachingZero() {
        Product product = new Product("USB-C hub", new BigDecimal("34.50"), 10);

        product.decreaseStock(10);

        assertThat(product.getStock()).isZero();
    }

    @Test
    void decreaseStockRejectsQuantityAboveStock() {
        Product product = new Product("USB-C hub", new BigDecimal("34.50"), 10);

        assertThatThrownBy(() -> product.decreaseStock(11))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(product.getStock()).isEqualTo(10);
    }

    @Test
    void decreaseStockRejectsNonPositiveQuantity() {
        Product product = new Product("USB-C hub", new BigDecimal("34.50"), 10);

        assertThatThrownBy(() -> product.decreaseStock(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(product.getStock()).isEqualTo(10);
    }
}
