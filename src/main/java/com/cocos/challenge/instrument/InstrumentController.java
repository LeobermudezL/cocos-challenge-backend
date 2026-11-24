package com.cocos.challenge.instrument;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class InstrumentController {

    private final InstrumentService instruments;

    public InstrumentController(InstrumentService instruments) {
        this.instruments = instruments;
    }

    @GetMapping("/api/v1/instruments")
    public List<InstrumentResponse> search(@RequestParam(name = "ticker", required = false) String ticker,
                                           @RequestParam(name = "name", required = false) String name) {
        return instruments.search(ticker, name);
    }
}
