package com.cocos.challenge;

import com.cocos.challenge.account.AccountCalculator;
import com.cocos.challenge.order.Order;
import com.cocos.challenge.order.OrderRepository;
import com.cocos.challenge.order.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import(ChallengeApplicationTests.PostgresConfiguration.class)
@Transactional
class OrderSubmissionTests {

    private static final String ORDERS_PATH = "/api/v1/users/{userId}/orders";
    private static final String PORTFOLIO_PATH = "/api/v1/users/{userId}/portfolio";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private MockMvc mvc;

    @Autowired
    void configureMvc(WebApplicationContext context) {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void fillsMarketBuyAndAmountBasedMarketSellAndUpdatesPortfolio() throws Exception {
        submit(1, """
                {"instrumentId":1,"side":"BUY","type":"MARKET","quantity":2}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.instrumentId").value(1))
                .andExpect(jsonPath("$.side").value("BUY"))
                .andExpect(jsonPath("$.type").value("MARKET"))
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.price").value(259.00))
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.datetime").isString());

        submit(1, """
                {"instrumentId":47,"side":"SELL","type":"MARKET","amount":1852.1300}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.price").value(925.85))
                .andExpect(jsonPath("$.status").value("FILLED"));

        mvc.perform(get(PORTFOLIO_PATH, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableCash").value(628833.70))
                .andExpect(jsonPath("$.totalAccountValue").value(889756.00))
                .andExpect(jsonPath("$.positions[0].shares").value(2))
                .andExpect(jsonPath("$.positions[2].shares").value(38));
    }

    @Test
    void limitsReserveExactResourcesAndInsufficientResourcesPersistAsRejected() throws Exception {
        addLedgerOrder(2, 66, 100, "1.00", "CASH_IN", "FILLED");
        addLedgerOrder(2, 1, 10, "2.00", "BUY", "FILLED");

        submit(2, """
                {"instrumentId":1,"side":"BUY","type":"LIMIT","quantity":8,"price":10.0000}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NEW"));

        submit(2, """
                {"instrumentId":1,"side":"SELL","type":"LIMIT","amount":200.35,"price":20}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quantity").value(10))
                .andExpect(jsonPath("$.status").value("NEW"));

        submit(2, """
                {"instrumentId":1,"side":"BUY","type":"LIMIT","quantity":1,"price":0.01}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        submit(2, """
                {"instrumentId":1,"side":"SELL","type":"MARKET","quantity":1}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        assertThat(orders.findByUser_IdOrderByDatetimeAscIdAsc(2)).extracting(Order::getStatus)
                .containsExactly(OrderStatus.FILLED, OrderStatus.FILLED, OrderStatus.NEW, OrderStatus.NEW,
                        OrderStatus.REJECTED, OrderStatus.REJECTED);
        assertThat(balances(2).availableShares(1)).isZero();
        mvc.perform(get(PORTFOLIO_PATH, 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableCash").value(0.00))
                .andExpect(jsonPath("$.totalAccountValue").value(2670.00))
                .andExpect(jsonPath("$.positions[0].shares").value(10));
    }

    @Test
    void rejectsUnknownCashButAllowsSellAgainstKnownShares() throws Exception {
        addLedgerOrder(2, 66, 100, "1.00", "CASH_IN", "FILLED");
        addLedgerOrder(2, 1, 3, null, "BUY", "FILLED");

        submit(2, """
                {"instrumentId":1,"side":"BUY","type":"LIMIT","quantity":1,"price":10}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        submit(2, """
                {"instrumentId":1,"side":"SELL","type":"MARKET","quantity":3}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FILLED"));

        assertThat(balances(2).availableCash()).isNull();
        assertThat(balances(2).availableShares(1)).isZero();
    }

    @Test
    void rejectsUnknownRequestedSharesButAllowsBuyAndSellOfUnaffectedResources() throws Exception {
        addLedgerOrder(3, 66, 100, "1.00", "CASH_IN", "FILLED");
        addLedgerOrder(3, 1, 2, "5.00", "BUY", "FILLED");
        addLedgerOrder(3, 2, 2, "5.00", "BUY", "FILLED");
        addLedgerOrder(3, 1, null, "20.00", "SELL", "NEW");

        submit(3, """
                {"instrumentId":1,"side":"SELL","type":"LIMIT","quantity":1,"price":20}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        submit(3, """
                {"instrumentId":1,"side":"BUY","type":"LIMIT","quantity":1,"price":20}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NEW"));

        submit(3, """
                {"instrumentId":2,"side":"SELL","type":"LIMIT","quantity":2,"price":20}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NEW"));

        var balances = balances(3);
        assertThat(balances.availableCash()).isEqualByComparingTo("60.00");
        assertThat(balances.availableShares(1)).isNull();
        assertThat(balances.availableShares(2)).isZero();
    }

    @Test
    void cannotIncreaseHistoricalNegativeHoldingsButCanReduceThemByBuying() throws Exception {
        submit(1, """
                {"instrumentId":31,"side":"SELL","type":"MARKET","quantity":1}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(balances(1).holdings().get(31).shares()).isEqualTo(-10L);

        submit(1, """
                {"instrumentId":31,"side":"BUY","type":"MARKET","quantity":1}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FILLED"));
        assertThat(balances(1).holdings().get(31).shares()).isEqualTo(-9L);
    }

    @Test
    void acceptsMaximumQuantityAndDerivedMonetaryValuesAboveThePriceLimit() throws Exception {
        addLedgerOrder(2, 66, Integer.MAX_VALUE, "10.00", "CASH_IN", "FILLED");

        submit(2, """
                {"instrumentId":1,"side":"BUY","type":"LIMIT","amount":21474836479.99,"price":10.00}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quantity").value(Integer.MAX_VALUE))
                .andExpect(jsonPath("$.status").value("NEW"));

        var balances = balances(2);
        assertThat(balances.reservedCash()).isEqualByComparingTo("21474836470.00");
        assertThat(balances.availableCash()).isEqualByComparingTo("0.00");
    }

    @Test
    void acceptsTheLargestPersistablePriceWithTrailingZeros() throws Exception {
        addLedgerOrder(2, 66, 1, "99999999.99", "CASH_IN", "FILLED");
        submit(2, """
                {"instrumentId":1,"side":"BUY","type":"LIMIT","quantity":1,"price":99999999.9900}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NEW"));
        assertThat(orders.findByUser_IdOrderByDatetimeAscIdAsc(2).getLast().getPrice())
                .isEqualByComparingTo("99999999.99");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"instrumentId\":1,\"side\":\"BUY\",\"type\":\"MARKET\"}",
            "{\"instrumentId\":1,\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":1,\"amount\":300}",
            "{\"instrumentId\":1,\"side\":\"BUY\",\"type\":\"LIMIT\",\"quantity\":1,\"quantity\":100,\"price\":10}",
            "{\"instrumentId\":1,\"side\":\"CASH_IN\",\"type\":\"MARKET\",\"quantity\":1}",
            "{\"instrumentId\":1,\"side\":0,\"type\":\"MARKET\",\"quantity\":1}",
            "{\"instrumentId\":1,\"side\":\"BUY\",\"type\":\"STOP\",\"quantity\":1}",
            "{\"instrumentId\":1.1,\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":1}",
            "{\"instrumentId\":1,\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":0}",
            "{\"instrumentId\":1,\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":-1}",
            "{\"instrumentId\":1,\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":1.01}",
            "{\"instrumentId\":1,\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":\"1\"}",
            "{\"instrumentId\":1,\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":2147483648}",
            "{\"instrumentId\":1,\"side\":\"BUY\",\"type\":\"MARKET\",\"amount\":0}",
            "{\"instrumentId\":1,\"side\":\"BUY\",\"type\":\"MARKET\",\"amount\":-1}",
            "{\"instrumentId\":1,\"side\":\"BUY\",\"type\":\"MARKET\",\"amount\":300.001}",
            "{\"instrumentId\":1,\"side\":\"BUY\",\"type\":\"MARKET\",\"amount\":258.99}",
            "{\"instrumentId\":1,\"side\":\"BUY\",\"type\":\"LIMIT\",\"amount\":21474836480.00,\"price\":10}",
            "{\"instrumentId\":1,\"side\":\"BUY\",\"type\":\"LIMIT\",\"amount\":1e100000,\"price\":10}",
            "{\"instrumentId\":1,\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":1,\"price\":259}",
            "{\"instrumentId\":1,\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":1,\"price\":null}",
            "{\"instrumentId\":1,\"side\":\"BUY\",\"type\":\"LIMIT\",\"quantity\":1}",
            "{\"instrumentId\":1,\"side\":\"BUY\",\"type\":\"LIMIT\",\"quantity\":1,\"price\":-1}",
            "{\"instrumentId\":1,\"side\":\"BUY\",\"type\":\"LIMIT\",\"quantity\":1,\"price\":0}",
            "{\"instrumentId\":1,\"side\":\"BUY\",\"type\":\"LIMIT\",\"quantity\":1,\"price\":10.011}",
            "{\"instrumentId\":1,\"side\":\"BUY\",\"type\":\"LIMIT\",\"quantity\":1,\"price\":100000000}",
            "{\"instrumentId\":66,\"side\":\"BUY\",\"type\":\"LIMIT\",\"quantity\":1,\"price\":1}"
    })
    void invalidRequestsCreateNoOrder(String json) throws Exception {
        submit(2, json).andExpect(status().isBadRequest());
        assertThat(orders.findByUser_IdOrderByDatetimeAscIdAsc(2)).isEmpty();
    }

    @Test
    void unknownResourcesAndMissingQuotesCreateNoOrder() throws Exception {
        submit(999, """
                {"instrumentId":1,"side":"BUY","type":"MARKET","quantity":1}
                """).andExpect(status().isNotFound());
        submit(2, """
                {"instrumentId":999,"side":"BUY","type":"MARKET","quantity":1}
                """).andExpect(status().isNotFound());
        submit(2, """
                {"instrumentId":3,"side":"BUY","type":"MARKET","quantity":1}
                """).andExpect(status().isBadRequest());
        assertThat(orders.findByUser_IdOrderByDatetimeAscIdAsc(2)).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-1", "NaN"})
    void unusableLatestCloseDoesNotFallBackToOlderQuote(String close) throws Exception {
        jdbc.update("""
                insert into marketdata (instrumentid, close, date)
                values (1, cast(? as numeric), date '2023-07-15')
                """, close);

        submit(2, """
                {"instrumentId":1,"side":"BUY","type":"MARKET","quantity":1}
                """).andExpect(status().isBadRequest());
        assertThat(orders.findByUser_IdOrderByDatetimeAscIdAsc(2)).isEmpty();
    }

    @Test
    void marketQuoteUsesDateThenIdAndOnlyNeedsTheClose() throws Exception {
        addLedgerOrder(2, 66, 300, "1.00", "CASH_IN", "FILLED");
        jdbc.update("""
                insert into marketdata (instrumentid, close, high, previousclose, date)
                values (1, 278, null, 250, date '2023-07-15'),
                       (1, 280, 'NaN'::numeric, 'NaN'::numeric, date '2023-07-15'),
                       (1, 292, null, 250, date '2023-07-13'),
                       (1, 299, null, 250, null)
                """);

        submit(2, """
                {"instrumentId":1,"side":"BUY","type":"MARKET","quantity":1}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.price").value(280.00))
                .andExpect(jsonPath("$.status").value("FILLED"));

        mvc.perform(get(PORTFOLIO_PATH, 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableCash").value(20.00))
                .andExpect(jsonPath("$.totalAccountValue").value(300.00))
                .andExpect(jsonPath("$.positions.length()").value(1))
                .andExpect(jsonPath("$.positions[0].instrumentId").value(1))
                .andExpect(jsonPath("$.positions[0].shares").value(1))
                .andExpect(jsonPath("$.positions[0].marketValue").value(280.00))
                .andExpect(jsonPath("$.positions[0].totalReturnPercentage").value(0.0000))
                .andExpect(jsonPath("$.positions[0].dailyReturnPercentage").value(nullValue()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"BUY", "SELL"})
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void competingSubmissionsWaitOnTheUserLockAndSeeCommittedResources(String side) throws Exception {
        Integer userId = jdbc.queryForObject("insert into users (email) values ('race@test.com') returning id",
                Integer.class);
        boolean buy = side.equals("BUY");
        String request = buy
                ? """
                  {"instrumentId":1,"side":"BUY","type":"LIMIT","quantity":6,"price":10}
                  """
                : """
                  {"instrumentId":1,"side":"SELL","type":"MARKET","quantity":6}
                  """;
        var firstTransaction = new TransactionTemplate(transactionManager);
        firstTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        firstTransaction.setTimeout(15);
        var competitor = new AtomicReference<Future<MvcResult>>();
        var worker = Executors.newSingleThreadExecutor();
        OrderStatus accepted = buy ? OrderStatus.NEW : OrderStatus.FILLED;

        try {
            addLedgerOrder(userId, 66, 100, "1.00", "CASH_IN", "FILLED");
            if (!buy) {
                addLedgerOrder(userId, 1, 10, "10.00", "BUY", "FILLED");
            }

            firstTransaction.executeWithoutResult(transaction -> {
                Integer firstBackend = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                try {
                    submit(userId, request)
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$.status").value(accepted.name()));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }

                competitor.set(worker.submit(() -> submit(userId, request)
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.status").value("REJECTED"))
                        .andReturn()));
                await().pollDelay(Duration.ZERO).atMost(Duration.ofSeconds(5)).until(() ->
                        Boolean.TRUE.equals(jdbc.queryForObject("""
                                select exists (
                                    select 1 from pg_stat_activity where ? = any(pg_blocking_pids(pid))
                                )
                                """, Boolean.class, firstBackend)));
            });
            competitor.get().get(8, TimeUnit.SECONDS);

            var entries = orders.findByUser_IdOrderByDatetimeAscIdAsc(userId);
            assertThat(entries.subList(entries.size() - 2, entries.size())).extracting(Order::getStatus)
                    .containsExactly(accepted, OrderStatus.REJECTED);
            var balances = AccountCalculator.calculate(entries);
            assertThat(balances.availableCash()).isEqualByComparingTo(buy ? "40.00" : "1554.00");
            assertThat(balances.availableShares(1)).isEqualTo(buy ? 0L : 4L);
        } finally {
            worker.shutdownNow();
            try {
                assertThat(worker.awaitTermination(8, TimeUnit.SECONDS)).isTrue();
            } finally {
                jdbc.update("delete from orders where userid = ?", userId);
                jdbc.update("delete from users where id = ?", userId);
            }
        }
    }

    private ResultActions submit(int userId, String json) throws Exception {
        return mvc.perform(post(ORDERS_PATH, userId).contentType(APPLICATION_JSON).content(json));
    }

    private AccountCalculator.Balances balances(int userId) {
        return AccountCalculator.calculate(orders.findByUser_IdOrderByDatetimeAscIdAsc(userId));
    }

    private void addLedgerOrder(int userId, int instrumentId, Integer size, String price, String side, String status) {
        jdbc.update("""
                insert into orders (instrumentid, userid, size, price, side, status, type, datetime)
                values (?, ?, ?, cast(? as numeric), ?, ?, ?, timestamp '2023-07-12 09:00:00')
                """, instrumentId, userId, size, price, side, status, status.equals("NEW") ? "LIMIT" : "MARKET");
    }
}
