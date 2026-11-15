package com.grocery.hr;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public class HrDtos {

    public record EmployeeDto(
        Long userId,
        String username,
        String fullName,
        String role,
        boolean active,
        Long profileId,
        String employeeCode,
        String phone,
        String email,
        String designation,
        LocalDate joiningDate,
        String employmentStatus,
        String assignedShift,
        String shiftStartTime,
        String shiftEndTime,
        String address,
        String salaryType,
        BigDecimal basicSalary,
        BigDecimal dailyWage,
        BigDecimal overtimeHourlyRate,
        Integer paidLeaveQuota,
        String bankAccountNo,
        String ifscCode,
        String upiId,
        boolean salaryConfidential,
        String todayStatus,
        Instant todayCheckIn,
        Instant todayCheckOut,
        boolean todayLate,
        BigDecimal paidLeavesUsed,
        BigDecimal paidLeavesRemaining,
        BigDecimal unpaidLeavesUsed
    ) {}

    public record CreateEmployeeRequest(
        @NotBlank @Size(min = 3, max = 50) @Pattern(regexp = "[A-Za-z0-9._-]+") String username,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Size(max = 120) String fullName,
        @NotBlank @Pattern(regexp = "OWNER|MANAGER|STAFF") String role,
        String employeeCode,
        @Size(max = 30) String phone,
        @Size(max = 120) String email,
        @Size(max = 80) String designation,
        LocalDate joiningDate,
        String employmentStatus,
        String assignedShift,
        String shiftStartTime,
        String shiftEndTime,
        @Size(max = 255) String address,
        String salaryType,
        @DecimalMin("0.00") BigDecimal basicSalary,
        @DecimalMin("0.00") BigDecimal dailyWage,
        @DecimalMin("0.00") BigDecimal overtimeHourlyRate,
        Integer paidLeaveQuota,
        @Size(max = 50) String bankAccountNo,
        @Size(max = 20) String ifscCode,
        @Size(max = 80) String upiId
    ) {}

    public record UpdateEmployeeRequest(
        @NotBlank @Size(max = 120) String fullName,
        @Pattern(regexp = "OWNER|MANAGER|STAFF") String role,
        @Size(max = 30) String phone,
        @Size(max = 120) String email,
        @Size(max = 80) String designation,
        LocalDate joiningDate,
        String employmentStatus,
        String assignedShift,
        String shiftStartTime,
        String shiftEndTime,
        @Size(max = 255) String address,
        String salaryType,
        @DecimalMin("0.00") BigDecimal basicSalary,
        @DecimalMin("0.00") BigDecimal dailyWage,
        @DecimalMin("0.00") BigDecimal overtimeHourlyRate,
        Integer paidLeaveQuota,
        @Size(max = 50) String bankAccountNo,
        @Size(max = 20) String ifscCode,
        @Size(max = 80) String upiId
    ) {}

    public record AttendanceDto(
        Long id,
        Long userId,
        String employeeCode,
        String fullName,
        String designation,
        String assignedShift,
        String shiftStartTime,
        String shiftEndTime,
        LocalDate attendanceDate,
        String status,
        String leaveType,
        Instant checkIn,
        Instant checkOut,
        boolean late,
        int lateMinutes,
        BigDecimal workedHours,
        BigDecimal overtimeHours,
        String notes
    ) {}

    public record MarkAttendanceRequest(
        @NotNull Long userId,
        @NotNull LocalDate attendanceDate,
        @NotBlank @Pattern(regexp = "PRESENT|ABSENT|HALF_DAY|LEAVE|WEEK_OFF") String status,
        String leaveType,
        String checkInTime,
        String checkOutTime,
        @DecimalMin("0.00") BigDecimal workedHours,
        @DecimalMin("0.00") BigDecimal overtimeHours,
        Boolean late,
        Integer lateMinutes,
        @Size(max = 255) String notes
    ) {}

    public record CheckInOutRequest(
        @Size(max = 255) String notes
    ) {}

    public record EmployeeAttendanceSummaryDto(
        Long userId,
        String employeeCode,
        String fullName,
        String designation,
        String assignedShift,
        int totalCalendarDays,
        int workingDaysInPeriod,
        BigDecimal presentDays,
        int halfDays,
        BigDecimal absentDays,
        BigDecimal paidLeaveDays,
        BigDecimal unpaidLeaveDays,
        int weekOffDays,
        int lateDays,
        BigDecimal totalWorkedHours,
        BigDecimal totalOvertimeHours,
        BigDecimal attendancePercentage,
        Map<String, String> dailyStatusMap
    ) {}

    public record AttendanceCalendarResponse(
        String month,
        LocalDate from,
        LocalDate to,
        List<String> dates,
        List<EmployeeAttendanceSummaryDto> employees,
        List<AttendanceDto> records
    ) {}

    public record LeaveRequestDto(
        Long id,
        Long userId,
        String employeeCode,
        String fullName,
        String designation,
        String leaveType,
        boolean paid,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal totalDays,
        String reason,
        String status,
        String reviewedByName,
        Instant reviewedAt,
        String reviewNote,
        Instant createdAt
    ) {}

    public record ApplyLeaveRequest(
        Long userId,
        @NotBlank @Pattern(regexp = "PAID|UNPAID|SICK|CASUAL") String leaveType,
        Boolean paid,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        Boolean halfDay,
        @NotBlank @Size(max = 300) String reason
    ) {}

    public record ReviewLeaveRequest(
        @NotBlank @Pattern(regexp = "APPROVED|REJECTED") String status,
        @Size(max = 255) String reviewNote
    ) {}

    public record LeaveBalanceDto(
        Long userId,
        String employeeCode,
        String fullName,
        int annualPaidQuota,
        BigDecimal paidLeavesUsed,
        BigDecimal paidLeavesPending,
        BigDecimal paidLeavesRemaining,
        BigDecimal unpaidLeavesUsed
    ) {}

    public record SalaryAdvanceDto(
        Long id,
        Long userId,
        String employeeCode,
        String fullName,
        BigDecimal amount,
        BigDecimal deductedAmount,
        BigDecimal remainingAmount,
        LocalDate advanceDate,
        String method,
        String reason,
        String status,
        Instant createdAt
    ) {}

    public record CreateAdvanceRequest(
        @NotNull Long userId,
        @NotNull @DecimalMin("1.00") BigDecimal amount,
        LocalDate advanceDate,
        @NotBlank @Pattern(regexp = "CASH|UPI|CARD") String method,
        @Size(max = 255) String reason
    ) {}

    public record PayrollSettingsDto(
        int standardWorkingDays,
        BigDecimal standardDailyHours,
        int graceLateMinutes,
        BigDecimal overtimeMultiplier,
        int defaultAnnualPaidLeaves,
        boolean deductAbsentAutomatically,
        boolean autoConvertAbsentToPaidLeave,
        BigDecimal freeAbsentDaysAllowed,
        int lateDaysThreshold,
        BigDecimal latePenaltyPerDay,
        Instant updatedAt
    ) {}

    public record UpdatePayrollSettingsRequest(
        @Min(1) @Max(31) int standardWorkingDays,
        @NotNull @DecimalMin("1.00") BigDecimal standardDailyHours,
        @Min(0) int graceLateMinutes,
        @NotNull @DecimalMin("1.00") BigDecimal overtimeMultiplier,
        @Min(0) int defaultAnnualPaidLeaves,
        boolean deductAbsentAutomatically,
        boolean autoConvertAbsentToPaidLeave,
        @NotNull @DecimalMin("0.0") BigDecimal freeAbsentDaysAllowed,
        @Min(0) int lateDaysThreshold,
        @NotNull @DecimalMin("0.00") BigDecimal latePenaltyPerDay
    ) {}

    public record PayrollPaymentDto(
        Long id,
        BigDecimal amount,
        String method,
        Long expenseId,
        String note,
        String paidByName,
        Instant paidAt
    ) {}

    public record PayrollRecordDto(
        Long id,
        String payslipNo,
        Long userId,
        String employeeCode,
        String fullName,
        String designation,
        String phone,
        String bankAccountNo,
        String ifscCode,
        String upiId,
        String payrollMonth,
        String salaryType,
        BigDecimal basicSalary,
        BigDecimal dailyWage,
        int workingDays,
        BigDecimal presentDays,
        int halfDays,
        BigDecimal paidLeaveDays,
        BigDecimal unpaidLeaveDays,
        BigDecimal absentDays,
        int lateDays,
        BigDecimal payableDays,
        BigDecimal overtimeHours,
        BigDecimal earnedBasic,
        BigDecimal overtimePay,
        BigDecimal bonus,
        BigDecimal incentives,
        BigDecimal leaveDeduction,
        BigDecimal advanceDeduction,
        BigDecimal otherDeductions,
        BigDecimal grossSalary,
        BigDecimal netSalary,
        BigDecimal paidAmount,
        BigDecimal pendingAmount,
        String paymentStatus,
        String lastPaymentMethod,
        Instant lastPaidAt,
        String notes,
        Instant createdAt,
        List<PayrollPaymentDto> payments
    ) {}

    public record UpdatePayrollAdjustmentsRequest(
        @NotNull @DecimalMin("0.00") BigDecimal bonus,
        @NotNull @DecimalMin("0.00") BigDecimal incentives,
        @NotNull @DecimalMin("0.00") BigDecimal leaveDeduction,
        @NotNull @DecimalMin("0.00") BigDecimal advanceDeduction,
        @NotNull @DecimalMin("0.00") BigDecimal otherDeductions,
        @Size(max = 255) String notes
    ) {}

    public record PaySalaryRequest(
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotBlank @Pattern(regexp = "CASH|UPI|CARD") String method,
        @Size(max = 255) String note
    ) {}

    public record EmployeeSalesPerformanceDto(
        Long userId,
        String employeeCode,
        String fullName,
        String role,
        String designation,
        String assignedShift,
        long billCount,
        BigDecimal quantitySold,
        BigDecimal revenue,
        BigDecimal profit,
        BigDecimal presentDays,
        BigDecimal absentDays,
        int lateDays,
        BigDecimal overtimeHours,
        BigDecimal attendancePercentage
    ) {}

    public record HrDashboardResponse(
        LocalDate today,
        String currentMonth,
        int totalEmployees,
        int activeEmployees,
        int presentToday,
        int absentToday,
        int onLeaveToday,
        int lateArrivalsToday,
        int notMarkedToday,
        BigDecimal totalMonthlySalary,
        BigDecimal paidMonthlySalary,
        BigDecimal pendingSalaries,
        BigDecimal overallAttendancePct,
        BigDecimal totalOvertimeHoursMonth,
        BigDecimal totalOvertimePayMonth,
        int pendingLeaveRequests,
        boolean salaryConfidential,
        List<AttendanceDto> todayAttendanceList,
        List<EmployeeSalesPerformanceDto> employeePerformance
    ) {}
}
