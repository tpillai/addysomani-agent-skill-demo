package com.example.orders.order;

import com.example.orders.order.OrderResponse.OrderLineResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"lines\":[]}",
            "{}",
            "{\"lines\":[{\"productId\":1,\"quantity\":0}]}",
            "{\"lines\":[{\"productId\":1,\"quantity\":-1}]}",
            "{\"lines\":[{\"productId\":1}]}",
            "{\"lines\":[{\"quantity\":1}]}",
            "{\"lines\":[null]}"
    })
    void rejectsInvalidRequestWith400ProblemDetail(String body) throws Exception {
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Invalid request"))
                .andExpect(jsonPath("$.instance").value("/api/orders"))
                .andExpect(jsonPath("$.errors").isNotEmpty());

        verifyNoInteractions(orderService);
    }

    @Test
    void rejectsMoreThan50Lines() throws Exception {
        String lines = IntStream.rangeClosed(1, 51)
                .mapToObj(id -> "{\"productId\":" + id + ",\"quantity\":1}")
                .collect(Collectors.joining(","));

        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[" + lines + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid request"))
                .andExpect(jsonPath("$.errors[0].field").value("lines"));

        verifyNoInteractions(orderService);
    }

    @Test
    void listsFieldErrorsForInvalidLine() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"productId":null,"quantity":0}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[0].field").value("lines[0].productId"))
                .andExpect(jsonPath("$.errors[0].message").value("must not be null"))
                .andExpect(jsonPath("$.errors[1].field").value("lines[0].quantity"))
                .andExpect(jsonPath("$.errors[1].message").value("must be greater than 0"));
    }

    @Test
    void rejectsMalformedJsonWith400ProblemDetail() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\": [ oops"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Invalid request"))
                .andExpect(jsonPath("$.instance").value("/api/orders"));

        verifyNoInteractions(orderService);
    }

    @Test
    void rejectsDuplicateProductWith400ProblemDetail() throws Exception {
        given(orderService.placeOrder(any(PlaceOrderRequest.class))).willThrow(new DuplicateProductException(1L));

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"productId":1,"quantity":1},{"productId":1,"quantity":2}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Product 1 appears on more than one line"))
                .andExpect(jsonPath("$.productId").value(1));
    }

    @Test
    void getsOrderById() throws Exception {
        given(orderService.getOrder(7L)).willReturn(new OrderResponse(
                7L,
                Instant.parse("2026-10-04T10:15:30Z"),
                List.of(new OrderLineResponse(2L, "USB-C hub", new BigDecimal("34.50"), 1, new BigDecimal("34.50"))),
                new BigDecimal("34.50")));

        mockMvc.perform(get("/api/orders/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.createdAt").value("2026-10-04T10:15:30Z"))
                .andExpect(jsonPath("$.lines[0].productName").value("USB-C hub"))
                .andExpect(jsonPath("$.lines[0].lineTotal").value(34.50))
                .andExpect(jsonPath("$.total").value(34.50));
    }

    @Test
    void returns404ProblemDetailForUnknownOrder() throws Exception {
        given(orderService.getOrder(42L)).willThrow(new OrderNotFoundException(42L));

        mockMvc.perform(get("/api/orders/42"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.detail").value("Order 42 not found"))
                .andExpect(jsonPath("$.instance").value("/api/orders/42"))
                .andExpect(jsonPath("$.orderId").value(42));
    }
}
