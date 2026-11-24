package com.cocos.challenge.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderResponse(Integer id, Integer userId, Integer instrumentId, OrderSide side, OrderType type,
                            OrderStatus status, Integer quantity, BigDecimal price, LocalDateTime datetime) {
}
