package com.example.orders.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @Query("""
            select o from Order o
            left join fetch o.lines l
            left join fetch l.product
            where o.id = :id
            order by l.id""")
    Optional<Order> findWithLinesById(@Param("id") Long id);
}
