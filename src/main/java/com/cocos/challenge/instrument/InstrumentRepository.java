package com.cocos.challenge.instrument;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface InstrumentRepository extends JpaRepository<Instrument, Integer> {

    @Query("""
            select i from Instrument i
            where i.type <> com.cocos.challenge.instrument.InstrumentType.MONEDA
              and locate(lower(coalesce(:ticker, '')), lower(coalesce(i.ticker, ''))) > 0
              and locate(lower(coalesce(:name, '')), lower(coalesce(i.name, ''))) > 0
            order by i.ticker, i.id
            """)
    List<Instrument> searchTradable(@Param("ticker") String ticker, @Param("name") String name);
}
