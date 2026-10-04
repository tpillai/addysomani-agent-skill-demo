#!/usr/bin/env bash
# Smoke test for orders-service against a running app.
#
# Usage:
#   scripts/smoke-test.sh                        # http://localhost:8080
#   scripts/smoke-test.sh http://localhost:8081
#   RACE_BUYERS=50 scripts/smoke-test.sh
#
# WARNING: this places real orders and uses up product stock (the race drains
# product 3 to 0). Restart the app first for a fresh in-memory database.

set -u

BASE="${1:-http://localhost:8080}"
RACE_BUYERS="${RACE_BUYERS:-20}"
JSON='Content-Type: application/json'
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

pass=0
fail=0

check() { # check <label> <expected> <actual>
    if [[ "$2" == "$3" ]]; then
        printf '  PASS  %-40s %s\n' "$1" "$3"
        pass=$((pass + 1))
    else
        printf '  FAIL  %-40s expected %s, got %s\n' "$1" "$2" "$3"
        fail=$((fail + 1))
    fi
}

post() { # post <body> -> prints status code, body saved to $TMP/body
    curl -s -o "$TMP/body" -w '%{http_code}' -X POST "$BASE/api/orders" -H "$JSON" -d "$1"
}

stock_of() { # stock_of <productId>
    curl -s "$BASE/api/products/$1" | sed -E 's/.*"stock":([0-9]+).*/\1/'
}

if ! curl -s -o /dev/null "$BASE/actuator/health"; then
    echo "App is not reachable at $BASE. Start it with: mvn spring-boot:run"
    exit 2
fi
echo "Testing $BASE"

echo
echo "== Health and products"
check "GET /actuator/health" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/actuator/health")"
check "GET /api/products" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/products")"

echo
echo "== Happy path: place and read back an order"
before=$(stock_of 1)
check "POST order (product 1 x2)" 201 "$(post '{"lines":[{"productId":1,"quantity":2}]}')"
order_id=$(sed -E 's/^\{"id":([0-9]+).*/\1/' "$TMP/body")
check "GET /api/orders/$order_id" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/orders/$order_id")"
check "product 1 stock decreased by 2" "$((before - 2))" "$(stock_of 1)"

echo
echo "== Spec error table"
check "malformed JSON" 400 "$(post '{"lines":[')"
check "empty lines" 400 "$(post '{"lines":[]}')"
check "quantity 0" 400 "$(post '{"lines":[{"productId":1,"quantity":0}]}')"
check "null productId" 400 "$(post '{"lines":[{"quantity":1}]}')"
check "duplicate productId" 400 "$(post '{"lines":[{"productId":1,"quantity":1},{"productId":1,"quantity":1}]}')"
check "unknown product" 404 "$(post '{"lines":[{"productId":99,"quantity":1}]}')"
stock3=$(stock_of 3)
too_many="{\"lines\":[{\"productId\":3,\"quantity\":$((stock3 + 1))}]}"
check "quantity > stock" 409 "$(post "$too_many")"
check "GET unknown order" 404 "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/orders/999999")"

echo
echo "== All-or-nothing: a failing line changes no stock"
before=$(stock_of 1)
post '{"lines":[{"productId":1,"quantity":1},{"productId":99,"quantity":1}]}' > /dev/null
check "product 1 stock unchanged" "$before" "$(stock_of 1)"

echo
echo "== Race: $RACE_BUYERS buyers for the last unit of product 3"
stock3=$(stock_of 3)
if (( stock3 > 1 )); then
    post "{\"lines\":[{\"productId\":3,\"quantity\":$((stock3 - 1))}]}" > /dev/null
fi
check "product 3 stock before race" 1 "$(stock_of 3)"
for i in $(seq 1 "$RACE_BUYERS"); do
    curl -s -o "$TMP/race$i" -w '%{http_code}\n' -X POST "$BASE/api/orders" -H "$JSON" \
        -d '{"lines":[{"productId":3,"quantity":1}]}' > "$TMP/code$i" &
done
wait
winners=$(cat "$TMP"/code* | grep -c '^201$')
check "exactly one buyer wins" 1 "$winners"
check "product 3 stock after race" 0 "$(stock_of 3)"
echo "  status codes:"
cat "$TMP"/code* | sort | uniq -c | sed 's/^/    /'
echo "  409 reasons:"
cat "$TMP"/race* | grep -o '"detail":"[^"]*"' | sort | uniq -c | sed 's/^/    /'

echo
echo "Result: $pass passed, $fail failed"
(( fail == 0 ))
