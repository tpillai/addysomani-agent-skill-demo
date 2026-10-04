# Orders — Task List

Spec: `SPEC.md` · Plan: `tasks/plan.md`
Every task ends with `mvn test` passing, and the existing `ProductControllerTest` and `OrdersApplicationTests` must stay green.

---

## Phase 1: Foundation

### - [x] T1: Product gets `@Version` + `decreaseStock`, with optimistic-lock proof
**Description:** This adds optimistic locking to `Product` and a guarded way to change stock. It is the riskiest piece, so it goes first.

**Acceptance criteria:**
- [x] `Product` has `@Version private long version` (primitive, non-null), and every `data.sql` insert sets `version` = 0
- [x] `decreaseStock(int quantity)` subtracts the quantity, allows reaching 0, and throws `IllegalArgumentException` if the quantity is ≤ 0 or more than the stock. No public stock setter is added.
- [x] A `@DataJpaTest` does the following and asserts `ObjectOptimisticLockingFailureException`:
  - persists its own product
  - detaches a copy
  - bumps the version through a managed instance and flushes
  - calls `decreaseStock` on the stale copy and `saveAndFlush`es it

**Verification:**
- [x] `mvn test -Dtest=ProductTest,ProductOptimisticLockTest`
- [x] `mvn test` (the context-load test proves `data.sql` matches the schema)

**Dependencies:** None
**Files:** `product/Product.java`, `resources/data.sql`, `test/.../product/ProductTest.java`, `test/.../product/ProductOptimisticLockTest.java`
**Scope:** S

### - [x] T2: Order persistence: entities, fetch-join repository, `OrderResponse`
**Description:** This adds the `Order`/`OrderLine` model and a single-query load of an order with its lines and products. It also adds the response records that map it.

**Acceptance criteria:**
- [x] The two entities map as follows:
  - `Order` is `@Table(name = "orders")` with `id` and `createdAt` (set in the constructor), and `@OneToMany(mappedBy, cascade = ALL)` LAZY lines
  - `OrderLine` has `@ManyToOne(LAZY) Product`, `quantity` and `BigDecimal unitPrice`
- [x] `OrderRepository.findWithLinesById(Long)` uses `left join fetch o.lines l join fetch l.product`. The `@DataJpaTest` shows the lines and products are initialised (`Hibernate.isInitialized`). If `Order` clashes as an HQL keyword, use `@Entity(name = "CustomerOrder")`.
- [x] `OrderResponse.from(Order)` / `OrderLineResponse.from(OrderLine)` compute `lineTotal = unitPrice × quantity` and `total = Σ lineTotal` (asserted with `isEqualByComparingTo`)

**Verification:**
- [x] `mvn test -Dtest=OrderRepositoryTest`
- [x] `mvn test`

**Dependencies:** T1
**Files:** `order/Order.java`, `order/OrderLine.java`, `order/OrderRepository.java`, `order/OrderResponse.java` (includes the nested `OrderLineResponse`), `test/.../order/OrderRepositoryTest.java`
**Scope:** M

## - [x] Checkpoint A: Foundation
- [x] `mvn test` green
- [x] `mvn spring-boot:run` starts, and `curl localhost:8080/api/products` is unchanged

---

## Phase 2: Core flow

### - [x] T3: Place an order, happy path: `POST /api/orders` → 201
**Description:** This is the first end-to-end slice. A valid request creates an order, snapshots prices, decrements stock and returns 201.

**Acceptance criteria:**
- [x] `PlaceOrderRequest(lines)` carries `@NotEmpty @Size(max = 50) @Valid`, and the nested `OrderLineRequest(productId, quantity)` carries `@NotNull` / `@NotNull @Positive`
- [x] `@Transactional OrderService.placeOrder` does the following and returns an `OrderResponse`:
  - loads the products with `findAllById`
  - sets `unitPrice` from `product.getPrice()`
  - calls `decreaseStock` for each line
  - saves the order
- [x] `OrderController` POST takes `@Valid` input and returns 201, a `Location` header that ends with `/api/orders/{id}`, and the body from the spec

**Verification:**
- [x] `mvn test -Dtest=OrderServiceTest,OrderControllerTest`. These are a plain unit test with mocked repositories (checks the stock decrement, price snapshot and total) and a `@WebMvcTest` with `@MockitoBean OrderService`.
- [x] `mvn test`

**Dependencies:** T2
**Files:** `order/PlaceOrderRequest.java`, `order/OrderService.java`, `order/OrderController.java`, `test/.../order/OrderServiceTest.java`, `test/.../order/OrderControllerTest.java`
**Scope:** M

### - [x] T4: Read an order: `GET /api/orders/{id}` → 200 / 404 ProblemDetail
**Description:** This adds the read path and creates the project's first `@RestControllerAdvice`.

