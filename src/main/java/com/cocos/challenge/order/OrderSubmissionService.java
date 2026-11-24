package com.cocos.challenge.order;

import com.cocos.challenge.account.AccountCalculator;
import com.cocos.challenge.account.AccountCalculator.Balances;
import com.cocos.challenge.instrument.InstrumentRepository;
import com.cocos.challenge.instrument.InstrumentType;
import com.cocos.challenge.marketdata.MarketDataRepository;
import com.cocos.challenge.marketdata.MarketDataRepository.ClosingPrice;
import com.cocos.challenge.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

@Service
public class OrderSubmissionService {

    private static final BigDecimal MAX_INT = BigDecimal.valueOf(Integer.MAX_VALUE);
    private static final BigDecimal FIRST_UNSUPPORTED_QUANTITY = MAX_INT.add(BigDecimal.ONE);

    private final UserRepository users;
    private final InstrumentRepository instruments;
    private final MarketDataRepository marketData;
    private final OrderRepository orders;

    public OrderSubmissionService(UserRepository users, InstrumentRepository instruments,
                                  MarketDataRepository marketData, OrderRepository orders) {
        this.users = users;
        this.instruments = instruments;
        this.marketData = marketData;
        this.orders = orders;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderResponse submit(Integer userId, OrderSubmissionRequest request) {
        int instrumentId = positiveInt(request.instrumentId(), "instrumentId");
        OrderSide side = switch (request.side()) {
            case "BUY" -> OrderSide.BUY;
            case "SELL" -> OrderSide.SELL;
            case null, default -> throw invalid("Side must be BUY or SELL");
        };
        OrderType type = switch (request.type()) {
            case "MARKET" -> OrderType.MARKET;
            case "LIMIT" -> OrderType.LIMIT;
            case null, default -> throw invalid("Type must be MARKET or LIMIT");
        };

        if ((request.quantity() == null) == (request.amount() == null)) {
            throw invalid("Provide exactly one of quantity or amount");
        }
        Integer explicitQuantity = request.quantity() == null ? null : positiveInt(request.quantity(), "quantity");
        if (request.amount() != null) {
            validateAmount(request.amount());
        }
        if (type == OrderType.MARKET && request.price() != null) {
            throw invalid("Market orders must omit price");
        }
        BigDecimal limitPrice = type == OrderType.LIMIT ? validatePrice(request.price(), "Invalid limit price") : null;

        var user = users.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        var instrument = instruments.findById(instrumentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Instrument not found"));
        if (instrument.getType() == InstrumentType.MONEDA || "ARS".equals(instrument.getTicker())) {
            throw invalid("Cash instruments cannot be traded");
        }

        BigDecimal price = type == OrderType.MARKET ? marketPrice(instrumentId) : limitPrice;
        int quantity = explicitQuantity == null ? quantityFor(request.amount(), price) : explicitQuantity;
        BigDecimal notional = price.multiply(BigDecimal.valueOf(quantity));

        var balances = AccountCalculator.calculate(orders.findByUser_IdOrderByDatetimeAscIdAsc(userId));
        OrderStatus status = OrderStatus.REJECTED;
        if (hasResources(balances, instrumentId, side, quantity, notional)) {
            status = type == OrderType.MARKET ? OrderStatus.FILLED : OrderStatus.NEW;
        }
        var datetime = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        var order = orders.save(new Order(instrument, user, quantity, price, type, side, status, datetime));
        return new OrderResponse(order.getId(), userId, instrumentId, side, type, status, quantity, price, datetime);
    }

    private BigDecimal marketPrice(Integer instrumentId) {
        BigDecimal close = marketData.findFirstByInstrument_IdAndDateIsNotNullOrderByDateDescIdDesc(
                        instrumentId, ClosingPrice.class)
                .map(ClosingPrice::close)
                .orElse(null);
        return validatePrice(close, "No usable closing price for this instrument");
    }

    private static BigDecimal validatePrice(BigDecimal price, String message) {
        if (price == null || price.signum() <= 0) {
            throw invalid(message);
        }
        try {
            return Order.validatePrice(price);
        } catch (IllegalArgumentException e) {
            throw invalid(message);
        }
    }

    private static void validateAmount(BigDecimal amount) {
        if (amount.signum() <= 0 || (amount.scale() > 2 && amount.stripTrailingZeros().scale() > 2)) {
            throw invalid("Amount must be positive with at most two meaningful decimal places");
        }
    }

    private static int quantityFor(BigDecimal amount, BigDecimal price) {
        if (amount.compareTo(price.multiply(FIRST_UNSUPPORTED_QUANTITY)) >= 0) {
            throw invalid("Amount exceeds the maximum order quantity");
        }
        return positiveInt(amount.divide(price, 0, RoundingMode.DOWN), "quantity");
    }

    private static int positiveInt(BigDecimal value, String field) {
        String message = field + " must be a positive whole number within the database integer range";
        if (value == null || value.signum() <= 0 || value.compareTo(MAX_INT) > 0) {
            throw invalid(message);
        }
        try {
            return value.intValueExact();
        } catch (ArithmeticException e) {
            throw invalid(message);
        }
    }

    private static boolean hasResources(Balances balances, int instrumentId, OrderSide side, int quantity,
                                        BigDecimal notional) {
        if (side == OrderSide.BUY) {
            BigDecimal cash = balances.availableCash();
            return cash != null && cash.compareTo(notional) >= 0;
        }
        Long shares = balances.availableShares(instrumentId);
        return shares != null && shares >= quantity;
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
