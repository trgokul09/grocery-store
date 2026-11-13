package com.grocery.shift;

import com.grocery.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;

@Entity @Table(name = "shifts")
@Getter @Setter @NoArgsConstructor
public class Shift {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id") private User user;
    private Instant openedAt = Instant.now();
    private Instant closedAt;
    private BigDecimal openingCash = BigDecimal.ZERO;
    private BigDecimal countedCash;
    private BigDecimal expectedCash;
    private BigDecimal variance;
    private BigDecimal cashSales = BigDecimal.ZERO;
    private BigDecimal cashExpenses = BigDecimal.ZERO;
    private BigDecimal supplierCashPayments = BigDecimal.ZERO;
    @Enumerated(EnumType.STRING) private ShiftStatus status = ShiftStatus.OPEN;
    private String notes;
}
