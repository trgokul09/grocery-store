package com.grocery.hr;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payroll_settings")
@Getter @Setter @NoArgsConstructor
public class PayrollSettings {
    @Id
    private Long id = 1L;
    private int standardWorkingDays = 26;
    private BigDecimal standardDailyHours = new BigDecimal("8.00");
    private int graceLateMinutes = 15;
    private BigDecimal overtimeMultiplier = new BigDecimal("1.00");
    private int defaultAnnualPaidLeaves = 12;
    private boolean deductAbsentAutomatically = false;
    private boolean autoConvertAbsentToPaidLeave = true;
    private BigDecimal freeAbsentDaysAllowed = new BigDecimal("0.0");
    private int lateDaysThreshold = 3;
    private BigDecimal latePenaltyPerDay = new BigDecimal("0.00");
    private Instant updatedAt = Instant.now();
}
