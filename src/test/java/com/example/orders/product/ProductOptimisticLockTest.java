package com.example.orders.product;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class ProductOptimisticLockTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ProductRepository productRepository;

    @Test
    void seededProductsStartAtVersionZero() {
        assertThat(productRepository.findAll())
                .isNotEmpty()
                .allSatisfy(product -> assertThat(product.getVersion()).isZero());
    }

    @Test
    void savingStaleProductRaisesOptimisticLockFailure() {
        Long id = entityManager.persistFlushFind(new Product("Webcam", new BigDecimal("59.00"), 5)).getId();
        entityManager.clear();

        Product stale = productRepository.findById(id).orElseThrow();
        entityManager.detach(stale);

        Product current = productRepository.findById(id).orElseThrow();
        current.decreaseStock(1);
        entityManager.flush();
        assertThat(current.getVersion()).isEqualTo(1);

        stale.decreaseStock(1);

        assertThatThrownBy(() -> productRepository.saveAndFlush(stale))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}
