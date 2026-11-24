package com.cocos.challenge.marketdata;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MarketDataRepository extends JpaRepository<MarketData, Integer> {

    default Optional<MarketData> findFirstByInstrument_IdAndDateIsNotNullOrderByDateDescIdDesc(Integer instrumentId) {
        return findFirstByInstrument_IdAndDateIsNotNullOrderByDateDescIdDesc(instrumentId, MarketData.class);
    }

    <T> Optional<T> findFirstByInstrument_IdAndDateIsNotNullOrderByDateDescIdDesc(Integer instrumentId,
                                                                                 Class<T> projection);

    record ClosingPrice(BigDecimal close) {
    }

    record ValuationQuote(Integer instrumentId, BigDecimal close, BigDecimal previousClose) {
    }

    @Query(value = """
            select distinct on (instrumentid) instrumentid as "instrumentId",
                   nullif(close, 'NaN'::numeric) as close,
                   nullif(previousclose, 'NaN'::numeric) as "previousClose"
            from marketdata
            where instrumentid in (:instrumentIds) and date is not null
            order by instrumentid, date desc, id desc
            """, nativeQuery = true)
    List<ValuationQuote> findLatestDatedByInstrumentIds(@Param("instrumentIds") Collection<Integer> instrumentIds);
}