**Acceptance criteria:**
- [x] `@Transactional(readOnly = true) OrderService.getOrder(id)` uses `findWithLinesById` and builds the `OrderResponse` inside the transaction. If no order is found, it throws `OrderNotFoundException`.
- [x] `web/GlobalExceptionHandler` (`@RestControllerAdvice`, extends `ResponseEntityExceptionHandler`, no constructor dependencies) maps `OrderNotFoundException` to 404 `application/problem+json`, with detail `Order 42 not found` and an `orderId` property
- [x] GET 200 returns the same shape as POST, and `ProductControllerTest` stays green

**Verification:**
- [x] `mvn test -Dtest=OrderServiceTest,OrderControllerTest,ProductControllerTest`
- [x] `mvn test`

**Dependencies:** T3
**Files:** `order/OrderService.java`, `order/OrderController.java`, `order/OrderNotFoundException.java`, `web/GlobalExceptionHandler.java`, plus edits to `OrderServiceTest` and `OrderControllerTest`
**Scope:** M

## - [x] Checkpoint B: End-to-end
- [x] `mvn test` green
- [x] Manual check. Start the app with `mvn spring-boot:run`, then:
  - `curl -i -X POST localhost:8080/api/orders -H 'Content-Type: application/json' -d '{"lines":[{"productId":1,"quantity":2}]}'` returns 201
  - `curl -i localhost:8080/api/orders/1` returns 200 with the same body
  - `curl localhost:8080/api/products/1` shows stock 23
- [x] Human review before Phase 3

---

## Phase 3: Error paths (T5, T6 and T7 each depend only on T4)

### - [x] T5: 400 ProblemDetail for invalid requests and duplicate productId
**Description:** Malformed or invalid input gets a consistent 400 ProblemDetail.

**Acceptance criteria:**
- [x] The handler overrides `handleMethodArgumentNotValid` and `handleHttpMessageNotReadable` to return 400 with detail `Invalid request`. Bean-validation failures also get an `errors` list of `{field, message}`.
- [x] The service throws `DuplicateProductException` when a `productId` repeats. It maps to 400 with detail `Product 1 appears on more than one line` and a `productId` property, and nothing is loaded or changed.
- [x] Controller tests cover each of these returning 400:
  - empty `lines`
  - 51 lines
  - quantity 0
  - missing `productId`
  - malformed JSON
  - a duplicate `productId`

**Verification:**
- [x] `mvn test -Dtest=OrderControllerTest,OrderServiceTest`
- [x] `mvn test`

**Dependencies:** T4
**Files:** `web/GlobalExceptionHandler.java`, `order/OrderService.java`, `order/DuplicateProductException.java`, plus edits to `OrderControllerTest` and `OrderServiceTest`
**Scope:** M

### - [x] T6: 404 unknown product and 409 insufficient stock, all-or-nothing
**Description:** Catalogue and stock checks run for every line before anything changes.

**Acceptance criteria:**
- [x] An unknown `productId` returns 404 with detail `Product 99 not found` and a `productId` property, from `ProductNotFoundException`
- [x] A quantity above stock returns 409 with detail `Insufficient stock for product 3: requested 10, available 8` and the properties `productId`, `requested` and `available`, from `InsufficientStockException`. A quantity equal to stock succeeds and leaves stock at 0.
- [x] Service tests check that when any line fails:
  - no product's stock changes, including lines before the failing one
  - `orderRepository.save` is never called

**Verification:**
- [x] `mvn test -Dtest=OrderServiceTest,OrderControllerTest`
- [x] `mvn test`

**Dependencies:** T4
**Files:** `order/OrderService.java`, `order/ProductNotFoundException.java`, `order/InsufficientStockException.java`, `web/GlobalExceptionHandler.java`, plus edits to `OrderServiceTest` and `OrderControllerTest`
**Scope:** M

### - [x] T7: 409 ProblemDetail for optimistic-lock conflicts
**Description:** When an order loses a concurrent stock update, the client gets a clean 409.

**Acceptance criteria:**
- [x] The handler maps `ObjectOptimisticLockingFailureException` to 409 with detail `Stock changed concurrently, please retry`
- [x] A `@WebMvcTest` whose mocked `OrderService` throws this exception asserts the 409 ProblemDetail. The exception surfaces at commit through the proxy, so a service unit test can't prove the mapping.

**Verification:**
- [x] `mvn test -Dtest=OrderControllerTest`
- [x] `mvn clean verify`

**Dependencies:** T4 (T1 proves the exception is raised)
**Files:** `web/GlobalExceptionHandler.java`, plus an edit to `OrderControllerTest`
**Scope:** S

## - [x] Checkpoint C: Complete
- [x] `mvn clean verify` green
- [x] Manual: every row of the spec's Errors table checked by curl
- [x] Success criteria 1–8 are met (see the traceability table in `tasks/plan.md`)
- [x] Ready for `/review`

---

## Review follow-ups (from `/review`)
- [x] R1: Integration test proves `placeOrder` persists stock, rolls back on failure and never oversells under concurrency (kills the "drop `@Transactional`" mutation)
- [ ] R2: `createdAt` truncated to microseconds so POST and GET return the same value on every OS
- [ ] R3: Catch-all handler returns a 500 ProblemDetail without leaking exception messages
