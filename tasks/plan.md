# Implementation Plan: Orders

Source spec: `SPEC.md`. Task checklist: `tasks/todo.md`, which `/build` reads.

## Overview
This adds `POST /api/orders` and `GET /api/orders/{id}`. Placing an order is all-or-nothing:
- every line is checked against the catalogue and current stock
- stock is decremented in the same transaction as the order is saved
- `@Version` on `Product` prevents overselling under concurrency

All errors are RFC 7807 `ProblemDetail`, produced by a new `GlobalExceptionHandler`.

## Dependency Graph
```
Product @Version + decreaseStock + data.sql version        (T1)
    │
    ├── Order / OrderLine entities, OrderRepository (fetch-join), OrderResponse   (T2)
    │       │
    │       ├── OrderService.placeOrder + PlaceOrderRequest + POST controller     (T3)
    │       │       │
    │       │       ├── OrderService.getOrder + GET controller + GlobalExceptionHandler (T4)
    │       │       │       │
    │       │       │       ├── 400s: bean-validation / malformed JSON / duplicate productId (T5)
    │       │       │       ├── 404 unknown product, 409 insufficient stock, all-or-nothing  (T6)
    │       │       │       └── 409 optimistic-lock mapping                                  (T7)
```
T5, T6 and T7 each depend only on T4, so they can be built in any order or in parallel. They all edit `GlobalExceptionHandler`, so merges need care.

## Architecture Decisions
- **Optimistic locking (`@Version long version`)** goes on `Product`. The losing transaction fails at commit with `ObjectOptimisticLockingFailureException`, which maps to 409. There is no retry; the client retries.
- **`OrderService` returns `OrderResponse`, not entities.** With `open-in-view: false`, lines and products must be read inside the transaction, so the service builds the DTO there (the spec requires this). This departs on purpose from "the controller maps to the DTO". The controller still never sees an entity.
- **Check order in `placeOrder`:**
  1. duplicate `productId` → 400
  2. load all products with `findAllById` (one query); the first missing id → 404
  3. stock for each line → 409
  4. only then call `decreaseStock` and save

  Nothing is mutated before every check passes, and `@Transactional` rollback is the second safety net.
- **`GlobalExceptionHandler` extends `ResponseEntityExceptionHandler`.** This gives `ProblemDetail` for framework exceptions without touching `application.yml`, which is an ask-first change. It overrides `handleMethodArgumentNotValid` and `handleHttpMessageNotReadable` to return detail `Invalid request` plus an `errors` list. It has no constructor dependencies, because it also loads in `ProductControllerTest`.
- **`Order` naming:** `@Table(name = "orders")` is needed because `order` is a SQL keyword. If HQL `from Order o` fails to parse (Order is also an HQL keyword), fall back to `@Entity(name = "CustomerOrder")`.
- **`Location` header:** built with `ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")`, so it is absolute. Tests assert `endsWith("/api/orders/{id}")`.
- **`createdAt`:** `Instant.now()` is set in the `Order` constructor. No `Clock` is injected, and tests assert it is non-null.
- **Money:** `BigDecimal` throughout. Tests compare with `isEqualByComparingTo` in AssertJ and `jsonPath(...).value(179.98)` in JSON, so they don't depend on scale.

## Task List

### Phase 1: Foundation
- [x] T1: Product gets `@Version` + `decreaseStock`, with optimistic-lock proof (S)
- [x] T2: Order persistence: entities, fetch-join repository, `OrderResponse` (M, 5 files)

### Checkpoint A: Foundation
- [x] `mvn test` green (includes the context-load test, which runs `data.sql` against the new schema)
- [x] `mvn spring-boot:run` starts, and `GET /api/products` is unchanged

### Phase 2: Core flow
- [x] T3: Place an order, happy path: `POST /api/orders` → 201 (M, 5 files)
- [x] T4: Read an order: `GET /api/orders/{id}` → 200/404, creates `GlobalExceptionHandler` (M, 6 files incl. 2 edited tests)

### Checkpoint B: End-to-end
- [x] `mvn test` green
- [x] Manual: `curl` POST then GET round-trips, and `GET /api/products/1` shows stock decreased
- [x] Human review before the error-path tasks

### Phase 3: Error paths
- [x] T5: 400 ProblemDetail for invalid requests and duplicate productId (M)
- [x] T6: 404 unknown product and 409 insufficient stock, all-or-nothing (M)
- [x] T7: 409 ProblemDetail for optimistic-lock conflicts (S)

### Checkpoint C: Complete
- [x] `mvn clean verify` green
- [x] Every row in the spec's Errors table is checked by curl
- [x] Success criteria 1–8 are all met (see traceability below)
- [x] Ready for `/review`

## Traceability: Success Criteria → Tasks
| # | Criterion | Task(s) |
|---|---|---|
| 1 | POST 201 + Location + correct total + stock decremented | T3 |
| 2 | Unknown product → 404, nothing saved | T6 |
| 3 | Quantity > stock → 409 with requested/available, nothing saved | T6 |
| 4 | Quantity == stock succeeds, stock → 0 | T6 (T1 covers `decreaseStock` boundary) |
| 5 | Empty / >50 lines, qty ≤ 0, null productId, duplicate → 400 | T5 |
| 6 | GET 200 same shape; unknown id → 404 | T4 |
| 7 | Stale version → optimistic-lock failure → 409 | T1 (exception), T7 (409 mapping) |
| 8 | `mvn clean verify` passes | every task; final at Checkpoint C |

## Risks and Mitigations
| Risk | Impact | Mitigation |
|---|---|---|
| A null `version` in seed rows makes Hibernate treat them as new or fail the update | High | Use a primitive `long version` and add `version` = 0 to every `data.sql` insert (T1) |
| `data.sql` also runs in `@DataJpaTest`, so seeded products exist | Med | Tests persist their own products and use the ids that come back; never assume ids 1–3 |
| A JPQL bulk update doesn't bump `@Version`, so the stale-version test would prove nothing | Med | The test increments the version through a managed entity and flushes, then `saveAndFlush`es the detached stale copy |
| HQL `Order` keyword clash | Low | The repository test parses the query in T2; fallback entity name is `CustomerOrder` |
| N+1 when reading lines/products | Med | A single `left join fetch o.lines l join fetch l.product` query, used by GET |
| The optimistic-lock exception surfaces at commit, outside the service method body | Med | Prove the 409 mapping with `@WebMvcTest` and a mocked service that throws it (T7). Service unit tests can't. |
| The new advice changes `ProductControllerTest` behaviour | Low | The advice has no dependencies. `ProductController` 404 uses `ResponseEntity.notFound()`, which the advice doesn't touch. Keeping that test green is a T4 criterion. |

## Open Questions
None blocking. All spec questions are resolved.
