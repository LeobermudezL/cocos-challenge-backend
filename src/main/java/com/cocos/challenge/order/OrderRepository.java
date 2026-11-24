package com.cocos.challenge.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Integer> {

    List<Order> findByUser_IdOrderByDatetimeAscIdAsc(Integer userId);

    Optional<Order> findByIdAndUser_Id(Integer id, Integer userId);
}
