package com.grocery.hr;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payroll_payments")
@Getter @Setter @NoArgsConstructor
public class PayrollPayment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payroll_id")
    private PayrollRecord payroll;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 10)
    private String method;

    private Long expenseId;
    private String note;
    private Long paidBy;
    private Instant paidAt = Instant.now();
}
