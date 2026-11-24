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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(ChallengeApplicationTests.PostgresConfiguration.class)
@Transactional
class ChallengeApplicationTests {

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

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresConfiguration {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:17-alpine").withInitScript("database.sql");
        }
    }
}
