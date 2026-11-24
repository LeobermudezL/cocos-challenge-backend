package com.cocos.challenge;

import com.cocos.challenge.instrument.Instrument;
import com.cocos.challenge.instrument.InstrumentRepository;
import com.cocos.challenge.marketdata.MarketDataRepository;
import com.cocos.challenge.order.Order;
import com.cocos.challenge.order.OrderRepository;
import com.cocos.challenge.order.OrderSide;
import com.cocos.challenge.order.OrderStatus;
import com.cocos.challenge.order.OrderType;
import com.cocos.challenge.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import(ChallengeApplicationTests.PostgresConfiguration.class)
@Transactional
class ChallengeApplicationTests {

    private static final String INSTRUMENTS_PATH = "/api/v1/instruments";
    private static final String PORTFOLIO_PATH = "/api/v1/users/{userId}/portfolio";

    @Autowired
    private UserRepository users;

    @Autowired
    private InstrumentRepository instruments;

    @Autowired
    private MarketDataRepository marketData;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private MockMvc mvc;

    @Autowired
    void configureMvc(WebApplicationContext context) {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void loadsSeedOrdersAndPersistsNewOrder() {
        var user = users.findByIdForUpdate(1).orElseThrow();
        assertThat(user.getAccountNumber()).isEqualTo("10001");
        assertThat(orders.findByUser_IdOrderByDatetimeAscIdAsc(user.getId()))
                .extracting(Order::getSide)
                .contains(OrderSide.CASH_IN, OrderSide.CASH_OUT);

        var instrument = instruments.findById(47).orElseThrow();
        var timestamp = LocalDateTime.of(2023, 7, 14, 12, 0);
        var order = new Order(instrument, user, 2, new BigDecimal("930.25"),
                OrderType.LIMIT, OrderSide.BUY, OrderStatus.NEW, timestamp);
        var id = orders.saveAndFlush(order).getId();
        entityManager.clear();

        var saved = orders.findByIdAndUser_Id(id, user.getId()).orElseThrow();
        assertThat(saved.getInstrument().getId()).isEqualTo(47);
        assertThat(saved.getSize()).isEqualTo(2);
        assertThat(saved.getPrice()).isEqualByComparingTo("930.25");
        assertThat(saved.getType()).isEqualTo(OrderType.LIMIT);
        assertThat(saved.getSide()).isEqualTo(OrderSide.BUY);
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.NEW);
        assertThat(saved.getDatetime()).isEqualTo(timestamp);
        assertThat(orders.findByIdAndUser_Id(id, 2)).isEmpty();
    }

