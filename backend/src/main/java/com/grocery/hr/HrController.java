package com.grocery.hr;

import com.grocery.common.CurrentUser;
import com.grocery.hr.HrDtos.*;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/hr")
public class HrController {

    private final HrService hr;
    private final CurrentUser cu;

    public HrController(HrService hr, CurrentUser cu) {
        this.hr = hr;
        this.cu = cu;
    }

    // 1. Employee Dashboard
    @GetMapping("/dashboard")
    public HrDashboardResponse dashboard(@RequestParam(required = false) String month) {
        return hr.getDashboard(month);
    }

    // 2. Employee Directory & Profiles
    @GetMapping("/employees")
    public List<EmployeeDto> listEmployees(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String shift) {
        return hr.listEmployees(search, status, shift);
    }

    @GetMapping("/employees/me")
    public EmployeeDto myProfile() {
        return hr.getEmployee(cu.require().getId());
    }

    @GetMapping("/employees/{userId}")
    public EmployeeDto getEmployee(@PathVariable Long userId) {
        return hr.getEmployee(userId);
    }

    @PostMapping("/employees")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public EmployeeDto createEmployee(@Valid @RequestBody CreateEmployeeRequest r) {
        return hr.createEmployee(r);
    }

    @PutMapping("/employees/{userId}")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public EmployeeDto updateEmployee(@PathVariable Long userId, @Valid @RequestBody UpdateEmployeeRequest r) {
        return hr.updateEmployee(userId, r);
    }

    @PatchMapping("/employees/{userId}/active")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public EmployeeDto toggleActive(@PathVariable Long userId, @RequestParam boolean value) {
        return hr.toggleEmployeeStatus(userId, value);
    }

    // 3. Attendance Management
    @PostMapping("/attendance/check-in")
    public AttendanceDto checkIn(@RequestBody(required = false) CheckInOutRequest r) {
        return hr.checkIn(r);
    }

    @PostMapping("/attendance/check-out")
    public AttendanceDto checkOut(@RequestBody(required = false) CheckInOutRequest r) {
        return hr.checkOut(r);
    }

    @PostMapping("/attendance/mark")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public AttendanceDto markAttendance(@Valid @RequestBody MarkAttendanceRequest r) {
        return hr.markAttendance(r);
    }

    @GetMapping("/attendance/daily")
    public List<AttendanceDto> dailyAttendance(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return hr.getAttendanceForDate(date);
    }

    @GetMapping("/attendance/calendar")
    public AttendanceCalendarResponse monthlyCalendar(
            @RequestParam(required = false) String month,
            @RequestParam(required = false) Long userId) {
        return hr.getMonthlyAttendanceCalendar(month, userId);
    }

    // 4. Leave Management
    @GetMapping("/leaves")
    public List<LeaveRequestDto> listLeaves(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String status) {
        return hr.listLeaves(userId, status);
    }

    @GetMapping("/leaves/balances")
    public List<LeaveBalanceDto> leaveBalances() {
        return hr.listLeaveBalances();
    }

    @PostMapping("/leaves")
    @ResponseStatus(HttpStatus.CREATED)
    public LeaveRequestDto applyLeave(@Valid @RequestBody ApplyLeaveRequest r) {
        return hr.applyLeave(r);
    }

    @PostMapping("/leaves/{id}/review")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public LeaveRequestDto reviewLeave(@PathVariable Long id, @Valid @RequestBody ReviewLeaveRequest r) {
        return hr.reviewLeave(id, r);
    }

    // 5. Payroll Rules, Advances, Payroll Calculation & Payslips
    @GetMapping("/payroll/settings")
    public PayrollSettingsDto getPayrollSettings() {
        return hr.getPayrollSettingsDto();
    }

    @PutMapping("/payroll/settings")
    @PreAuthorize("hasRole('OWNER')")
    public PayrollSettingsDto updatePayrollSettings(@Valid @RequestBody UpdatePayrollSettingsRequest r) {
        return hr.updatePayrollSettings(r);
    }

    @GetMapping("/advances")
    public List<SalaryAdvanceDto> listAdvances(@RequestParam(required = false) Long userId) {
        return hr.listAdvances(userId);
    }

    @PostMapping("/advances")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('OWNER')")
    public SalaryAdvanceDto createAdvance(@Valid @RequestBody CreateAdvanceRequest r) {
        return hr.createAdvance(r);
    }

    @GetMapping("/payroll")
    public List<PayrollRecordDto> listPayroll(
            @RequestParam(required = false) String month,
            @RequestParam(required = false) Long userId) {
        return hr.listPayrollRecords(month, userId);
    }

    @GetMapping("/payroll/{id}")
    public PayrollRecordDto getPayslip(@PathVariable Long id) {
        return hr.getPayslip(id);
    }

    @PostMapping("/payroll/generate")
    @PreAuthorize("hasRole('OWNER')")
    public List<PayrollRecordDto> generatePayroll(
            @RequestParam(required = false) String month,
            @RequestParam(required = false) Long userId) {
        return hr.generateMonthlyPayroll(month, userId);
    }

    @PutMapping("/payroll/{id}/adjustments")
    @PreAuthorize("hasRole('OWNER')")
    public PayrollRecordDto updatePayrollAdjustments(
            @PathVariable Long id,
            @Valid @RequestBody UpdatePayrollAdjustmentsRequest r) {
        return hr.updatePayrollAdjustments(id, r);
    }

    @PostMapping("/payroll/{id}/pay")
    @PreAuthorize("hasRole('OWNER')")
    public PayrollRecordDto paySalary(@PathVariable Long id, @Valid @RequestBody PaySalaryRequest r) {
        return hr.paySalary(id, r);
    }
}
