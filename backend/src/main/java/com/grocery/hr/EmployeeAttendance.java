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
@Table(name = "employee_attendance")
@Getter @Setter @NoArgsConstructor
public class EmployeeAttendance {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false)
    private LocalDate attendanceDate;

    @Column(nullable = false, length = 20)
    private String status = "PRESENT"; // PRESENT, ABSENT, HALF_DAY, LEAVE, WEEK_OFF

    @Column(length = 20)
    private String leaveType; // PAID, UNPAID

    private Instant checkIn;
    private Instant checkOut;

    @Column(name = "is_late", nullable = false)
    private boolean late = false;

    private int lateMinutes = 0;
    private BigDecimal workedHours = BigDecimal.ZERO;
    private BigDecimal overtimeHours = BigDecimal.ZERO;
    private String notes;
    private Long recordedBy;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