    @Test
    void blocksAnotherTransactionFromLockingTheSameUser() {
        users.findByIdForUpdate(1).orElseThrow();

        var contender = new TransactionTemplate(transactionManager);
        // The suspended test transaction keeps its row lock while the contender runs.
        contender.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThatThrownBy(() -> contender.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL lock_timeout = '500ms'");
            users.findByIdForUpdate(1).orElseThrow();
        })).rootCause().isInstanceOfSatisfying(SQLException.class,
                error -> assertThat(error.getSQLState()).isEqualTo("55P03"));
    }

    @Test
    void searchesTradableInstrumentsUsingOptionalFilters() {
        assertThat(instruments.searchTradable(null, null))
                .hasSize(65)
                .extracting(Instrument::getTicker)
                .doesNotContain("ARS");
        assertThat(instruments.searchTradable("om", "NEGRA"))
                .extracting(Instrument::getTicker)
                .containsExactly("LOMA");
        assertThat(instruments.searchTradable("dyca", "negra")).isEmpty();
        assertThat(instruments.searchTradable("%", null)).isEmpty();
    }

    @Test
    void listsAllTradableInstrumentsForAbsentAndBlankFilters() throws Exception {
        var all = mvc.perform(get(INSTRUMENTS_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(65))
                .andExpect(jsonPath("$[0].ticker").value("AGRO"))
                .andExpect(jsonPath("$[?(@.ticker == 'ARS')]").isEmpty())
                .andReturn().getResponse().getContentAsString();

        mvc.perform(get(INSTRUMENTS_PATH).param("ticker", "  ").param("name", "\t "))
                .andExpect(status().isOk())
                .andExpect(content().string(all));
    }

    @Test
    void appliesCaseInsensitivePartialFiltersIndividuallyAndTogether() throws Exception {
        mvc.perform(get(INSTRUMENTS_PATH).param("ticker", " dYc ").param("name", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].ticker").value("DYCA"));

        mvc.perform(get(INSTRUMENTS_PATH).param("name", "nEgRa"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].ticker").value("LOMA"));

        mvc.perform(get(INSTRUMENTS_PATH).param("ticker", " oM ").param("name", " nEgRa "))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        [{"id":45,"ticker":"LOMA","name":"Loma Negra S.A.","type":"ACCIONES"}]
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void returnsEmptyListForMismatchedFiltersAndCashInstrument() throws Exception {
        mvc.perform(get(INSTRUMENTS_PATH).param("ticker", "dyca").param("name", "negra"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        mvc.perform(get(INSTRUMENTS_PATH).param("ticker", "ARS").param("name", "PESOS"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void matchesWildcardCharactersLiterallyAndOrdersEqualTickersById() throws Exception {
        jdbc.update("""
                insert into instruments (ticker, name, type)
                values ('T%_TEST', 'Zeta', 'ACCIONES'), ('T%_TEST', 'Alpha', 'ACCIONES')
                """);

        for (String wildcard : List.of("%", "_")) {
            mvc.perform(get(INSTRUMENTS_PATH).param("ticker", wildcard))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].name").value("Zeta"))
                    .andExpect(jsonPath("$[1].name").value("Alpha"));
        }
    }

    @Test
    void findsLatestDatedQuoteByDateAndThenId() {
        jdbc.update("""
                insert into marketdata (instrumentid, close, previousclose, date)
                values (1, 270.00, 260.00, date '2023-07-14'),
                       (1, 280.00, 270.00, date '2023-07-13'),
                       (1, 300.00, 280.00, null),
                       (3, 320.00, 300.00, null)
                """);

        var quote = marketData.findFirstByInstrument_IdAndDateIsNotNullOrderByDateDescIdDesc(1).orElseThrow();
        assertThat(quote.getDate()).isEqualTo(LocalDate.of(2023, 7, 14));
        assertThat(quote.getClose()).isEqualByComparingTo("270.00");
        assertThat(quote.getPreviousClose()).isEqualByComparingTo("260.00");
        assertThat(marketData.findFirstByInstrument_IdAndDateIsNotNullOrderByDateDescIdDesc(3)).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void returnsTheIndependentlyCalculatedSeededPortfolio() throws Exception {
        var response = mvc.perform(get(PORTFOLIO_PATH, 1))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "totalAccountValue": 889756.00,
                          "availableCash": 627500.00,
                          "positions": [
                            {"instrumentId":31,"ticker":"BMA","name":"Banco Macro S.A.","shares":-10,
                             "marketValue":-15028.00,"totalReturnPercentage":null,"dailyReturnPercentage":-1.1478},
                            {"instrumentId":47,"ticker":"PAMP","name":"Pampa Holding S.A.","shares":40,
                             "marketValue":37034.00,"totalReturnPercentage":-0.4462,"dailyReturnPercentage":0.4394},
                            {"instrumentId":54,"ticker":"METR","name":"MetroGAS S.A.","shares":500,
                             "marketValue":114750.00,"totalReturnPercentage":-8.2000,"dailyReturnPercentage":-1.0776}
                          ]
                        }
                        """, JsonCompareMode.STRICT))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).contains("\"availableCash\":627500.00", "\"totalReturnPercentage\":-8.2000");
    }

    @Test
    void returnsAnEmptyPortfolioForAnExistingUserWithoutOrders() throws Exception {
        mvc.perform(get(PORTFOLIO_PATH, 2))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"totalAccountValue":0.00,"availableCash":0.00,"positions":[]}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void treatsNaNOrderPriceAsUnknownAndPreservesItOnAnUnrelatedUpdate() throws Exception {
        Integer orderId = jdbc.queryForObject("""
                insert into orders (instrumentid, userid, size, price, side, status, type, datetime)
                values (47, 1, 1, 'NaN'::numeric, 'BUY', 'NEW', 'LIMIT', '2023-07-14 12:00:00')
                returning id
                """, Integer.class);

        mvc.perform(get(PORTFOLIO_PATH, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableCash").value(nullValue()))
                .andExpect(jsonPath("$.totalAccountValue").value(889756.00));

        Order pending = orders.findByIdAndUser_Id(orderId, 1).orElseThrow();
        assertThat(pending.getPrice()).isNull();
        ReflectionTestUtils.setField(pending, "size", 2);
        entityManager.flush();

        assertThat(jdbc.queryForObject("select size from orders where id = ?", Integer.class, orderId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("select price::text from orders where id = ?", String.class, orderId))
                .isEqualTo("NaN");
    }

    @Test
    void returnsNotFoundForAnUnknownUser() throws Exception {
        mvc.perform(get(PORTFOLIO_PATH, 999))
                .andExpect(status().isNotFound());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void keepsOriginalQuoteWhenAConcurrentTransactionCommitsBetweenReads() throws Exception {
        Integer quoteId = jdbc.queryForObject("""
                select id from marketdata where instrumentid = 47 and date = date '2023-07-14'
                """, Integer.class);
        var writer = new TransactionTemplate(transactionManager);
        writer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writer.setTimeout(5);
        var originalClose = new BigDecimal("925.85");
        var newClose = new BigDecimal("935.85");
        String updateClose = "update marketdata set close = ? where id = ?";

        MethodInterceptor commitBeforeQuoteRead = invocation -> {
            if (invocation.getMethod().getName().equals("findLatestDatedByInstrumentIds")) {
                // Orders have been read by this point. Commit the write before the quote query runs.
                writer.executeWithoutResult(status -> assertThat(jdbc.update(updateClose, newClose, quoteId))
                        .isEqualTo(1));
            }
            return invocation.proceed();
        };
        Advised repository = (Advised) marketData;
        repository.addAdvice(0, commitBeforeQuoteRead);
        try {
            mvc.perform(get(PORTFOLIO_PATH, 1))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.positions[1].marketValue").value(37034.00));
            assertThat(jdbc.queryForObject("select close from marketdata where id = ?", BigDecimal.class, quoteId))
                    .isEqualByComparingTo(newClose);
        } finally {
            repository.removeAdvice(commitBeforeQuoteRead);
            jdbc.update(updateClose, originalClose, quoteId);
        }
    }

    @Test
    void roundsAnExactReturnTieAfterAPartialSaleAndRepurchase() throws Exception {
        jdbc.update("""
                insert into orders (instrumentid, userid, size, price, side, status, type, datetime)
                values (1, 2, 2, 11.00, 'BUY', 'FILLED', 'MARKET', '2023-07-14 09:00:00'),
                       (1, 2, 1, 12.00, 'BUY', 'FILLED', 'MARKET', '2023-07-14 10:00:00'),
                       (1, 2, 2, 15.00, 'SELL', 'FILLED', 'MARKET', '2023-07-14 11:00:00'),
                       (1, 2, 1, 10.00, 'BUY', 'FILLED', 'MARKET', '2023-07-14 12:00:00')
                """);
        jdbc.update("""
                insert into marketdata (instrumentid, close, date)
                values (1, 10.67, date '2023-07-15')
                """);

        mvc.perform(get(PORTFOLIO_PATH, 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positions[0].shares").value(2))
                .andExpect(jsonPath("$.positions[0].marketValue").value(21.34))
                .andExpect(jsonPath("$.positions[0].totalReturnPercentage").value(0.0312));
    }

    @Test
    void preservesIndependentValuationsWhenTheLedgerIsIncomplete() throws Exception {
        jdbc.update("""
                insert into orders (instrumentid, userid, size, price, side, status, type, datetime)
                values (1, 3, null, 10.00, 'BUY', 'FILLED', 'MARKET', '2023-07-14 09:00:00'),
                       (2, 3, 2, 5.00, 'BUY', 'FILLED', null, '2023-07-14 09:00:00'),
                       (66, 3, null, null, null, 'CANCELLED', null, null),
                       (2, 3, 1, 7.00, 'SELL', 'FILLED', 'MARKET', null),
                       (3, 3, 1, 10.00, 'BUY', 'FILLED', 'MARKET', null)
                """);
        jdbc.update("""
                insert into marketdata (instrumentid, close, previousclose, date)
                values (1, 12.00, 10.00, date '2023-07-15'),
                       (2, 12.00, 10.00, date '2023-07-15'),
                       (3, 12.00, 10.00, date '2023-07-15')
                """);

        mvc.perform(get(PORTFOLIO_PATH, 3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAccountValue").value(nullValue()))
                .andExpect(jsonPath("$.availableCash").value(nullValue()))
                .andExpect(jsonPath("$.positions[0].shares").value(nullValue()))
                .andExpect(jsonPath("$.positions[0].marketValue").value(nullValue()))
                .andExpect(jsonPath("$.positions[0].totalReturnPercentage").value(nullValue()))
                .andExpect(jsonPath("$.positions[0].dailyReturnPercentage").value(20.0000))
                .andExpect(jsonPath("$.positions[1].shares").value(1))
                .andExpect(jsonPath("$.positions[1].marketValue").value(12.00))
                .andExpect(jsonPath("$.positions[1].totalReturnPercentage").value(nullValue()))
                .andExpect(jsonPath("$.positions[2].totalReturnPercentage").value(20.0000));
    }

    @Test
    void valuesReopenedPositionsUsingDeterministicTradeAndQuoteOrdering() throws Exception {
        jdbc.update("""
                insert into orders (instrumentid, userid, size, price, side, status, type, datetime)
                values (1, 2, 1, 31.00, 'SELL', 'FILLED', 'MARKET', '2023-07-14 11:00:00'),
                       (1, 2, 2, 32.00, 'BUY', 'FILLED', 'MARKET', '2023-07-14 11:00:00'),
                       (1, 2, 1, 30.00, 'BUY', 'FILLED', 'MARKET', '2023-07-14 10:00:00'),
                       (66, 2, 1000, 1.00, 'CASH_IN', 'FILLED', 'MARKET', '2023-07-14 09:00:00'),
                       (66, 2, 50, 1.00, 'CASH_OUT', 'FILLED', 'MARKET', '2023-07-14 12:00:00'),
                       (66, 2, 1, 1.00, 'BUY', 'FILLED', 'MARKET', '2023-07-14 13:00:00'),
                       (2, 2, 1, 9.00, 'BUY', 'FILLED', 'MARKET', '2023-07-14 13:00:00'),
                       (2, 2, 1, 9.00, 'SELL', 'FILLED', 'MARKET', '2023-07-14 13:00:00'),
                       (2, 2, 3, 10.01, 'BUY', 'NEW', 'LIMIT', '2023-07-14 14:00:00'),
                       (1, 2, 1, 30.00, 'SELL', 'NEW', 'LIMIT', '2023-07-14 14:00:00'),
                       (1, 2, 50, 500.00, 'SELL', 'REJECTED', 'MARKET', '2023-07-14 15:00:00'),
                       (2, 2, 100, 50.00, 'BUY', 'CANCELLED', 'LIMIT', '2023-07-14 15:00:00')
                """);
        jdbc.update("""
                insert into marketdata (instrumentid, close, previousclose, date)
                values (1, 32.03, 32.00, date '2023-07-15'),
                       (1, 32.01, 32.00, date '2023-07-15'),
                       (1, 100.00, 90.00, date '2023-07-14'),
                       (1, 150.00, 32.00, null)
                """);

        mvc.perform(get(PORTFOLIO_PATH, 2))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "totalAccountValue":950.02,"availableCash":855.97,
                          "positions":[{"instrumentId":1,"ticker":"DYCA","name":"Dycasa S.A.","shares":2,
                                        "marketValue":64.02,"totalReturnPercentage":0.0312,"dailyReturnPercentage":0.0312}]
                        }
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void returnsNoTotalWhenPositionsLackUsableQuotesAndKeepsOtherValuations() throws Exception {
        jdbc.update("""
                insert into orders (instrumentid, userid, size, price, side, status, type, datetime)
                values (66, 3, 100, 1.00, 'CASH_IN', 'FILLED', 'MARKET', '2023-07-14 09:00:00'),
                       (1, 3, 1, 10.00, 'BUY', 'FILLED', 'MARKET', '2023-07-14 10:00:00'),
                       (2, 3, 1, 0.00, 'BUY', 'FILLED', 'MARKET', '2023-07-14 10:00:00'),
                       (3, 3, 1, 10.00, 'BUY', 'FILLED', 'MARKET', '2023-07-14 10:00:00'),
                       (8, 3, 1, 10.00, 'BUY', 'FILLED', 'MARKET', '2023-07-14 10:00:00'),
                       (9, 3, 1, 10.00, 'BUY', 'FILLED', 'MARKET', '2023-07-14 10:00:00')
                """);
        jdbc.update("""
                insert into marketdata (instrumentid, close, previousclose, date)
                values (1, null, 10.00, date '2023-07-16'),
                       (2, 12.00, 10.00, date '2023-07-16'),
                       (8, 11.00, 0.00, date '2023-07-16'),
                       (9, 12.00, null, date '2023-07-16')
                """);

        mvc.perform(get(PORTFOLIO_PATH, 3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAccountValue").value(nullValue()))
                .andExpect(jsonPath("$.availableCash").value(60.00))
                .andExpect(jsonPath("$.positions.length()").value(5))
                .andExpect(jsonPath("$.positions[0].marketValue").value(nullValue()))
                .andExpect(jsonPath("$.positions[0].totalReturnPercentage").value(nullValue()))
                .andExpect(jsonPath("$.positions[0].dailyReturnPercentage").value(nullValue()))
                .andExpect(jsonPath("$.positions[1].marketValue").value(12.00))
                .andExpect(jsonPath("$.positions[1].totalReturnPercentage").value(nullValue()))
                .andExpect(jsonPath("$.positions[1].dailyReturnPercentage").value(20.0000))
                .andExpect(jsonPath("$.positions[2].marketValue").value(nullValue()))
                .andExpect(jsonPath("$.positions[2].totalReturnPercentage").value(nullValue()))
                .andExpect(jsonPath("$.positions[2].dailyReturnPercentage").value(nullValue()))
                .andExpect(jsonPath("$.positions[3].marketValue").value(11.00))
                .andExpect(jsonPath("$.positions[3].totalReturnPercentage").value(10.0000))
                .andExpect(jsonPath("$.positions[3].dailyReturnPercentage").value(nullValue()))
                .andExpect(jsonPath("$.positions[4].marketValue").value(12.00))
                .andExpect(jsonPath("$.positions[4].totalReturnPercentage").value(20.0000))
                .andExpect(jsonPath("$.positions[4].dailyReturnPercentage").value(nullValue()));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresConfiguration {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:17-alpine").withInitScript("database.sql");
        }
    }
}
