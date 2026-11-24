package com.cocos.challenge;

import com.cocos.challenge.account.AccountCalculator;
import com.cocos.challenge.order.Order;
import com.cocos.challenge.order.OrderRepository;
import com.cocos.challenge.order.OrderStatus;
import jakarta.persistence.EntityManager;
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
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import(ChallengeApplicationTests.PostgresConfiguration.class)
@Transactional
class OrderCancellationTests {

    private static final String CANCEL_PATH = "/api/v1/users/{userId}/orders/{orderId}/cancel";
    private static final String ORDERS_PATH = "/api/v1/users/{userId}/orders";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private MockMvc mvc;

    @Autowired
    void configureMvc(WebApplicationContext context) {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"BUY", "SELL"})
    void cancelsLimitOrderWithoutChangingOtherAttributesOrActualBalances(String side) throws Exception {
        boolean buy = side.equals("BUY");
        addLedgerOrder(2, 66, 1000, "1.00", "CASH_IN", "FILLED");
        addLedgerOrder(2, 3, 10, "20.00", "BUY", "FILLED");
        int orderId = addLedgerOrder(2, 3, 5, "25.00", side, "NEW");
        String originalAttributes = storedAttributes(orderId);
        var before = balances(2);
        assertThat(before.actualCash()).isEqualByComparingTo("800.00");
        assertThat(before.availableCash()).isEqualByComparingTo(buy ? "675.00" : "800.00");
        assertThat(before.availableShares(3)).isEqualTo(buy ? 10L : 5L);

        cancel(2, orderId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId))
                .andExpect(jsonPath("$.userId").value(2))
                .andExpect(jsonPath("$.instrumentId").value(3))
                .andExpect(jsonPath("$.type").value("LIMIT"))
                .andExpect(jsonPath("$.side").value(side))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.quantity").value(5))
                .andExpect(jsonPath("$.price").value(25.00))
                .andExpect(jsonPath("$.datetime").value("2023-07-12T09:00:00"));
        entityManager.flush();
        entityManager.clear();

        assertThat(storedStatus(orderId)).isEqualTo("CANCELLED");
        assertThat(storedAttributes(orderId)).isEqualTo(originalAttributes);
        var entries = orders.findByUser_IdOrderByDatetimeAscIdAsc(2);
        assertThat(entries).hasSize(3);
        var after = AccountCalculator.calculate(entries);
        assertThat(after.actualCash()).isEqualByComparingTo(before.actualCash());
        assertThat(after.holdings().get(3).shares()).isEqualTo(before.holdings().get(3).shares());
        assertThat(after.availableCash()).isEqualByComparingTo("800.00");
        assertThat(after.availableShares(3)).isEqualTo(10L);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"FILLED", "REJECTED", "CANCELLED", "INVALID"})
    void refusesIneligibleAndUnrecognizedStatuses(String orderStatus) throws Exception {
        int orderId = addLedgerOrder(2, 1, 2, "10.00", "BUY", orderStatus);

        cancel(2, orderId).andExpect(status().isConflict());

        assertThat(storedStatus(orderId)).isEqualTo(orderStatus);
    }

    @Test
    void returnsNotFoundForUnknownUsersUnknownOrdersAndOrdersOwnedByOthers() throws Exception {
        int orderId = addLedgerOrder(2, 1, 2, "10.00", "BUY", "NEW");

        cancel(999, orderId).andExpect(status().isNotFound());
        cancel(2, Integer.MAX_VALUE).andExpect(status().isNotFound());
        cancel(3, orderId).andExpect(status().isNotFound());

        assertThat(storedStatus(orderId)).isEqualTo("NEW");
    }

    @Test
    void returnsConflictWhenTheSameCancellationIsRepeated() throws Exception {
        int orderId = addLedgerOrder(2, 1, 2, "10.00", "BUY", "NEW");

        cancel(2, orderId).andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();
        cancel(2, orderId).andExpect(status().isConflict());

        assertThat(storedStatus(orderId)).isEqualTo("CANCELLED");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentCancelsCommitOneTransitionAndReleaseCashOnce() throws Exception {
        int userId = createRaceUser();
        try {
            addLedgerOrder(userId, 66, 100, "1.00", "CASH_IN", "FILLED");
            int orderId = addLedgerOrder(userId, 3, 10, "10.00", "BUY", "NEW");

            runOverlappingRequests(
                    () -> cancel(userId, orderId).andExpect(status().isOk()).andReturn(),
                    () -> cancel(userId, orderId).andExpect(status().isConflict()).andReturn());

            var entries = orders.findByUser_IdOrderByDatetimeAscIdAsc(userId);
            assertThat(entries).extracting(Order::getStatus).containsExactly(OrderStatus.FILLED, OrderStatus.CANCELLED);
            var balances = AccountCalculator.calculate(entries);
            assertThat(balances.actualCash()).isEqualByComparingTo("100.00");
            assertThat(balances.availableCash()).isEqualByComparingTo("100.00");
        } finally {
            deleteRaceUser(userId);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentSubmissionAndCancellationUseTheSameLock(boolean cancelFirst) throws Exception {
        int userId = createRaceUser();
        try {
            addLedgerOrder(userId, 66, 100, "1.00", "CASH_IN", "FILLED");
            int orderId = addLedgerOrder(userId, 3, 10, "10.00", "BUY", "NEW");
            OrderStatus expectedSubmission = cancelFirst ? OrderStatus.NEW : OrderStatus.REJECTED;
            Callable<MvcResult> cancellation = () -> cancel(userId, orderId)
                    .andExpect(status().isOk()).andReturn();
            Callable<MvcResult> submission = () -> mvc.perform(post(ORDERS_PATH, userId)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"instrumentId":3,"side":"BUY","type":"LIMIT","quantity":10,"price":10}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value(expectedSubmission.name()))
                    .andReturn();

            runOverlappingRequests(cancelFirst ? cancellation : submission, cancelFirst ? submission : cancellation);

            var entries = orders.findByUser_IdOrderByDatetimeAscIdAsc(userId);
            assertThat(entries).extracting(Order::getStatus)
                    .containsExactly(OrderStatus.FILLED, OrderStatus.CANCELLED, expectedSubmission);
            var balances = AccountCalculator.calculate(entries);
            assertThat(balances.actualCash()).isEqualByComparingTo("100.00");
            assertThat(balances.availableCash()).isEqualByComparingTo(cancelFirst ? "0.00" : "100.00");
            assertThat(balances.holdings()).isEmpty();
        } finally {
            deleteRaceUser(userId);
        }
    }

    private void runOverlappingRequests(Callable<MvcResult> first, Callable<MvcResult> second) throws Exception {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(15);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var pending = transaction.execute(status -> {
                Integer firstBackend = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                try {
                    first.call();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }

                var blocked = worker.submit(second);
                await().pollDelay(Duration.ZERO).atMost(Duration.ofSeconds(5)).until(() ->
                        Boolean.TRUE.equals(jdbc.queryForObject("""
                                select exists (
                                    select 1 from pg_stat_activity where ? = any(pg_blocking_pids(pid))
                                )
                                """, Boolean.class, firstBackend)));
                return blocked;
            });
            pending.get(8, TimeUnit.SECONDS);
        } finally {
            worker.shutdownNow();
            assertThat(worker.awaitTermination(8, TimeUnit.SECONDS)).isTrue();
        }
    }

    private ResultActions cancel(int userId, int orderId) throws Exception {
        return mvc.perform(post(CANCEL_PATH, userId, orderId));
    }

    private AccountCalculator.Balances balances(int userId) {
        return AccountCalculator.calculate(orders.findByUser_IdOrderByDatetimeAscIdAsc(userId));
    }

    private String storedAttributes(int orderId) {
        return jdbc.queryForObject("select (to_jsonb(o) - 'status')::text from orders o where id = ?",
                String.class, orderId);
    }

    private String storedStatus(int orderId) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    private int createRaceUser() {
        return jdbc.queryForObject("insert into users (email) values ('cancel-race@test.com') returning id",
                Integer.class);
    }

    private void deleteRaceUser(int userId) {
        jdbc.update("delete from orders where userid = ?", userId);
        jdbc.update("delete from users where id = ?", userId);
    }

    private int addLedgerOrder(int userId, int instrumentId, int size, String price, String side, String status) {
        return jdbc.queryForObject("""
                insert into orders (instrumentid, userid, size, price, side, status, type, datetime)
                values (?, ?, ?, cast(? as numeric), ?, ?, ?, timestamp '2023-07-12 09:00:00')
                returning id
                """, Integer.class, instrumentId, userId, size, price, side, status,
                side.startsWith("CASH") ? "MARKET" : "LIMIT");
    }
}
