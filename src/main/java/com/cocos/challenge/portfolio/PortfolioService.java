package com.cocos.challenge.portfolio;

import com.cocos.challenge.account.AccountCalculator;
import com.cocos.challenge.account.AccountCalculator.Holding;
import com.cocos.challenge.instrument.InstrumentType;
import com.cocos.challenge.marketdata.MarketData;
import com.cocos.challenge.marketdata.MarketDataRepository;
import com.cocos.challenge.order.OrderRepository;
import com.cocos.challenge.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PortfolioService {

    private static final BigDecimal PERCENT = BigDecimal.valueOf(100);

    private final UserRepository users;
    private final OrderRepository orders;
    private final MarketDataRepository marketData;

    public PortfolioService(UserRepository users, OrderRepository orders, MarketDataRepository marketData) {
        this.users = users;
        this.orders = orders;
        this.marketData = marketData;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<PortfolioResponse> findPortfolio(Integer userId) {
        if (!users.existsById(userId)) {
            return Optional.empty();
        }

        var balances = AccountCalculator.calculate(orders.findByUser_IdOrderByDatetimeAscIdAsc(userId));
        var holdings = balances.holdings().values().stream()
                .filter(holding -> holding.instrument().getType() != InstrumentType.MONEDA)
                .sorted(Comparator.comparing(holding -> holding.instrument().getId()))
                .toList();
        var instrumentIds = holdings.stream().map(holding -> holding.instrument().getId()).toList();
        var quotes = latestQuotes(instrumentIds);

        BigDecimal total = balances.unidentifiedHolding() ? null : balances.actualCash();
        var positions = new ArrayList<PortfolioResponse.Position>(holdings.size());
        for (Holding holding : holdings) {
            MarketData quote = quotes.get(holding.instrument().getId());
            BigDecimal close = quote == null ? null : quote.getClose();
            BigDecimal marketValue = close == null || holding.shares() == null ? null
                    : close.multiply(BigDecimal.valueOf(holding.shares()));
            total = total == null || marketValue == null ? null : total.add(marketValue);
            positions.add(position(holding, quote, close, marketValue));
        }
        return Optional.of(new PortfolioResponse(money(total), money(balances.availableCash()), List.copyOf(positions)));
    }

    private Map<Integer, MarketData> latestQuotes(List<Integer> instrumentIds) {
        if (instrumentIds.isEmpty()) {
            return Map.of();
        }
        return marketData.findLatestDatedByInstrumentIds(instrumentIds).stream()
                .collect(Collectors.toMap(quote -> quote.getInstrument().getId(), Function.identity()));
    }

    private static PortfolioResponse.Position position(Holding holding, MarketData quote,
                                                       BigDecimal close, BigDecimal marketValue) {
        var instrument = holding.instrument();
        var basis = holding.costBasis();
        BigDecimal totalReturn = close == null || basis == null ? null
                : percentageChange(close.multiply(new BigDecimal(basis.denominator())), basis.numerator());
        BigDecimal dailyReturn = quote == null ? null : percentageChange(close, quote.getPreviousClose());
        return new PortfolioResponse.Position(instrument.getId(), instrument.getTicker(), instrument.getName(),
                holding.shares(), money(marketValue), totalReturn, dailyReturn);
    }

    private static BigDecimal percentageChange(BigDecimal value, BigDecimal basis) {
        if (value == null || basis == null || basis.signum() == 0) {
            return null;
        }
        return value.subtract(basis).multiply(PERCENT).divide(basis, 4, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_EVEN);
    }
}
