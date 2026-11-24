package com.cocos.challenge.instrument;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class InstrumentService {

    private final InstrumentRepository instruments;

    public InstrumentService(InstrumentRepository instruments) {
        this.instruments = instruments;
    }

    public List<InstrumentResponse> search(String ticker, String name) {
        return instruments.searchTradable(normalize(ticker), normalize(name)).stream()
                .map(instrument -> new InstrumentResponse(
                        instrument.getId(), instrument.getTicker(), instrument.getName(), instrument.getType()))
                .toList();
    }

    private static String normalize(String filter) {
        return StringUtils.hasText(filter) ? filter.strip() : null;
    }
}
