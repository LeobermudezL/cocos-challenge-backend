# Cocos Capital Backend Challenge

A Java REST API for searching tradable instruments, valuing portfolios in Argentine pesos (ARS), submitting MARKET and LIMIT orders, and cancelling open orders. It uses the supplied PostgreSQL schema and historical seed data.

**Stack** · Java 21, Spring Boot 4.2, Spring MVC, Spring Data JPA and Hibernate, Jakarta Bean Validation, PostgreSQL 17, Maven Wrapper, JUnit, and Testcontainers.

## Run locally

Install JDK 21 and Docker with the Compose plugin. The commands below use a POSIX-compatible shell and OpenSSL. The API examples also use `curl` and `jq`. The first run may need network access to download the Maven distribution, dependencies, and Docker images.

From the repository root, run these commands once to create a local environment file and generate a local-only password.

```sh
(
  umask 077
  cp .env.example .env
  printf 'DB_PASSWORD=%s\n' "$(openssl rand -hex 24)" >> .env
)
```

Load that environment, start PostgreSQL, and run the application.

```sh
set -a
. ./.env
set +a
docker compose up -d --wait db
./mvnw spring-boot:run
```

| Variable | Purpose |
|---|---|
| `DB_URL` | Required JDBC URL. The example uses `jdbc:postgresql://127.0.0.1:5432/challenge`. |
| `DB_USERNAME` | Required PostgreSQL login. The local example uses `challenge`. |
| `DB_PASSWORD` | Required local PostgreSQL password generated during setup. |
| `DB_PORT` | Optional Compose host port, defaulting to `5432`. If changed, update `DB_URL` to match. |

Compose binds PostgreSQL to loopback and runs the original [database.sql](database.sql) on first startup with an empty data volume. Hibernate validates the schema at application startup. The HTTP API listens on port `8080`. This setup needs no Neon connection. To stop, interrupt Maven and run `docker compose down`. To start over with the original seed, `docker compose down -v` deletes this project's local database volume and any orders added to it.

### Run from IntelliJ IDEA

Create `.env` as shown above, then start PostgreSQL from the repository root with `docker compose up -d --wait db`.

1. Open **Run → Edit Configurations** and select the run configuration for `com.cocos.challenge.ChallengeApplication`. Set its JRE to Java 21.
2. In **Environment variables**, select the repository's `.env` using **Browse for .env files and scripts**. If the file picker is unavailable, add `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` using their values from `.env`.
3. Click **Apply**, then run the configuration.

