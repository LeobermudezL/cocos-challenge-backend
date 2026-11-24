# Business and technical decisions

## Portfolio

- Calculate total unrealized position return using the exact weighted-average acquisition cost ratio. Partial sales leave the ratio unchanged. Round the percentage only when constructing the response.
- Include daily return as `(close - previousclose) / previousclose × 100`. Return null for undefined values, including a zero denominator.
- Exclude undated quotes and select the latest quote by `date DESC, id DESC`. Market value is signed shares times closing price and requires a close. Unrealized return also requires a defined acquisition cost basis. Return null for a missing position valuation or an undefined return, and for the total account value if a required position valuation is missing.
- Preserve historical negative holdings. The seed has a filled buy of 20 shares followed by a filled sale of 30 shares for user 1 and instrument 31. Report the resulting −10 shares and include their signed market value in the account total when a close is available. Their unrealized return is null because the cost basis is undefined. Preserve the source orders.
- A purchase that covers a historical negative holding establishes a cost basis only for the newly held shares. A filled purchase without a recorded price leaves its long cost basis and the actual cash balance unknown.

## Cash and reservations

- Only `FILLED` orders affect actual cash and holdings. Include filled `CASH_IN` and `CASH_OUT` movements in ARS cash.
- A `NEW` buy reserves cash. A `NEW` sell reserves shares. Cancellation releases the reservation.
- Reserved cash counts toward total account value. Deduct it from available cash only.
- Ignore orders explicitly marked `CANCELLED` or `REJECTED`. An unknown status can affect both actual balances and reservations. Unknown side, quantity, or price leaves the calculations that depend on those fields unknown. A filled trade with a known quantity and missing price still updates shares. An undated filled trade leaves cash and shares calculable, but makes cost basis unknown for that instrument when sales make trade order relevant.
- Pending buys and sells require positive quantities and prices. An incomplete or invalid reservation leaves the affected cash or share availability unknown. Share uncertainty is scoped to the instrument when it can be identified. An unidentified filled trade can obscure any holding and makes the account total unknown. An identifiable holding with unknown quantity has null shares, market value, and unrealized return. Preserve independently known outputs and use null availability to signal that resources cannot be verified.

## API behavior

- Reject duplicate fields when parsing JSON request bodies across the application. Requests with duplicate fields receive HTTP 400.
- Accept exactly one of quantity or ARS amount for both buys and sells. A market order uses the latest close. A limit order uses the supplied limit price.
- Return HTTP 201 for persisted submissions, including `REJECTED` orders. Return HTTP 400 for invalid requests, HTTP 404 for unknown resources, and HTTP 409 for invalid state transitions. Only `NEW` orders can be cancelled.
- Return HTTP 200 with the updated order on cancellation. Return HTTP 404 for orders owned by another user. A repeated cancellation or an order with a null or unrecognized stored status returns HTTP 409. Treat unrecognized statuses as unknown on reads and retain the original stored value.
- Order requests use JSON numbers for instrument ID, quantity, amount and price. Omit unused numeric fields. A market order without a usable latest dated close returns HTTP 400 and creates no order. A newer quote with an unusable close takes precedence over an older usable quote.
- Insufficient or unknown required resources produce a persisted `REJECTED` order. Buy authorization depends on available cash. Sell authorization depends on available shares of the requested instrument. Other resource uncertainty does not affect that decision.
- Use case-insensitive partial matching for ticker and name. Combine supplied filters with AND. Return all tradable instruments when neither filter is supplied. Exclude the ARS cash instrument from search and buy or sell submissions.

## Monetary precision

- Use `BigDecimal` for all monetary values and financial calculations. Never use `float` or `double` for financial calculations.
- Preserve the original `NUMERIC(10,2)` schema. Apply its precision and scale limits only to persisted prices. Derived monetary balances can exceed those limits.
- Accept prices with trailing fractional zeros when they are exactly representable at scale 2. Use `Order.validatePrice` before calculating quantity or reserving cash or shares. Order construction enforces the same check before persistence.
- Accept ARS order amounts with up to two meaningful decimal places. Divide the amount by the order price at scale 0 with `RoundingMode.DOWN` to get whole shares.
- Use `MathContext.DECIMAL128` for intermediate divisions that require rounding. Avoid premature rounding in other calculations.
- Return monetary values with 2 decimal places and percentage returns with 4 decimal places. Use `RoundingMode.HALF_EVEN` when rounding final response values.

## Persistence and concurrency

- Preserve the supplied PostgreSQL schema. Hibernate validates it at startup. Integration tests load the original `database.sql` into isolated Testcontainers PostgreSQL.
- Portfolio reads use a read-only PostgreSQL `REPEATABLE READ` transaction. Load orders and their instruments together, then fetch latest quotes for all open positions in one query.
- Order submission and cancellation must acquire a pessimistic write lock on the same user row within their transaction before reading balances, holdings, or order state. Hold the lock through the write.
- Order submission uses `READ COMMITTED` so the account ledger read after lock acquisition sees orders committed by the previous holder. Store order timestamps in UTC at PostgreSQL timestamp precision.
- Cancellation also uses `READ COMMITTED` and loads the order after acquiring the user lock. Hibernate updates the status in place. The ledger excludes the cancelled order when calculating reservations.
