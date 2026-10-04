# Practice: build the "orders" feature with agent-skills

The app already has a finished **products** feature (`GET /api/products`).
Your job: add **orders** by walking through each skill phase.

## 0. One-time setup (in a Claude Code session)

    /plugin marketplace add addyosmani/agent-skills
    /plugin install agent-skills@addy-agent-skills

Then open Claude Code in this `orders-service` folder.
(If a command clashes with a built-in, use the long form, e.g. `/agent-skills:review`.)

## 1. Define — `/spec`

    /spec Let customers place an order for one or more products.
    POST /api/orders with productId + quantity per line.
    Reject unknown products and quantities above available stock.
    Placing an order reduces stock. GET /api/orders/{id} returns the order with a total.

Answer its questions. Notice it asks before coding.
Output to look for: a spec with endpoints, JSON examples, error cases (400/404/409), out-of-scope list.

## 2. Plan — `/plan`

Expect small, ordered tasks, roughly:
1. Order + OrderLine entities
2. OrderRepository
3. OrderService (stock check + decrement, @Transactional)
4. OrderController + request/response records + validation
5. Global exception handler (ProblemDetail)

## 3. Build — `/build`

Runs ONE task, then `mvn test`, then stops. Run `/build` again for the next task.
(`/build auto` does all of them in a row — try it only after doing it step by step once.)

## 4. Verify — `/test`

Ask: `/test the insufficient-stock case`.
It should write a failing test first, then make it pass.

## 5. Review — `/review`

Things a good review should catch here:
- Is stock checked and decremented in the same transaction?
- Two concurrent orders for the last item (race condition)?
- Are entities leaking out of the controller?
- N+1 queries when loading order lines?

Try a persona too: "use the security-auditor agent on the order endpoints".

## 6. Simplify + Ship — `/code-simplify`, `/ship`

`/ship` gives you a pre-release checklist (health checks, config, rollback).

## Try it by hand

    mvn spring-boot:run
    curl localhost:8080/api/products
    curl -X POST localhost:8080/api/orders -H 'Content-Type: application/json' \
      -d '{"lines":[{"productId":1,"quantity":2}]}'
