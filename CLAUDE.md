# orders-service

Sample Spring Boot 3.5 app (Java 21, Maven) used to practise the
addyosmani/agent-skills workflow (/spec → /plan → /build → /test → /review → /ship).

## Commands
- Build + all tests: `mvn clean verify`
- Unit tests only: `mvn test`
- Single test: `mvn test -Dtest=ProductControllerTest#listsProducts`
- Run app: `mvn spring-boot:run`  → http://localhost:8080
  - Health: http://localhost:8080/actuator/health
  - H2 console: http://localhost:8080/h2-console (JDBC URL `jdbc:h2:mem:orders`)

## Layout
- One package per feature under `com.example.orders` (e.g. `product/`, `order/`)
- Seed data: `src/main/resources/data.sql`

## Conventions
- Layers: controller → service → repository; no business logic in controllers
- Request/response DTOs are Java records; never return JPA entities from controllers
- Validate input with jakarta.validation (`@Valid`, `@NotNull`, `@Positive`) at the controller
- Errors: a `@RestControllerAdvice` returning `ProblemDetail` (RFC 7807)
- Money is `BigDecimal`, never double
- Constructor injection only; no field `@Autowired` in main code
- Tests: JUnit 5 + AssertJ/MockMvc
  - `@WebMvcTest` + `@MockitoBean` for controllers
  - plain unit tests for services
  - `@DataJpaTest` for custom repository queries
- Every task must end with `mvn test` passing
