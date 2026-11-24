# cocos-challenge-backend

> **Implementation note:** The recruiting team confirmed that this challenge must be implemented in Java instead of Node.js. The selected technology stack is Java 21 and Spring Boot. All other requirements remain unchanged.

**Overview:**

Develop an API that provides the following information and functionality through its endpoints:

- **Portfolio:** The response must return the total value of a user's account, the available cash balance in Argentine pesos (ARS), and the list of assets held by the user, including the number of shares, the total monetary value of each position ($), and the total percentage return (%).
- **Asset Search:** The response must return a list of market assets matching the search criteria. The search must support filtering by ticker and/or name.
- **Order Submission:** This endpoint must allow users to submit buy or sell orders for an asset. It must support two order types: `MARKET` and `LIMIT`. Market orders do not require a price because they are executed against available market offers. Limit orders, on the other hand, require the user to specify the price at which they want the order to be executed. The order must be persisted in the `orders` table with the corresponding status and values.

**Optional / Nice to Have:**

- Provide a Postman, Insomnia, or REST Client collection for the API, including examples of how to invoke its endpoints.

# Functional Considerations

- Asset prices must be expressed in Argentine pesos (ARS).
- Market simulation is NOT required.
- When a user submits an order, the number of shares they want to buy or sell must be specified. Allow users to provide either an exact number of shares or a total investment amount in ARS. In the latter case, calculate the maximum number of shares that can be included in the order. Fractional shares are not allowed.
- Orders have an attribute called `side`, which indicates whether the order is a buy (`BUY`) or sell (`SELL`) operation.
- Orders have different statuses:
    - `NEW` — When a limit order is submitted to the market, it is assigned this status.
    - `FILLED` — When an order is executed. Market orders are executed immediately upon submission.
    - `REJECTED` — When an order is rejected by the market because it does not meet the requirements, such as when an order exceeds the available balance.
    - `CANCELLED` — When an order is cancelled by the user.
- When a user submits a `MARKET` order, the order is executed immediately and its status is set to `FILLED`.
- When a user submits a `LIMIT` order, its status must be set to `NEW`.
- Only orders with the `NEW` status can be cancelled.
- If an order exceeds the available balance or holdings, it must be rejected and persisted with the `REJECTED` status. For buy orders, validate that the user has sufficient cash. For sell orders, validate that the user has sufficient shares.
- Incoming and outgoing transfers can be modeled as orders. Incoming transfers have the `CASH_IN` side, while outgoing transfers have the `CASH_OUT` side.
- When an order is executed, the user's list of positions must be updated.
- To calculate holdings and available cash, use all relevant movements in the `orders` table, taking the `size` column into account.
- Cash (ARS) is modeled as an instrument of type `MONEDA`.
- The `marketdata` table contains instrument prices for the last two days. The `close` column represents the closing price of each asset. To calculate the daily return, use the `close` and `previousClose` columns.
- When a `MARKET` order is submitted, use the latest closing price (`close`).
- To calculate the market value, return, and number of shares of each position, use the corresponding orders with the `FILLED` status for each asset.

# Technical Considerations

## REST API

- Develop the application using Node.js (original requirement, superseded by the implementation note above).
    - Use a REST API framework of your choice, such as Express or NestJS.
    - Choose an appropriate data access strategy or library. An ORM or direct database queries may be used.
    - Use any additional libraries or frameworks considered appropriate.
- Implement a functional test covering the order submission functionality.
- User authentication is NOT required.
- Document any assumptions or design decisions considered relevant.

# Database

As a reference, a database has already been created with the following tables and sample data, provided in the `database.sql` file:

- **users:** id, email, accountNumber
- **instruments:** id, ticker, name, type
- **orders:** id, instrumentId, userId, side, size, price, type, status, datetime
- **marketdata:** id, instrumentId, high, low, open, close, previousClose, datetime

The provided database is a functional model that can be used as-is, although it is a basic implementation. Candidates are free to modify, add, or adjust tables as necessary to improve application performance, optimize queries, or address any other relevant technical considerations.

Any changes must be properly justified in the documentation.