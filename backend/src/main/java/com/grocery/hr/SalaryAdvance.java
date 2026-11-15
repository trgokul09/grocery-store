package com.grocery.hr;

import com.grocery.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "salary_advances")
@Getter @Setter @NoArgsConstructor
public class SalaryAdvance {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private BigDecimal deductedAmount = BigDecimal.ZERO;

    @Column(nullable = false)
    private LocalDate advanceDate;

    @Column(nullable = false, length = 10)
    private String method = "CASH";

    private String reason;

    @Column(nullable = false, length = 20)
    private String status = "OPEN"; // OPEN, DEDUCTED

    private Long expenseId;
    private Long createdBy;
    private Instant createdAt = Instant.now();
}
