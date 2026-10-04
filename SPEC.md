# Spec: Orders

## Objective

Let a customer place an order for one or more products and read it back.
Placing an order is all-or-nothing: every line is validated against the
catalogue and current stock, and stock is reduced only if the whole order is
accepted.

### User stories
- As a customer, I can `POST /api/orders` with one or more `{productId, quantity}`
  lines and get back the created order with its total.
- As a customer, I can `GET /api/orders/{id}` and see the lines, unit prices,
  line totals and order total.
- As the shop, I never sell more units than I have in stock, even when two orders
  arrive at the same time.

### Assumptions
1. There is no authentication and no customer identity, so an order is not tied to a user.
2. Orders have no status or lifecycle (no cancel or pay). Once created, they are immutable.
3. Each line stores the product's price at order time (`unitPrice`). The total is
   the sum of `unitPrice × quantity` and is not affected by later price changes.
4. Totals are computed from the lines with `BigDecimal` and are not stored as a column.
5. Currency is implicit (same as `Product.price`), so there is no currency field.

### Decisions (confirmed)
| Topic | Decision |
|---|---|
| Concurrent orders for the same stock | Optimistic locking: `@Version` on `Product`. The losing transaction gets **409 Conflict** |
| Same `productId` on two lines | Rejected with **400** |
| Price used by a line | Snapshot at order time (`OrderLine.unitPrice`) |

## API

### `POST /api/orders`

Request:
```json
{
  "lines": [
    { "productId": 1, "quantity": 2 },
    { "productId": 2, "quantity": 1 }
  ]
}
```

Validation (jakarta.validation at the controller):
- `lines`: `@NotEmpty`, `@Size(max = 50)`, `@Valid`
- `productId`: `@NotNull`
- `quantity`: `@NotNull`, `@Positive`
- No duplicate `productId` across lines (checked in the service, returns 400)

Success: **201 Created**, `Location: /api/orders/{id}`, body:
```json
{
  "id": 1,
  "createdAt": "2026-10-04T10:15:30Z",
  "lines": [
    { "productId": 1, "productName": "Mechanical keyboard", "unitPrice": 89.99, "quantity": 2, "lineTotal": 179.98 },
    { "productId": 2, "productName": "USB-C hub",           "unitPrice": 34.50, "quantity": 1, "lineTotal": 34.50 }
  ],
  "total": 214.48
}
```

Side effect: for each line, `product.stock -= quantity`, in the same transaction
as saving the order.

### `GET /api/orders/{id}`

**200 OK** with the same body shape as above. **404** if the order doesn't exist.

### Errors

All errors are RFC 7807 `ProblemDetail` (`application/problem+json`), produced by a
new `@RestControllerAdvice`.

| Case | Status | Example `detail` | Extra properties |
|---|---|---|---|
| Malformed JSON, empty or >50 `lines`, null/≤0 quantity, null productId | 400 | `Invalid request` | `errors`: list of `{field, message}` |
| Duplicate `productId` in request | 400 | `Product 1 appears on more than one line` | `productId` |
| Unknown `productId` | 404 | `Product 99 not found` | `productId` |
| Quantity > available stock | 409 | `Insufficient stock for product 3: requested 10, available 8` | `productId`, `requested`, `available` |
| Concurrent update lost (optimistic lock) | 409 | `Stock changed concurrently, please retry` | — |
| Order id not found (GET) | 404 | `Order 42 not found` | `orderId` |

Example:
```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Insufficient stock for product 3: requested 10, available 8",
  "instance": "/api/orders",
  "productId": 3,
  "requested": 10,
  "available": 8
}
```

If any line fails, nothing is saved and no stock changes.

## Tech Stack
Spring Boot 3.5, Java 21, Maven, Spring Data JPA, H2 (in-memory), jakarta.validation.
No new dependencies.

## Commands
- Build + all tests: `mvn clean verify`
- Tests: `mvn test`
- Single test: `mvn test -Dtest=OrderServiceTest#rejectsQuantityAboveStock`
- Run: `mvn spring-boot:run`, then:
  ```
  curl -i -X POST localhost:8080/api/orders -H 'Content-Type: application/json' \
    -d '{"lines":[{"productId":1,"quantity":2}]}'
  curl -i localhost:8080/api/orders/1
  ```

