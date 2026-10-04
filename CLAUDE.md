# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# orders-service

Sample Spring Boot 3.5 app (Java 21, Maven) used to practise the
addyosmani/agent-skills workflow (/spec → /plan → /build → /test → /review → /ship).
`TRY-IT.md` is the exercise brief: the **products** feature is done; the
**orders** feature (`POST /api/orders`, `GET /api/orders/{id}`) is what gets built.

## Commands
- Build + all tests: `mvn clean verify`
- Tests: `mvn test` (no failsafe/integration split, so this runs the same suite, including the `@SpringBootTest` context check)
- Single test: `mvn test -Dtest=ProductControllerTest#listsProducts`
- Run app: `mvn spring-boot:run`  → http://localhost:8080
  - Health: http://localhost:8080/actuator/health (only `health` and `info` are exposed)
  - H2 console: http://localhost:8080/h2-console (JDBC URL `jdbc:h2:mem:orders`)
- No linter or formatter is configured.

## Layout
- One package per feature under `com.example.orders` (e.g. `product/`, `order/`)
- Seed data: `src/main/resources/data.sql`

## Persistence
- In-memory H2; there are no migrations. Hibernate generates the schema from the
  entities (`ddl-auto: create-drop`), then `data.sql` runs after it
  (`defer-datasource-initialization: true`). Table/column names in `data.sql` must
  match Hibernate's snake_case naming of the entities, and a new entity's seed rows go there too.
- `open-in-view: false`: lazy associations (e.g. order lines) must be loaded inside a
  `@Transactional` service method or a fetch-join query, not in the controller.

## Conventions
- Layers: controller → service → repository; no business logic in controllers
  - Exception: `ProductController` predates this rule and calls `ProductRepository` directly. Follow the rule for new code; don't copy that pattern.
- Request/response DTOs are Java records with a static `from(Entity)` factory (see `ProductResponse`); never return JPA entities from controllers
- Validate input with jakarta.validation (`@Valid`, `@NotNull`, `@Positive`) at the controller
- Errors: a `@RestControllerAdvice` returning `ProblemDetail` (RFC 7807). None exists yet, so the first feature that needs one creates it
- Money is `BigDecimal`, never double
- Constructor injection only; no field `@Autowired` in main code
- Tests: JUnit 5 + AssertJ/MockMvc
  - `@WebMvcTest` + `@MockitoBean` for controllers
  - plain unit tests for services
  - `@DataJpaTest` for custom repository queries
- Every task must end with `mvn test` passing
