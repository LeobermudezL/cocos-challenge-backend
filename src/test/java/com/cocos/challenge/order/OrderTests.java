package com.cocos.challenge.order;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class OrderTests {

    @ParameterizedTest
    @ValueSource(strings = {"930", "930.25", "930.2500", "99999999.99"})
    void acceptsExactlyRepresentablePrices(String value) {
        var price = new BigDecimal(value);
        assertThat(Order.validatePrice(price)).isEqualTo(price.setScale(2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.001", "930.251", "100000000", "-100000000.00"})
    void rejectsUnsupportedPricesBeforeCalculationOrPersistence(String value) {
        var price = new BigDecimal(value);
        assertThatIllegalArgumentException().isThrownBy(() -> Order.validatePrice(price));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new Order(null, null, 1, price, OrderType.LIMIT, OrderSide.BUY, OrderStatus.NEW, null));
    }
}
