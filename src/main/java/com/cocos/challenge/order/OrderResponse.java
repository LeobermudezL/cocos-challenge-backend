package com.cocos.challenge.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderResponse(Integer id, Integer userId, Integer instrumentId, OrderSide side, OrderType type,
                            OrderStatus status, Integer quantity, BigDecimal price, LocalDateTime datetime) {

    static OrderResponse from(Order order) {
        var instrument = order.getInstrument();
        return new OrderResponse(order.getId(), order.getUser().getId(), instrument == null ? null : instrument.getId(),
                order.getSide(), order.getType(), order.getStatus(), order.getSize(), order.getPrice(),
                order.getDatetime());
    }
}
