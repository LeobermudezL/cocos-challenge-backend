package com.cocos.challenge.marketdata;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MarketDataRepository extends JpaRepository<MarketData, Integer> {

    Optional<MarketData> findFirstByInstrument_IdAndDateIsNotNullOrderByDateDescIdDesc(Integer instrumentId);

    @Query(value = """
            select distinct on (instrumentid) m.*
            from marketdata m
            where instrumentid in (:instrumentIds) and date is not null
            order by instrumentid, date desc, id desc
            """, nativeQuery = true)
    List<MarketData> findLatestDatedByInstrumentIds(@Param("instrumentIds") Collection<Integer> instrumentIds);
}
