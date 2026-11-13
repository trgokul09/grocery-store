package com.grocery.sale;

import com.grocery.common.PaymentMethod;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;

@Entity @Table(name = "sale_payments")
@Getter @Setter @NoArgsConstructor
public class SalePayment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "sale_id") private Sale sale;
    @Enumerated(EnumType.STRING) private PaymentMethod method;
    private BigDecimal amount;
}