## Project Structure
```
src/main/java/com/example/orders/
  product/
    Product.java              ← add @Version field + decreaseStock(int) method
  order/
    Order.java                ← @Entity @Table(name = "orders")  ("order" is a SQL keyword)
    OrderLine.java            ← @Entity, @ManyToOne Product, unitPrice, quantity
    OrderRepository.java      ← JpaRepository + fetch-join query for lines
    OrderService.java         ← @Transactional placeOrder / getOrder
    OrderController.java      ← POST /api/orders, GET /api/orders/{id}
    PlaceOrderRequest.java    ← record (+ nested OrderLineRequest record)
    OrderResponse.java        ← record with static from(Order) (+ OrderLineResponse)
    ProductNotFoundException.java, InsufficientStockException.java,
    DuplicateProductException.java, OrderNotFoundException.java
  web/
    GlobalExceptionHandler.java ← @RestControllerAdvice → ProblemDetail
src/main/resources/data.sql   ← product inserts gain `version` = 0
src/test/java/com/example/orders/order/
  OrderControllerTest.java    ← @WebMvcTest + @MockitoBean OrderService
  OrderServiceTest.java       ← plain unit test, mocked repositories
  OrderRepositoryTest.java    ← @DataJpaTest for the fetch-join query
```

### Data model
- `orders`: `id`, `created_at`
- `order_line`: `id`, `order_id` (FK), `product_id` (FK), `quantity`, `unit_price`
- `product`: adds `version bigint not null`

`Order` → `OrderLine` is `@OneToMany(mappedBy, cascade = ALL)`, LAZY. Because
`open-in-view: false`, `getOrder` loads lines (and their products) with a
`join fetch` query and builds the response inside the transaction.

## Code Style
Follow the existing products feature and CLAUDE.md conventions:

```java
public record OrderResponse(Long id, Instant createdAt, List<OrderLineResponse> lines, BigDecimal total) {

    static OrderResponse from(Order order) {
        List<OrderLineResponse> lines = order.getLines().stream().map(OrderLineResponse::from).toList();
        BigDecimal total = lines.stream().map(OrderLineResponse::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new OrderResponse(order.getId(), order.getCreatedAt(), lines, total);
    }
}
```

- Use constructor injection. Controllers stay thin: validate, delegate to the service, map to a DTO.
- Use `BigDecimal` for money and never `double`.
- Entities never leave the service/controller boundary. Controllers return records only.
- Stock changes go through `Product.decreaseStock(int)`, not a public setter.

## Testing Strategy
JUnit 5 + AssertJ + MockMvc, following CLAUDE.md:

- **`OrderServiceTest` (unit):** happy path (stock decremented, unit price
  snapshotted, total correct); unknown product; quantity > stock; quantity ==
  stock allowed; duplicate productId; no stock change when any line fails;
  order not found.
- **`OrderControllerTest` (`@WebMvcTest`):** 201 + Location + body; 400 for
  empty lines / 51 lines / zero quantity / missing productId / malformed JSON; 404/409
  mapped to ProblemDetail with the extra properties; GET 200 and 404.
- **`OrderRepositoryTest` (`@DataJpaTest`):** fetch-join query returns the order with its lines
  initialised.
- **Optimistic locking:** an `@SpringBootTest` or `@DataJpaTest` test that saves a stale `Product`
  version and asserts `ObjectOptimisticLockingFailureException`, plus an
  advice test that maps it to 409.
- `mvn test` must pass at the end of every task. Existing `ProductControllerTest`
  and the context-load test must keep passing.

## Boundaries
- **Always:** follow controller → service → repository; return DTO records; use
  `ProblemDetail` for errors; run `mvn test` before finishing each task; keep
  `data.sql` in sync with the entities.
- **Ask first:** adding Maven dependencies; changing existing product endpoints
  or `ProductResponse` shape; changing `application.yml`; adding new columns to
  `product` beyond `version`.
- **Never:** return JPA entities from controllers; use `double` for money;
  field `@Autowired`; refactor `ProductController` as part of this work; delete
  or skip failing tests.

## Out of Scope
- Authentication, customers, addresses
- Order cancellation/updates, status, payment, shipping
- Listing or paginating orders (`GET /api/orders`)
- Restocking or product admin endpoints
- Retry on optimistic-lock failure (the client retries)
- Currency, tax, discounts
- Idempotency keys for POST

## Success Criteria
1. `POST /api/orders` with valid lines returns 201, a `Location` header, and a body whose
   `total` equals Σ(unitPrice × quantity), and each product's stock drops by its quantity.
2. Unknown product → 404 ProblemDetail, and no stock changes and no order are saved.
3. Quantity above stock → 409 ProblemDetail with `requested`/`available`, and no changes are saved.
4. Quantity exactly equal to stock succeeds and leaves stock at 0.
5. Empty lines, more than 50 lines, quantity ≤ 0, null productId, or a duplicate productId → 400 ProblemDetail.
6. `GET /api/orders/{id}` returns 200 with the same shape, and an unknown id → 404 ProblemDetail.
7. A stale-version stock update raises an optimistic-lock failure that maps to 409.
8. `mvn clean verify` passes.

## Resolved Questions
1. **Unknown product status:** 404 (matches `TRY-IT.md`).
2. **Request size:** `lines` is capped at 50 with `@Size(max = 50)`, and a larger list → 400.