Docker Compose reads `.env` for its own settings. The application launched by IntelliJ gets its database settings from the run configuration. [IntelliJ environment variable documentation](https://www.jetbrains.com/help/idea/program-arguments-and-environment-variables.html)

## Run tests

```sh
./mvnw test
```

The suite uses Docker and an isolated Testcontainers PostgreSQL instance initialized from `database.sql`. Tests use the container's connection settings. Integration coverage includes functional order submission, cancellation, and competing requests.

## API

| Method and path | Behavior |
|---|---|
| `GET /api/v1/instruments` | Optional `ticker` and `name` filters. Case-insensitive literal substring matching, with AND when both are present. Blank filters are absent. Results exclude ARS and sort by ticker then ID. |
| `GET /api/v1/users/{userId}/portfolio` | Returns total account value, available cash, and positions with share count, market value, unrealized return, and daily return. An existing user with no orders gets zeros and an empty positions list. |
| `POST /api/v1/users/{userId}/orders` | Requires a numeric `instrumentId`, `side` of `BUY` or `SELL`, `type` of `MARKET` or `LIMIT`, and exactly one of `quantity` or `amount`. A LIMIT requires a positive `price`. Omit `price` for MARKET. |
| `POST /api/v1/users/{userId}/orders/{orderId}/cancel` | No request body. Cancels an owned `NEW` order and returns the updated order. |

| Status | Meaning |
|---|---|
| `200 OK` | Successful reads or cancellation. An instrument search with no matches returns `[]`. |
| `201 Created` | Order persisted. Inspect its `status` for `FILLED`, `NEW`, or `REJECTED`. Insufficient or unknown required cash or shares yields `REJECTED`. |
| `400 Bad Request` | Invalid input, duplicate JSON fields, an unusable latest MARKET close, an ARS trade, or an amount that cannot produce a positive whole-share quantity in range. No order is created. |
| `404 Not Found` | Unknown user, instrument, or order. An order owned by another user also returns 404. The portfolio 404 has an empty body. |
| `409 Conflict` | Cancellation rejected because the order is no longer `NEW` or its status is unknown. |

Errors use Spring's default HTTP handling. Clients should rely on the status code and, for persisted orders, the response's order status.

### Curl examples

Run in order against a freshly seeded database. The first two calls show the starting data. Run the POSTs once for the depicted balances and order IDs.

```sh
base_url=http://localhost:8080
user_id=1
instrument_id=1

curl -fsS -w '\n' -G "$base_url/api/v1/instruments" \
  --data-urlencode 'ticker=dyca' --data-urlencode 'name=dycasa'
curl -fsS -w '\n' "$base_url/api/v1/users/$user_id/portfolio"
```

#### Instrument search response

```json
[{"id":1,"ticker":"DYCA","name":"Dycasa S.A.","type":"ACCIONES"}]
```

#### Seeded portfolio response

```json
{
  "totalAccountValue": 889756.00,
  "availableCash": 627500.00,
  "positions": [
    {"instrumentId":31,"ticker":"BMA","name":"Banco Macro S.A.","shares":-10,"marketValue":-15028.00,"totalReturnPercentage":null,"dailyReturnPercentage":-1.1478},
    {"instrumentId":47,"ticker":"PAMP","name":"Pampa Holding S.A.","shares":40,"marketValue":37034.00,"totalReturnPercentage":-0.4462,"dailyReturnPercentage":0.4394},
    {"instrumentId":54,"ticker":"METR","name":"MetroGAS S.A.","shares":500,"marketValue":114750.00,"totalReturnPercentage":-8.2000,"dailyReturnPercentage":-1.0776}
  ]
}
```

#### Submit orders

Buy with a MARKET amount, place a LIMIT buy, and attempt a MARKET sell with insufficient shares.

```sh
curl -fsS -w '\n' "$base_url/api/v1/users/$user_id/orders" \
  -H 'Content-Type: application/json' \
  --data "{\"instrumentId\":$instrument_id,\"side\":\"BUY\",\"type\":\"MARKET\",\"amount\":600.00}"

limit_order=$(curl -fsS "$base_url/api/v1/users/$user_id/orders" \
  -H 'Content-Type: application/json' \
  --data "{\"instrumentId\":$instrument_id,\"side\":\"BUY\",\"type\":\"LIMIT\",\"quantity\":2,\"price\":250.00}")
printf '%s\n' "$limit_order" | jq .
order_id=$(printf '%s\n' "$limit_order" | jq -er 'select(.status == "NEW") | .id')

curl -fsS -w '\n' "$base_url/api/v1/users/$user_id/orders" \
  -H 'Content-Type: application/json' \
  --data "{\"instrumentId\":$instrument_id,\"side\":\"SELL\",\"type\":\"MARKET\",\"quantity\":1000000}"
```

Each submission returns one object. These examples are grouped in request order. The server assigns IDs and UTC timestamps.

```json
[
  {"id":12,"userId":1,"instrumentId":1,"side":"BUY","type":"MARKET","status":"FILLED","quantity":2,"price":259.00,"datetime":"2026-11-24T19:00:00"},
  {"id":13,"userId":1,"instrumentId":1,"side":"BUY","type":"LIMIT","status":"NEW","quantity":2,"price":250.00,"datetime":"2026-11-24T19:00:01"},
  {"id":14,"userId":1,"instrumentId":1,"side":"SELL","type":"MARKET","status":"REJECTED","quantity":1000000,"price":259.00,"datetime":"2026-11-24T19:00:02"}
]
```

#### Cancel an order

Cancel the LIMIT and verify that a second attempt returns 409.

```sh
curl -fsS -w '\n' -X POST "$base_url/api/v1/users/$user_id/orders/$order_id/cancel"
curl -sS -o /dev/null -w '%{http_code}\n' -X POST \
  "$base_url/api/v1/users/$user_id/orders/$order_id/cancel"
```

#### Example cancellation response

```json
{"id":13,"userId":1,"instrumentId":1,"side":"BUY","type":"LIMIT","status":"CANCELLED","quantity":2,"price":250.00,"datetime":"2026-11-24T19:00:01"}
```

Import the [Postman collection](postman/Cocos-Capital-Backend.postman_collection.json) to run the same flow. It defaults to the local API and seeded IDs. The successful LIMIT request captures its order ID for cancellation.

## Financial and transaction rules

- A MARKET order uses the latest dated close, ordered by date and then ID descending, and fills immediately if authorized. An authorized LIMIT starts as `NEW`. Quantities are positive whole shares within the database integer range. ARS amounts accept up to two meaningful decimal places and divide by the order price with `RoundingMode.DOWN`.
- Filled transfers and trades determine actual cash and holdings. An open buy reserves quantity × price in cash. An open sell reserves shares of its instrument. Cancellation changes the status and releases the reservation. Reserved cash reduces available cash and remains in total account value.
- Positions use signed shares × close. Unrealized return uses exact weighted-average acquisition cost. A partial sale leaves that average unchanged. Daily return is `(close − previousClose) / previousClose × 100`. Monetary output has two decimals and return percentages have four, using `HALF_EVEN` at the response boundary. A missing or nonpositive latest close preserves known shares and cash and makes the affected position value, its returns, and the account total `null`, even when an older usable quote exists.
- The seed records a filled buy of 20 BMA shares followed by a filled sell of 30 for user 1. The portfolio preserves the resulting −10 shares, includes their signed market value, and returns `null` for unrealized return because cost basis is undefined. Order validation prevents new oversells.
- Portfolio reads use a read-only `REPEATABLE READ` transaction so the user, ledger, and batched quote reads share one snapshot. Submission and cancellation acquire the same pessimistic lock on the user row and hold it through commit. They use `READ COMMITTED` so a request that waited for that lock reads changes committed by the previous holder before checking resources or order status.

The challenge uses historical quotes and excludes authentication, live pricing, market simulation, automatic LIMIT fills, and transfer endpoints. See [CHALLENGE.md](CHALLENGE.md) for the requirements and [DECISIONS.md](DECISIONS.md) for edge-case policies.
