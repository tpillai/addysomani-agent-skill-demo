package com.example.orders.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record PlaceOrderRequest(@NotEmpty @Size(max = 50) @Valid List<@NotNull OrderLineRequest> lines) {

    public record OrderLineRequest(@NotNull Long productId, @NotNull @Positive Integer quantity) {
    }
}
