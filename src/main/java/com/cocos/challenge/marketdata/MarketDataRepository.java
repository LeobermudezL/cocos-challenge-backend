package com.cocos.challenge.marketdata;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MarketDataRepository extends JpaRepository<MarketData, Integer> {

    Optional<MarketData> findFirstByInstrument_IdAndDateIsNotNullOrderByDateDescIdDesc(Integer instrumentId);
}
