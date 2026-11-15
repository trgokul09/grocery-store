package com.grocery;

import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HrAndPayrollIT extends AbstractIT {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP = new ParameterizedTypeReference<>() {};

    @Test
    @SuppressWarnings("unchecked")
    void employeeLifecycleAttendanceLeavePayrollAndRoleSecurity() {
        String owner = ownerToken();
        String manager = createUser(owner, "MANAGER");

        // 1. Create a new STAFF employee via /api/hr/employees as OWNER
        var createEmpRes = call(HttpMethod.POST, "/api/hr/employees", owner, Map.ofEntries(
            Map.entry("username", "ravi_emp"),
            Map.entry("password", "Passw0rd!x"),
            Map.entry("fullName", "Ravi Kumar"),
            Map.entry("role", "STAFF"),
            Map.entry("phone", "9876543210"),
            Map.entry("email", "ravi@store.com"),
            Map.entry("designation", "Senior Cashier"),
            Map.entry("assignedShift", "MORNING"),
            Map.entry("shiftStartTime", "09:00"),
            Map.entry("shiftEndTime", "18:00"),
            Map.entry("salaryType", "MONTHLY"),
            Map.entry("basicSalary", "26000.00"),
            Map.entry("dailyWage", "1000.00"),
            Map.entry("overtimeHourlyRate", "150.00"),
            Map.entry("paidLeaveQuota", 12)
        ));
        assertThat(createEmpRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long raviUserId = ((Number) createEmpRes.getBody().get("userId")).longValue();
        String raviToken = login("ravi_emp", "Passw0rd!x");

        // 2. Verify MANAGER can view employee directory, but confidential salary fields are masked
        var mgrViewRes = call(HttpMethod.GET, "/api/hr/employees/" + raviUserId, manager, null);
        assertThat(mgrViewRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mgrViewRes.getBody().get("salaryConfidential")).isEqualTo(true);
        assertThat(mgrViewRes.getBody().get("basicSalary")).isNull();

        // 3. Authenticated Check-In & Duplicate Check-In Prevention
        var checkInRes = call(HttpMethod.POST, "/api/hr/attendance/check-in", raviToken, Map.of("notes", "Morning arrival"));
        assertThat(checkInRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(checkInRes.getBody().get("status")).isEqualTo("PRESENT");

        var dupCheckIn = call(HttpMethod.POST, "/api/hr/attendance/check-in", raviToken, Map.of());
        assertThat(dupCheckIn.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 4. Authenticated Check-Out & Duplicate Check-Out Prevention
        var checkOutRes = call(HttpMethod.POST, "/api/hr/attendance/check-out", raviToken, Map.of("notes", "Shift done"));
        assertThat(checkOutRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(checkOutRes.getBody().get("checkOut")).isNotNull();

        var dupCheckOut = call(HttpMethod.POST, "/api/hr/attendance/check-out", raviToken, Map.of());
        assertThat(dupCheckOut.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 5. STAFF cannot manually mark attendance
        LocalDate yesterday = LocalDate.now().minusDays(1);
        var staffMarkAttempt = call(HttpMethod.POST, "/api/hr/attendance/mark", raviToken, Map.of(
            "userId", raviUserId, "attendanceDate", yesterday.toString(), "status", "PRESENT"
        ));
        assertThat(staffMarkAttempt.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Manager marks yesterday with 2 hours overtime
        var mgrMarkRes = call(HttpMethod.POST, "/api/hr/attendance/mark", manager, Map.of(
            "userId", raviUserId,
            "attendanceDate", yesterday.toString(),
            "status", "PRESENT",
            "workedHours", "10.00",
            "overtimeHours", "2.00"
        ));
        assertThat(mgrMarkRes.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 6. Leave Application by STAFF & Approval by MANAGER
        LocalDate leaveDate = LocalDate.now().plusDays(1);
        var applyLeaveRes = call(HttpMethod.POST, "/api/hr/leaves", raviToken, Map.of(
            "leaveType", "PAID",
            "startDate", leaveDate.toString(),
            "endDate", leaveDate.toString(),
            "reason", "Family function"
        ));
        assertThat(applyLeaveRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long leaveId = ((Number) applyLeaveRes.getBody().get("id")).longValue();

        var approveLeaveRes = call(HttpMethod.POST, "/api/hr/leaves/" + leaveId + "/review", manager, Map.of(
            "status", "APPROVED",
            "reviewNote", "Approved"
        ));
        assertThat(approveLeaveRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approveLeaveRes.getBody().get("status")).isEqualTo("APPROVED");

        // 7. Issue Salary Advance (Rs 2,000) as OWNER
        var advRes = call(HttpMethod.POST, "/api/hr/advances", owner, Map.of(
            "userId", raviUserId,
            "amount", "2000.00",
            "method", "CASH",
            "reason", "Medical advance"
        ));
        assertThat(advRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // 8. Generate Monthly Payroll & Verify Calculations
        String currentMonth = YearMonth.now().toString();
        ResponseEntity<List<Map<String, Object>>> genRes = rest.exchange(
            "/api/hr/payroll/generate?month=" + currentMonth + "&userId=" + raviUserId,
            HttpMethod.POST,
            new HttpEntity<>(null, auth(owner)),
            LIST_MAP
        );
        assertThat(genRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> pr = genRes.getBody().getFirst();
        Long payrollId = ((Number) pr.get("id")).longValue();

        // Earned basic = 26000.00, Overtime = 2 hrs * 150 = 300.00, Gross = 26300.00, Advance Deduction = 2000.00, Net = 24300.00
        assertThat(new BigDecimal(String.valueOf(pr.get("earnedBasic")))).isEqualByComparingTo("26000.00");
        assertThat(new BigDecimal(String.valueOf(pr.get("overtimePay")))).isEqualByComparingTo("300.00");
        assertThat(new BigDecimal(String.valueOf(pr.get("advanceDeduction")))).isEqualByComparingTo("2000.00");
        assertThat(new BigDecimal(String.valueOf(pr.get("netSalary")))).isEqualByComparingTo("24300.00");

        // 9. Partial Salary Payment (Rs 10,000) followed by Full Remaining Payment (Rs 14,300)
        var partialPay = call(HttpMethod.POST, "/api/hr/payroll/" + payrollId + "/pay", owner, Map.of(
            "amount", "10000.00",
            "method", "UPI",
            "note", "Part payment 1"
        ));
        assertThat(partialPay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(partialPay.getBody().get("paymentStatus")).isEqualTo("PARTIALLY_PAID");
        assertThat(new BigDecimal(String.valueOf(partialPay.getBody().get("pendingAmount")))).isEqualByComparingTo("14300.00");

        var finalPay = call(HttpMethod.POST, "/api/hr/payroll/" + payrollId + "/pay", owner, Map.of(
            "amount", "14300.00",
            "method", "CASH",
            "note", "Final settlement"
        ));
        assertThat(finalPay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(finalPay.getBody().get("paymentStatus")).isEqualTo("PAID");
        assertThat(new BigDecimal(String.valueOf(finalPay.getBody().get("pendingAmount")))).isEqualByComparingTo("0.00");

        // 10. Prevent Duplicate Salary Payment once PAID
        var dupPay = call(HttpMethod.POST, "/api/hr/payroll/" + payrollId + "/pay", owner, Map.of(
            "amount", "100.00",
            "method", "CASH"
        ));
        assertThat(dupPay.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
