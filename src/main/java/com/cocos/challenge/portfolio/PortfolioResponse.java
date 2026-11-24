package com.cocos.challenge.portfolio;

import java.math.BigDecimal;
import java.util.List;

public record PortfolioResponse(BigDecimal totalAccountValue, BigDecimal availableCash, List<Position> positions) {

    public record Position(Integer instrumentId, String ticker, String name, Long shares,
                           BigDecimal marketValue, BigDecimal totalReturnPercentage, BigDecimal dailyReturnPercentage) {
    }
}
