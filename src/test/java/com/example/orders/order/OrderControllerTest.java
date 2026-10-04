package com.example.orders.order;

import com.example.orders.order.OrderResponse.OrderLineResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @Test
    void placesOrderAndReturns201WithLocation() throws Exception {
        given(orderService.placeOrder(any(PlaceOrderRequest.class))).willReturn(new OrderResponse(
                7L,
                Instant.parse("2026-10-04T10:15:30Z"),
                List.of(
                        new OrderLineResponse(1L, "Mechanical keyboard", new BigDecimal("89.99"), 2, new BigDecimal("179.98")),
                        new OrderLineResponse(2L, "USB-C hub", new BigDecimal("34.50"), 1, new BigDecimal("34.50"))),
                new BigDecimal("214.48")));

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"productId":1,"quantity":2},{"productId":2,"quantity":1}]}"""))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/orders/7")))
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.createdAt").value("2026-10-04T10:15:30Z"))
                .andExpect(jsonPath("$.lines[0].productId").value(1))
                .andExpect(jsonPath("$.lines[0].productName").value("Mechanical keyboard"))
                .andExpect(jsonPath("$.lines[0].unitPrice").value(89.99))
                .andExpect(jsonPath("$.lines[0].quantity").value(2))
                .andExpect(jsonPath("$.lines[0].lineTotal").value(179.98))
                .andExpect(jsonPath("$.lines[1].lineTotal").value(34.50))
                .andExpect(jsonPath("$.total").value(214.48));
    }

    @Test
    void rejectsInvalidRequestWithoutCallingService() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[]}"""))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(orderService);
    }
}
