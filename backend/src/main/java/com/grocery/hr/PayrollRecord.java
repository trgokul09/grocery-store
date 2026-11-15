package com.grocery.hr;

import com.grocery.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payroll_records")
@Getter @Setter @NoArgsConstructor
public class PayrollRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 35)
    private String payslipNo;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false, length = 7)
    private String payrollMonth; // YYYY-MM

    private String salaryType = "MONTHLY";
    private BigDecimal basicSalary = BigDecimal.ZERO;
    private BigDecimal dailyWage = BigDecimal.ZERO;
    private int workingDays = 26;

    private BigDecimal presentDays = BigDecimal.ZERO;
    private int halfDays = 0;
    private BigDecimal paidLeaveDays = BigDecimal.ZERO;
    private BigDecimal unpaidLeaveDays = BigDecimal.ZERO;
    private BigDecimal absentDays = BigDecimal.ZERO;
    private int lateDays = 0;
    private BigDecimal payableDays = BigDecimal.ZERO;
    private BigDecimal overtimeHours = BigDecimal.ZERO;

    private BigDecimal earnedBasic = BigDecimal.ZERO;
    private BigDecimal overtimePay = BigDecimal.ZERO;
    private BigDecimal bonus = BigDecimal.ZERO;
    private BigDecimal incentives = BigDecimal.ZERO;
    private BigDecimal leaveDeduction = BigDecimal.ZERO;
    private BigDecimal advanceDeduction = BigDecimal.ZERO;
    private BigDecimal otherDeductions = BigDecimal.ZERO;

    private BigDecimal grossSalary = BigDecimal.ZERO;
    private BigDecimal netSalary = BigDecimal.ZERO;
    private BigDecimal paidAmount = BigDecimal.ZERO;
    private BigDecimal pendingAmount = BigDecimal.ZERO;

    private String paymentStatus = "PENDING"; // PENDING, PARTIALLY_PAID, PAID
    private String lastPaymentMethod;
    private Instant lastPaidAt;
    private String notes;
    private Long generatedBy;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
