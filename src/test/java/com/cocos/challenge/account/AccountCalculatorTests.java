package com.cocos.challenge.account;

import com.cocos.challenge.instrument.Instrument;
import com.cocos.challenge.order.Order;
import com.cocos.challenge.order.OrderSide;
import com.cocos.challenge.order.OrderStatus;
import com.cocos.challenge.order.OrderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static com.cocos.challenge.order.OrderSide.BUY;
import static com.cocos.challenge.order.OrderSide.CASH_IN;
import static com.cocos.challenge.order.OrderSide.CASH_OUT;
import static com.cocos.challenge.order.OrderSide.SELL;
import static com.cocos.challenge.order.OrderStatus.CANCELLED;
import static com.cocos.challenge.order.OrderStatus.FILLED;
import static com.cocos.challenge.order.OrderStatus.NEW;
import static com.cocos.challenge.order.OrderStatus.REJECTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountCalculatorTests {

    private static final int INSTRUMENT_ID = 31;

    private final Instrument instrument = mock(Instrument.class);

    @BeforeEach
    void setInstrumentId() {
        when(instrument.getId()).thenReturn(INSTRUMENT_ID);
    }

    @Test
    void calculatesActualAndAvailableBalancesFromTheRelevantStatuses() {
        var balances = AccountCalculator.calculate(List.of(
                order(CASH_IN, FILLED, 10_000, "1"),
                order(CASH_OUT, FILLED, 200, "1"),
                order(BUY, FILLED, 10, "100.25"),
                order(SELL, FILLED, 2, "110.10"),
                order(BUY, NEW, 3, "12.34"),
                order(SELL, NEW, 3, "110"),
                order(BUY, CANCELLED, 200, "100"),
                order(SELL, CANCELLED, 200, "100"),
                order(BUY, REJECTED, 300, "100"),
                order(SELL, REJECTED, 300, "100")));

        assertThat(balances.actualCash()).isEqualByComparingTo("9017.70");
        assertThat(balances.reservedCash()).isEqualByComparingTo("37.02");
        assertThat(balances.availableCash()).isEqualByComparingTo("8980.68");
        assertThat(balances.holdings().get(INSTRUMENT_ID).shares()).isEqualTo(8);
        assertThat(balances.reservedShares()).containsEntry(INSTRUMENT_ID, 3L);
        assertThat(balances.availableShares(INSTRUMENT_ID)).isEqualTo(5);
    }

    @Test
    void retainsTheAverageOnPartialSalesAndWeightsOnlyTheRemainingSharesAtNextPurchase() {
        var firstBuy = order(BUY, FILLED, 3, "10.01");
        var secondBuy = order(BUY, FILLED, 2, "12.03");
        var partialSale = order(SELL, FILLED, 2, "20");
        var thirdBuy = order(BUY, FILLED, 1, "14.02");

        var afterSale = holdingAfter(firstBuy, secondBuy, partialSale);
        assertThat(afterSale.shares()).isEqualTo(3);
        assertThat(afterSale.costBasis()).isEqualTo(holdingAfter(firstBuy, secondBuy).costBasis());
        assertBasis(afterSale, "54.09", 5);

        var reopenedPurchase = holdingAfter(firstBuy, secondBuy, partialSale, thirdBuy);
        assertBasis(reopenedPurchase, "46.474", 4);
    }

    @Test
    void resetsTheBasisWhenSharesReachZeroAndThePositionReopens() {
        var buy = order(BUY, FILLED, 3, "10.01");
        var close = order(SELL, FILLED, 3, "15");

        assertThat(holdingAfter(buy, close)).isNull();
        var reopened = holdingAfter(buy, close, order(BUY, FILLED, 2, "11.20"));
        assertThat(reopened.shares()).isEqualTo(2);
        assertBasis(reopened, "22.40", 2);
    }

    @Test
    void keepsNegativeHoldingsAndCostsOnlySharesBoughtBeyondTheShortfall() {
        var oversell = order(SELL, FILLED, 5, "10");
        var partialRecovery = order(BUY, FILLED, 2, "9");
        var negative = holdingAfter(oversell, partialRecovery);

        assertThat(negative.shares()).isEqualTo(-3);
        assertThat(negative.costBasis()).isNull();
        var positive = holdingAfter(oversell, partialRecovery, order(BUY, FILLED, 5, "8.20"));
        assertThat(positive.shares()).isEqualTo(2);
        assertBasis(positive, "16.40", 2);
    }

    @Test
    void keepsKnownSharesWhenTheAcquisitionPriceIsMissing() {
        var balances = AccountCalculator.calculate(List.of(
                order(BUY, FILLED, 2, null),
                order(BUY, FILLED, 3, "10.50"),
                order(SELL, FILLED, 1, "11")));

        assertThat(balances.holdings().get(INSTRUMENT_ID).shares()).isEqualTo(4);
        assertThat(balances.holdings().get(INSTRUMENT_ID).costBasis()).isNull();
        assertThat(balances.actualCash()).isNull();
        assertThat(balances.availableCash()).isNull();
    }

    @Test
    void retainsPrecisionForRepeatingCostAllocationAndBalancesLargerThanAPrice() {
        var balances = AccountCalculator.calculate(List.of(
                order(CASH_IN, FILLED, 2, "99999999.99"),
                order(BUY, FILLED, 2, "10"),
                order(BUY, FILLED, 1, "10.01"),
                order(SELL, FILLED, 2, "10"),
                order(BUY, FILLED, 1, "10")));

        assertThat(balances.actualCash()).isEqualByComparingTo("199999979.97");
        assertBasis(balances.holdings().get(INSTRUMENT_ID), "60.01", 6);
    }

    @Test
    void tracksLargeQuantitiesAndReservationsByInstrument() {
        Instrument other = mock(Instrument.class);
        when(other.getId()).thenReturn(47);
        var balances = AccountCalculator.calculate(List.of(
                order(BUY, FILLED, Integer.MAX_VALUE, "1"),
                order(BUY, FILLED, Integer.MAX_VALUE, "1"),
                order(SELL, NEW, Integer.MAX_VALUE, "1"),
                order(SELL, NEW, 80, "1"),
                order(other, BUY, FILLED, 10, "1"),
                order(other, SELL, NEW, 3, "1")));

        assertThat(balances.holdings().get(INSTRUMENT_ID).shares()).isEqualTo(4_294_967_294L);
        assertThat(balances.reservedShares()).containsEntry(INSTRUMENT_ID, 2_147_483_727L);
        assertThat(balances.availableShares(INSTRUMENT_ID)).isEqualTo(2_147_483_567L);
        assertThat(balances.availableShares(47)).isEqualTo(7L);
    }

    @Test
    void invalidPendingQuantitiesAndPricesPropagateUncertaintyOnlyToAffectedAvailability() {
        Instrument other = mock(Instrument.class);
        when(other.getId()).thenReturn(47);
        var firstFill = order(BUY, FILLED, 10, "1");
        var secondFill = order(other, BUY, FILLED, 5, "1");

        for (var invalid : List.of(order(BUY, NEW, null, "1"), order(BUY, NEW, 0, "1"),
                order(BUY, NEW, -2, "1"), order(BUY, NEW, 2, null), order(BUY, NEW, 2, "0"),
                order(BUY, NEW, 2, "-1"))) {
            var balances = AccountCalculator.calculate(List.of(firstFill, secondFill, invalid));
            assertThat(balances.actualCash()).isEqualByComparingTo("-15");
            assertThat(balances.reservedCash()).isNull();
            assertThat(balances.availableCash()).isNull();
            assertThat(balances.availableShares(INSTRUMENT_ID)).isEqualTo(10L);
        }
        for (var invalid : List.of(order(SELL, NEW, null, "1"), order(SELL, NEW, 0, "1"),
                order(SELL, NEW, -2, "1"), order(SELL, NEW, 2, null), order(SELL, NEW, 2, "0"),
                order(SELL, NEW, 2, "-1"))) {
            var balances = AccountCalculator.calculate(List.of(firstFill, secondFill, invalid,
                    order(SELL, NEW, 1, "1"), order(other, SELL, NEW, 2, "1")));
            assertThat(balances.availableCash()).isEqualByComparingTo("-15");
            assertThat(balances.availableShares(INSTRUMENT_ID)).isNull();
            assertThat(balances.availableShares(47)).isEqualTo(3L);
        }
    }

    @Test
    void unknownSideOrStatusDoesNotDisappearFromAccountCalculations() {
        var filledWithoutSide = AccountCalculator.calculate(List.of(
                order(BUY, FILLED, 10, "1"), order(null, FILLED, 2, "2"),
                order(null, CANCELLED, null, null), order(null, REJECTED, null, null)));
        assertThat(filledWithoutSide.actualCash()).isNull();
        assertThat(filledWithoutSide.holdings().get(INSTRUMENT_ID).shares()).isNull();
        assertThat(filledWithoutSide.availableShares(INSTRUMENT_ID)).isNull();
        assertThat(filledWithoutSide.reservedCash()).isEqualByComparingTo("0");

        var sellWithoutStatus = AccountCalculator.calculate(List.of(order(SELL, null, 3, "1")));
        assertThat(sellWithoutStatus.actualCash()).isNull();
        assertThat(sellWithoutStatus.holdings().get(INSTRUMENT_ID).shares()).isNull();
        assertThat(sellWithoutStatus.availableShares(INSTRUMENT_ID)).isNull();
        assertThat(sellWithoutStatus.reservedShares()).containsKey(INSTRUMENT_ID);
        assertThat(sellWithoutStatus.reservedCash()).isEqualByComparingTo("0");
    }

    @Test
    void keepsCashKnownForAZeroPriceSellWithUnknownStatus() {
        var balances = AccountCalculator.calculate(List.of(
                order(CASH_IN, FILLED, 100, "1"),
                order(BUY, FILLED, 10, "2"),
                order(SELL, null, 3, "0")));

        assertThat(balances.actualCash()).isEqualByComparingTo("80");
        assertThat(balances.availableCash()).isEqualByComparingTo("80");
        assertThat(balances.holdings().get(INSTRUMENT_ID).shares()).isNull();
        assertThat(balances.availableShares(INSTRUMENT_ID)).isNull();
    }

    @Test
    void distinguishesUnidentifiedShareReservationsFromUnidentifiedFilledHoldings() {
        var buy = order(BUY, FILLED, 10, "5");
        var reserved = AccountCalculator.calculate(List.of(buy, order(null, SELL, NEW, 3, "1")));
        assertThat(reserved.availableShares(INSTRUMENT_ID)).isNull();
        assertThat(reserved.holdings().get(INSTRUMENT_ID).shares()).isEqualTo(10L);
        assertThat(reserved.availableCash()).isEqualByComparingTo("-50");

        var filled = AccountCalculator.calculate(List.of(buy, order(null, SELL, FILLED, 3, "1")));
        assertThat(filled.unidentifiedHolding()).isTrue();
        assertThat(filled.availableShares(47)).isNull();
        assertThat(filled.holdings().get(INSTRUMENT_ID).shares()).isNull();
        assertThat(filled.availableCash()).isEqualByComparingTo("-47");
    }

    private static void assertBasis(AccountCalculator.Holding holding, String cost, long shares) {
        assertThat(holding.costBasis().numerator().multiply(BigDecimal.valueOf(shares)))
                .isEqualByComparingTo(new BigDecimal(cost).multiply(new BigDecimal(holding.costBasis().denominator())));
    }

    private AccountCalculator.Holding holdingAfter(Order... orders) {
        return AccountCalculator.calculate(List.of(orders)).holdings().get(INSTRUMENT_ID);
    }

    private Order order(OrderSide side, OrderStatus status, Integer size, String price) {
        return order(instrument, side, status, size, price);
    }

    private Order order(Instrument asset, OrderSide side, OrderStatus status, Integer size, String price) {
        return new Order(asset, null, size, price == null ? null : new BigDecimal(price),
                OrderType.MARKET, side, status, LocalDateTime.of(2023, 7, 14, 12, 0));
    }
}
