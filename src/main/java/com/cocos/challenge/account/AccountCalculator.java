package com.cocos.challenge.account;

import com.cocos.challenge.instrument.Instrument;
import com.cocos.challenge.order.Order;
import com.cocos.challenge.order.OrderSide;
import com.cocos.challenge.order.OrderStatus;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AccountCalculator {

    private AccountCalculator() {
    }

    public static Balances calculate(List<Order> chronologicalOrders) {
        var ledger = new Ledger();
        for (Order order : chronologicalOrders) {
            OrderStatus status = order.getStatus();
            if (status == OrderStatus.FILLED || status == null) {
                ledger.applyFill(order);
            }
            if (status == OrderStatus.NEW || status == null) {
                ledger.applyReservation(order);
            }
        }
        return ledger.balances();
    }

    private static class Ledger {

        private final Map<Integer, Holding> holdings = new HashMap<>();
        private final Map<Integer, Long> reservedShares = new HashMap<>();
        private final Set<Integer> undatedTrades = new HashSet<>();
        private final Set<Integer> soldInstruments = new HashSet<>();
        private BigDecimal actualCash = BigDecimal.ZERO;
        private BigDecimal reservedCash = BigDecimal.ZERO;
        private boolean unidentifiedHolding;
        private boolean unidentifiedReservation;

        private void applyFill(Order order) {
            Integer size = order.getSize();
            if (size != null && size == 0) {
                return;
            }

            OrderSide side = order.getSide();
            BigDecimal price = order.getPrice();
            boolean knownFill = order.getStatus() != null && side != null && size != null && size > 0;
            boolean zeroCashImpact = size != null && size > 0 && price != null && price.signum() == 0;

            if ((!knownFill && !zeroCashImpact) || price == null || price.signum() < 0) {
                actualCash = null;
            } else if (knownFill && actualCash != null) {
                BigDecimal amount = price.multiply(BigDecimal.valueOf(size));
                actualCash = switch (side) {
                    case CASH_IN, SELL -> actualCash.add(amount);
                    case CASH_OUT, BUY -> actualCash.subtract(amount);
                };
            }

            if (side == OrderSide.CASH_IN || side == OrderSide.CASH_OUT) {
                return;
            }
            Instrument instrument = order.getInstrument();
            if (instrument == null) {
                unidentifiedHolding = true;
            } else if (!knownFill) {
                holdings.put(instrument.getId(), new Holding(instrument, null, null));
            } else {
                applyTrade(order, instrument);
            }
        }

        private void applyTrade(Order order, Instrument instrument) {
            Integer instrumentId = instrument.getId();
            Holding previous = holdings.get(instrumentId);
            if (previous != null && previous.shares() == null) {
                return;
            }
            if (order.getDatetime() == null) {
                undatedTrades.add(instrumentId);
            }
            long previousShares = previous == null ? 0 : previous.shares();
            long size = order.getSize();
            boolean buy = order.getSide() == OrderSide.BUY;
            if (!buy) {
                soldInstruments.add(instrumentId);
            }

            long shares = Math.addExact(previousShares, buy ? size : -size);
            if (shares == 0) {
                holdings.remove(instrumentId);
            } else {
                CostBasis basis = null;
                if (shares > 0) {
                    basis = buy ? basisAfterBuy(previous, shares, size, order.getPrice()) : previous.costBasis();
                }
                holdings.put(instrumentId, new Holding(instrument, shares, basis));
            }
        }

        private void applyReservation(Order order) {
            OrderSide side = order.getSide();
            if (side == OrderSide.CASH_IN || side == OrderSide.CASH_OUT) {
                return;
            }

            Integer size = order.getSize();
            BigDecimal price = order.getPrice();
            boolean valid = order.getStatus() != null && side != null
                    && size != null && size > 0 && price != null && price.signum() > 0;
            if (side == null || side == OrderSide.BUY) {
                if (!valid) {
                    reservedCash = null;
                } else if (reservedCash != null) {
                    reservedCash = reservedCash.add(price.multiply(BigDecimal.valueOf(size)));
                }
            }
            if (side == null || side == OrderSide.SELL) {
                Instrument instrument = order.getInstrument();
                if (instrument == null) {
                    unidentifiedReservation = true;
                } else if (!valid) {
                    reservedShares.put(instrument.getId(), null);
                } else if (reservedShares.getOrDefault(instrument.getId(), 0L) != null) {
                    reservedShares.merge(instrument.getId(), size.longValue(), Math::addExact);
                }
            }
        }

        private Balances balances() {
            holdings.replaceAll((id, holding) -> {
                if (unidentifiedHolding) {
                    return new Holding(holding.instrument(), null, null);
                }
                if (undatedTrades.contains(id) && soldInstruments.contains(id)) {
                    return new Holding(holding.instrument(), holding.shares(), null);
                }
                return holding;
            });
            return new Balances(actualCash, reservedCash, Map.copyOf(holdings),
                    Collections.unmodifiableMap(reservedShares), unidentifiedHolding, unidentifiedReservation);
        }
    }

    private static CostBasis basisAfterBuy(Holding previous, long shares, long size, BigDecimal price) {
        if (price == null || price.signum() < 0) {
            return null;
        }
        if (previous == null || previous.shares() < 0) {
            return new CostBasis(price, BigInteger.ONE);
        }
        return previous.costBasis() == null ? null
                : previous.costBasis().afterBuy(previous.shares(), size, shares, price);
    }

    public record Balances(BigDecimal actualCash, BigDecimal reservedCash,
                           Map<Integer, Holding> holdings, Map<Integer, Long> reservedShares,
                           boolean unidentifiedHolding, boolean unidentifiedReservation) {

        public BigDecimal availableCash() {
            return actualCash == null || reservedCash == null ? null : actualCash.subtract(reservedCash);
        }

        public Long availableShares(Integer instrumentId) {
            if (unidentifiedHolding || unidentifiedReservation) {
                return null;
            }
            Holding holding = holdings.get(instrumentId);
            Long shares = holding == null ? Long.valueOf(0) : holding.shares();
            Long reserved = reservedShares.getOrDefault(instrumentId, 0L);
            return shares == null || reserved == null ? null : Math.subtractExact(shares, reserved);
        }
    }

    public record Holding(Instrument instrument, Long shares, CostBasis costBasis) {
    }

    // The exact average price is numerator divided by denominator.
    public record CostBasis(BigDecimal numerator, BigInteger denominator) {

        private CostBasis afterBuy(long heldShares, long purchasedShares, long totalShares, BigDecimal price) {
            BigDecimal heldCost = numerator.multiply(BigDecimal.valueOf(heldShares));
            BigInteger purchaseWeight = denominator.multiply(BigInteger.valueOf(purchasedShares));
            BigDecimal combinedCost = heldCost.add(price.multiply(new BigDecimal(purchaseWeight)));
            BigInteger combinedShares = denominator.multiply(BigInteger.valueOf(totalShares));
            BigInteger divisor = combinedCost.unscaledValue().gcd(combinedShares);
            return new CostBasis(new BigDecimal(combinedCost.unscaledValue().divide(divisor), combinedCost.scale()),
                    combinedShares.divide(divisor));
        }
    }
}
