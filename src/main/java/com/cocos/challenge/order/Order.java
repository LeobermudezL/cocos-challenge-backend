package com.cocos.challenge.order;

import com.cocos.challenge.instrument.Instrument;
import com.cocos.challenge.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "orders")
@DynamicUpdate
public class Order {

    private static final int PRICE_PRECISION = 10;
    private static final int PRICE_SCALE = 2;
    private static final BigDecimal PRICE_LIMIT = BigDecimal.TEN.pow(PRICE_PRECISION - PRICE_SCALE);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "instrumentid")
    private Instrument instrument;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "userid")
    private User user;

    @Column(name = "size")
    private Integer size;

    @Column(name = "price", precision = PRICE_PRECISION, scale = PRICE_SCALE)
    @ColumnTransformer(read = "nullif(price, 'NaN'::numeric)")
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 10)
    @ColumnTransformer(read = "case when type in ('MARKET', 'LIMIT') then type end")
    private OrderType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", length = 10)
    @ColumnTransformer(read = "case when side in ('BUY', 'SELL', 'CASH_IN', 'CASH_OUT') then side end")
    private OrderSide side;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    @ColumnTransformer(read = "case when status in ('NEW', 'FILLED', 'REJECTED', 'CANCELLED') then status end")
    private OrderStatus status;

    @Column(name = "datetime")
    private LocalDateTime datetime;

    protected Order() {
    }

    public Order(Instrument instrument, User user, Integer size, BigDecimal price,
                 OrderType type, OrderSide side, OrderStatus status, LocalDateTime datetime) {
        this.instrument = instrument;
        this.user = user;
        this.size = size;
        this.price = validatePrice(price);
        this.type = type;
        this.side = side;
        this.status = status;
        this.datetime = datetime;
    }

    public static BigDecimal validatePrice(BigDecimal price) {
        if (price == null) {
            return null;
        }
        if (price.abs().compareTo(PRICE_LIMIT) >= 0 || price.stripTrailingZeros().scale() > PRICE_SCALE) {
            throw new IllegalArgumentException("Order price must be exactly representable as NUMERIC(10,2)");
        }
        return price.setScale(PRICE_SCALE);
    }

    public boolean cancel() {
        if (status != OrderStatus.NEW) {
            return false;
        }
        status = OrderStatus.CANCELLED;
        return true;
    }

    public Integer getId() {
        return id;
    }

    public Instrument getInstrument() {
        return instrument;
    }

    public User getUser() {
        return user;
    }

    public Integer getSize() {
        return size;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public OrderType getType() {
        return type;
    }

    public OrderSide getSide() {
        return side;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public LocalDateTime getDatetime() {
        return datetime;
    }
}
