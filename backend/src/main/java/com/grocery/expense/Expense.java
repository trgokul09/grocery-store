package com.grocery.expense;

import com.grocery.common.PaymentMethod;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity @Table(name = "expenses")
@Getter @Setter @NoArgsConstructor
public class Expense {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Enumerated(EnumType.STRING) private ExpenseCategory category;
    private BigDecimal amount;
    @Enumerated(EnumType.STRING) private PaymentMethod method;
    private String description;
    private LocalDate expenseDate;
    private Long shiftId;
    private Long createdBy;
    private Instant createdAt = Instant.now();
}
