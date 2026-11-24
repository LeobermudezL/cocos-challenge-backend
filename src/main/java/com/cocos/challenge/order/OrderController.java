package com.cocos.challenge.order;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {

    private final OrderSubmissionService submissions;
    private final OrderCancellationService cancellations;

    public OrderController(OrderSubmissionService submissions, OrderCancellationService cancellations) {
        this.submissions = submissions;
        this.cancellations = cancellations;
    }

    @PostMapping("/api/v1/users/{userId}/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse submit(@PathVariable("userId") Integer userId,
                                @Valid @RequestBody OrderSubmissionRequest request) {
        return submissions.submit(userId, request);
    }

    @PostMapping("/api/v1/users/{userId}/orders/{orderId}/cancel")
    public OrderResponse cancel(@PathVariable("userId") Integer userId, @PathVariable("orderId") Integer orderId) {
        return cancellations.cancel(userId, orderId);
    }
}
