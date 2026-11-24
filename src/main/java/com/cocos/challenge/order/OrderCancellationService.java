package com.cocos.challenge.order;

import com.cocos.challenge.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderCancellationService {

    private final UserRepository users;
    private final OrderRepository orders;

    public OrderCancellationService(UserRepository users, OrderRepository orders) {
        this.users = users;
        this.orders = orders;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderResponse cancel(Integer userId, Integer orderId) {
        users.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        var order = orders.findByIdAndUser_Id(orderId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        if (!order.cancel()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only NEW orders can be cancelled");
        }
        return OrderResponse.from(order);
    }
}
