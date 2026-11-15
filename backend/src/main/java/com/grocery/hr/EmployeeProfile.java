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
@Table(name = "employee_profiles")
@Getter @Setter @NoArgsConstructor
public class EmployeeProfile {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "user_id", unique = true)
    private User user;

    @Column(nullable = false, unique = true, length = 30)
    private String employeeCode;

    private String phone;
    private String email;
    private String designation = "Store Associate";
    private LocalDate joiningDate = LocalDate.now();
    private String employmentStatus = "ACTIVE";
    private String assignedShift = "GENERAL";
    private String shiftStartTime = "09:00";
    private String shiftEndTime = "18:00";
    private String address;

    private String salaryType = "MONTHLY";
    private BigDecimal basicSalary = new BigDecimal("15000.00");
    private BigDecimal dailyWage = new BigDecimal("576.92");
    private BigDecimal overtimeHourlyRate = new BigDecimal("100.00");
    private int paidLeaveQuota = 12;

    private String bankAccountNo;
    private String ifscCode;
    private String upiId;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
