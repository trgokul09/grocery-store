package com.grocery.hr;

import com.grocery.audit.AuditService;
import com.grocery.common.CurrentUser;
import com.grocery.common.Exceptions.BusinessException;
import com.grocery.common.Exceptions.NotFoundException;
import com.grocery.common.PaymentMethod;
import com.grocery.common.StoreClock;
import org.springframework.security.access.AccessDeniedException;
import com.grocery.expense.Expense;
import com.grocery.expense.ExpenseCategory;
import com.grocery.expense.ExpenseRepository;
import com.grocery.hr.HrDtos.*;
import com.grocery.shift.ShiftRepository;
import com.grocery.shift.ShiftStatus;
import com.grocery.user.Role;
import com.grocery.user.RoleRepository;
import com.grocery.user.User;
import com.grocery.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Types;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class HrService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal HALF = new BigDecimal("0.5");

    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final EmployeeProfileRepository profiles;
    private final EmployeeAttendanceRepository attendanceRepo;
    private final LeaveRequestRepository leaveRepo;
    private final SalaryAdvanceRepository advanceRepo;
    private final PayrollSettingsRepository settingsRepo;
    private final PayrollRecordRepository payrollRepo;
    private final PayrollPaymentRepository paymentRepo;
    private final ExpenseRepository expenses;
    private final ShiftRepository shifts;
    private final AuditService audit;
    private final CurrentUser cu;
    private final StoreClock clock;
    private final EntityManager em;
    private final NamedParameterJdbcTemplate jdbc;

    public HrService(
            UserRepository users,
            RoleRepository roles,
            PasswordEncoder passwordEncoder,
            EmployeeProfileRepository profiles,
            EmployeeAttendanceRepository attendanceRepo,
            LeaveRequestRepository leaveRepo,
            SalaryAdvanceRepository advanceRepo,
            PayrollSettingsRepository settingsRepo,
            PayrollRecordRepository payrollRepo,
            PayrollPaymentRepository paymentRepo,
            ExpenseRepository expenses,
            ShiftRepository shifts,
            AuditService audit,
            CurrentUser cu,
            StoreClock clock,
            EntityManager em,
            NamedParameterJdbcTemplate jdbc) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.profiles = profiles;
        this.attendanceRepo = attendanceRepo;
        this.leaveRepo = leaveRepo;
        this.advanceRepo = advanceRepo;
        this.settingsRepo = settingsRepo;
        this.payrollRepo = payrollRepo;
        this.paymentRepo = paymentRepo;
        this.expenses = expenses;
        this.shifts = shifts;
        this.audit = audit;
        this.cu = cu;
        this.clock = clock;
        this.em = em;
        this.jdbc = jdbc;
    }

    // =========================================================================
    // 1. PROFILE SYNCHRONIZATION & EMPLOYEE MANAGEMENT
    // =========================================================================

    @Transactional
    public void ensureProfilesSynced() {
        List<User> allUsers = users.findAll();
        PayrollSettings settings = getOrCreateSettings();
        for (User u : allUsers) {
            if (profiles.findByUserId(u.getId()).isEmpty()) {
                EmployeeProfile ep = new EmployeeProfile();
                ep.setUser(u);
                ep.setEmployeeCode(nextEmployeeCode());
                String roleName = u.getRole().getName();
                ep.setDesignation(switch (roleName) {
                    case "OWNER" -> "Store Owner";
                    case "MANAGER" -> "Store Manager";
                    default -> "Cashier / Sales Associate";
                });
                ep.setBasicSalary(switch (roleName) {
                    case "OWNER" -> new BigDecimal("50000.00");
                    case "MANAGER" -> new BigDecimal("28000.00");
                    default -> new BigDecimal("15000.00");
                });
                ep.setDailyWage(ep.getBasicSalary().divide(
                    BigDecimal.valueOf(Math.max(1, settings.getStandardWorkingDays())), 2, RoundingMode.HALF_UP
                ));
                ep.setOvertimeHourlyRate(new BigDecimal("100.00"));
                ep.setPaidLeaveQuota(settings.getDefaultAnnualPaidLeaves());
                ep.setJoiningDate(clock.toLocal(u.getCreatedAt()));
                ep.setEmploymentStatus(u.isActive() ? "ACTIVE" : "INACTIVE");
                profiles.save(ep);
            }
        }
    }

    private String nextEmployeeCode() {
        Number n = (Number) em.createNativeQuery("SELECT nextval('emp_code_seq')").getSingleResult();
        return String.format("EMP-%04d", n.longValue());
    }

    private String nextPayslipNo(String month) {
        Number n = (Number) em.createNativeQuery("SELECT nextval('payslip_seq')").getSingleResult();
        return "PAY-" + month.replace("-", "") + "-" + String.format("%04d", n.longValue());
    }

    @Transactional
    public List<EmployeeDto> listEmployees(String search, String status, String shift) {
        ensureProfilesSynced();
        User caller = cu.require();
        if (cu.isStaff()) {
            return List.of(toEmployeeDto(profiles.findByUserId(caller.getId()).orElseThrow(), caller));
        }

        String q = search == null ? "" : search.trim().toLowerCase();
        String st = status == null ? "" : status.trim().toUpperCase();
        String sh = shift == null ? "" : shift.trim().toUpperCase();

        return profiles.findAll().stream()
            .sorted(Comparator.comparing(ep -> ep.getUser().getId()))
            .filter(ep -> q.isEmpty()
                || ep.getUser().getFullName().toLowerCase().contains(q)
                || ep.getUser().getUsername().toLowerCase().contains(q)
                || ep.getEmployeeCode().toLowerCase().contains(q)
                || (ep.getPhone() != null && ep.getPhone().toLowerCase().contains(q))
                || (ep.getDesignation() != null && ep.getDesignation().toLowerCase().contains(q)))
            .filter(ep -> st.isEmpty() || "ALL".equals(st) || st.equalsIgnoreCase(ep.getEmploymentStatus()))
            .filter(ep -> sh.isEmpty() || "ALL".equals(sh) || sh.equalsIgnoreCase(ep.getAssignedShift()))
            .map(ep -> toEmployeeDto(ep, caller))
            .toList();
    }

    @Transactional
    public EmployeeDto getEmployee(Long userId) {
        ensureProfilesSynced();
        User caller = cu.require();
        if (cu.isStaff() && !caller.getId().equals(userId)) {
            throw new AccessDeniedException("Staff can only view their own employee profile");
        }
        EmployeeProfile ep = profiles.findByUserId(userId)
            .orElseThrow(() -> new NotFoundException("Employee profile not found"));
        return toEmployeeDto(ep, caller);
    }

    @Transactional
    public EmployeeDto createEmployee(CreateEmployeeRequest r) {
        ensureProfilesSynced();
        User caller = cu.require();
        if (cu.isStaff()) {
            throw new AccessDeniedException("Not authorized to create employees");
        }
        if (!cu.isOwner() && !"STAFF".equals(r.role())) {
            throw new AccessDeniedException("Managers can only create STAFF employee accounts");
        }
        if (users.findByUsername(r.username().trim()).isPresent()) {
            throw new BusinessException("Username already taken: " + r.username());
        }

        Role role = roles.findByName(r.role()).orElseThrow(() -> new BusinessException("Invalid role: " + r.role()));
        User newUser = users.save(new User(
            r.username().trim(),
            passwordEncoder.encode(r.password()),
            r.fullName().trim(),
            role
        ));

        PayrollSettings settings = getOrCreateSettings();
        EmployeeProfile ep = new EmployeeProfile();
        ep.setUser(newUser);

        String code = (r.employeeCode() != null && !r.employeeCode().isBlank())
            ? r.employeeCode().trim().toUpperCase()
            : nextEmployeeCode();
        if (profiles.findByEmployeeCodeIgnoreCase(code).isPresent()) {
            throw new BusinessException("Employee ID already exists: " + code);
        }
        ep.setEmployeeCode(code);
        ep.setPhone(trimOrNull(r.phone()));
        ep.setEmail(trimOrNull(r.email()));
        ep.setDesignation(r.designation() == null || r.designation().isBlank() ? "Store Associate" : r.designation().trim());
        ep.setJoiningDate(r.joiningDate() == null ? clock.today() : r.joiningDate());
        ep.setEmploymentStatus(r.employmentStatus() == null || r.employmentStatus().isBlank() ? "ACTIVE" : r.employmentStatus().trim().toUpperCase());
        ep.setAssignedShift(r.assignedShift() == null || r.assignedShift().isBlank() ? "GENERAL" : r.assignedShift().trim().toUpperCase());
        ep.setShiftStartTime(r.shiftStartTime() == null || r.shiftStartTime().isBlank() ? "09:00" : r.shiftStartTime().trim());
        ep.setShiftEndTime(r.shiftEndTime() == null || r.shiftEndTime().isBlank() ? "18:00" : r.shiftEndTime().trim());
        ep.setAddress(trimOrNull(r.address()));
        ep.setPaidLeaveQuota(r.paidLeaveQuota() != null ? r.paidLeaveQuota() : settings.getDefaultAnnualPaidLeaves());

        if (cu.isOwner()) {
            ep.setSalaryType(r.salaryType() == null || r.salaryType().isBlank() ? "MONTHLY" : r.salaryType().trim().toUpperCase());
            BigDecimal basic = r.basicSalary() != null ? scale2(r.basicSalary()) : new BigDecimal("15000.00");
            ep.setBasicSalary(basic);
            BigDecimal daily = r.dailyWage() != null && r.dailyWage().signum() > 0
                ? scale2(r.dailyWage())
                : basic.divide(BigDecimal.valueOf(Math.max(1, settings.getStandardWorkingDays())), 2, RoundingMode.HALF_UP);
            ep.setDailyWage(daily);
            ep.setOvertimeHourlyRate(r.overtimeHourlyRate() != null ? scale2(r.overtimeHourlyRate()) : new BigDecimal("100.00"));
            ep.setBankAccountNo(trimOrNull(r.bankAccountNo()));
            ep.setIfscCode(trimOrNull(r.ifscCode()));
            ep.setUpiId(trimOrNull(r.upiId()));
        }

        newUser.setActive(!"INACTIVE".equalsIgnoreCase(ep.getEmploymentStatus()));
        profiles.save(ep);

        audit.log("CREATE_EMPLOYEE", "EMPLOYEE", newUser.getId(), null,
            Map.of("employeeCode", ep.getEmployeeCode(), "username", newUser.getUsername(), "role", r.role(), "designation", ep.getDesignation()));
        return toEmployeeDto(ep, caller);
    }

    @Transactional
    public EmployeeDto updateEmployee(Long userId, UpdateEmployeeRequest r) {
        ensureProfilesSynced();
        User caller = cu.require();
        if (cu.isStaff()) {
            throw new AccessDeniedException("Not authorized to edit employee records");
        }
        User targetUser = users.findById(userId).orElseThrow(() -> new NotFoundException("Employee not found"));
        EmployeeProfile ep = profiles.findByUserId(userId).orElseThrow(() -> new NotFoundException("Employee profile not found"));

        if (!cu.isOwner() && "OWNER".equals(targetUser.getRole().getName())) {
            throw new AccessDeniedException("Managers cannot modify the Owner's profile");
        }

        targetUser.setFullName(r.fullName().trim());
        if (cu.isOwner() && r.role() != null && !r.role().isBlank()) {
            if (targetUser.getId().equals(caller.getId()) && !r.role().equals(targetUser.getRole().getName())) {
                throw new BusinessException("You cannot change your own role");
            }
            Role role = roles.findByName(r.role()).orElseThrow(() -> new BusinessException("Invalid role"));
            targetUser.setRole(role);
        }

        ep.setPhone(trimOrNull(r.phone()));
        ep.setEmail(trimOrNull(r.email()));
        if (r.designation() != null && !r.designation().isBlank()) ep.setDesignation(r.designation().trim());
        if (r.joiningDate() != null) ep.setJoiningDate(r.joiningDate());
        if (r.employmentStatus() != null && !r.employmentStatus().isBlank()) {
            String status = r.employmentStatus().trim().toUpperCase();
            if (targetUser.getId().equals(caller.getId()) && "INACTIVE".equals(status)) {
                throw new BusinessException("You cannot deactivate yourself");
            }
            ep.setEmploymentStatus(status);
            targetUser.setActive(!"INACTIVE".equals(status));
        }
        if (r.assignedShift() != null && !r.assignedShift().isBlank()) ep.setAssignedShift(r.assignedShift().trim().toUpperCase());
        if (r.shiftStartTime() != null && !r.shiftStartTime().isBlank()) ep.setShiftStartTime(r.shiftStartTime().trim());
        if (r.shiftEndTime() != null && !r.shiftEndTime().isBlank()) ep.setShiftEndTime(r.shiftEndTime().trim());
        ep.setAddress(trimOrNull(r.address()));
        if (r.paidLeaveQuota() != null && r.paidLeaveQuota() >= 0) ep.setPaidLeaveQuota(r.paidLeaveQuota());

        // Only OWNER can modify salary and banking fields
        if (cu.isOwner()) {
            PayrollSettings settings = getOrCreateSettings();
            if (r.salaryType() != null && !r.salaryType().isBlank()) ep.setSalaryType(r.salaryType().trim().toUpperCase());
            if (r.basicSalary() != null) {
                ep.setBasicSalary(scale2(r.basicSalary()));
                if (r.dailyWage() == null || r.dailyWage().signum() == 0) {
                    ep.setDailyWage(ep.getBasicSalary().divide(
                        BigDecimal.valueOf(Math.max(1, settings.getStandardWorkingDays())), 2, RoundingMode.HALF_UP
                    ));
                }
            }
            if (r.dailyWage() != null && r.dailyWage().signum() > 0) ep.setDailyWage(scale2(r.dailyWage()));
            if (r.overtimeHourlyRate() != null) ep.setOvertimeHourlyRate(scale2(r.overtimeHourlyRate()));
            ep.setBankAccountNo(trimOrNull(r.bankAccountNo()));
            ep.setIfscCode(trimOrNull(r.ifscCode()));
            ep.setUpiId(trimOrNull(r.upiId()));
        }

        ep.setUpdatedAt(Instant.now());
        audit.log("UPDATE_EMPLOYEE", "EMPLOYEE", userId, null,
            Map.of("employeeCode", ep.getEmployeeCode(), "fullName", targetUser.getFullName(), "status", ep.getEmploymentStatus()));
        return toEmployeeDto(ep, caller);
    }

    @Transactional
    public EmployeeDto toggleEmployeeStatus(Long userId, boolean active) {
        ensureProfilesSynced();
        User caller = cu.require();
        if (cu.isStaff()) throw new AccessDeniedException("Not authorized");
        User u = users.findById(userId).orElseThrow(() -> new NotFoundException("Employee not found"));
        if (u.getId().equals(caller.getId()) && !active) {
            throw new BusinessException("You cannot deactivate yourself");
        }
        if (!cu.isOwner() && "OWNER".equals(u.getRole().getName())) {
            throw new AccessDeniedException("Managers cannot deactivate the Owner");
        }
        EmployeeProfile ep = profiles.findByUserId(userId).orElseThrow();
        u.setActive(active);
        ep.setEmploymentStatus(active ? "ACTIVE" : "INACTIVE");
        ep.setUpdatedAt(Instant.now());
        audit.log(active ? "ACTIVATE_EMPLOYEE" : "DEACTIVATE_EMPLOYEE", "EMPLOYEE", userId, null, Map.of("active", active));
        return toEmployeeDto(ep, caller);
    }

    private EmployeeDto toEmployeeDto(EmployeeProfile ep, User caller) {
        User u = ep.getUser();
        boolean canSeeSalary = caller.getRole().getName().equals("OWNER") || caller.getId().equals(u.getId());
        LocalDate today = clock.today();
        EmployeeAttendance todayAtt = attendanceRepo.findByUserIdAndAttendanceDate(u.getId(), today).orElse(null);
        LeaveBalanceDto lb = computeLeaveBalance(ep, today.getYear());

        return new EmployeeDto(
            u.getId(),
            u.getUsername(),
            u.getFullName(),
            u.getRole().getName(),
            u.isActive(),
            ep.getId(),
            ep.getEmployeeCode(),
            ep.getPhone(),
            ep.getEmail(),
            ep.getDesignation(),
            ep.getJoiningDate(),
            ep.getEmploymentStatus(),
            ep.getAssignedShift(),
            ep.getShiftStartTime(),
            ep.getShiftEndTime(),
            ep.getAddress(),
            canSeeSalary ? ep.getSalaryType() : null,
            canSeeSalary ? ep.getBasicSalary() : null,
            canSeeSalary ? ep.getDailyWage() : null,
            canSeeSalary ? ep.getOvertimeHourlyRate() : null,
            ep.getPaidLeaveQuota(),
            canSeeSalary ? ep.getBankAccountNo() : null,
            canSeeSalary ? ep.getIfscCode() : null,
            canSeeSalary ? ep.getUpiId() : null,
            !canSeeSalary,
            todayAtt != null ? todayAtt.getStatus() : "NOT_MARKED",
            todayAtt != null ? todayAtt.getCheckIn() : null,
            todayAtt != null ? todayAtt.getCheckOut() : null,
            todayAtt != null && todayAtt.isLate(),
            lb.paidLeavesUsed(),
            lb.paidLeavesRemaining(),
            lb.unpaidLeavesUsed()
        );
    }

    // =========================================================================
    // 2. ATTENDANCE MANAGEMENT (CHECK-IN, CHECK-OUT, BULK MARK, CALENDAR)
    // =========================================================================

    @Transactional
    public AttendanceDto checkIn(CheckInOutRequest req) {
        ensureProfilesSynced();
        User user = cu.require();
        if (!user.isActive()) throw new BusinessException("Employee account is inactive");

        EmployeeProfile ep = profiles.findByUserId(user.getId()).orElseThrow();
        LocalDate today = clock.today();
        EmployeeAttendance existing = attendanceRepo.findByUserIdAndAttendanceDate(user.getId(), today).orElse(null);

        if (existing != null && existing.getCheckIn() != null) {
            throw new BusinessException("You have already checked in today");
        }
        if (existing != null && "LEAVE".equals(existing.getStatus())) {
            throw new BusinessException("You are marked on approved leave today");
        }

        PayrollSettings settings = getOrCreateSettings();
        Instant now = Instant.now();
        LocalTime nowTime = LocalDateTime.ofInstant(now, clock.zone()).toLocalTime();
        LocalTime shiftStart = parseLocalTime(ep.getShiftStartTime(), LocalTime.of(9, 0));

        boolean isLate = false;
        int lateMins = 0;
        if (nowTime.isAfter(shiftStart.plusMinutes(settings.getGraceLateMinutes()))) {
            isLate = true;
            lateMins = (int) Math.max(0, ChronoUnit.MINUTES.between(shiftStart, nowTime));
        }

        EmployeeAttendance att = existing != null ? existing : new EmployeeAttendance();
        att.setUser(user);
        att.setAttendanceDate(today);
        att.setStatus("PRESENT");
        att.setLeaveType(null);
        att.setCheckIn(now);
        att.setLate(isLate);
        att.setLateMinutes(lateMins);
        if (req != null && req.notes() != null && !req.notes().isBlank()) {
            att.setNotes(req.notes().trim());
        }
        att.setRecordedBy(user.getId());
        att.setUpdatedAt(now);

        EmployeeAttendance saved = attendanceRepo.save(att);
        audit.log("ATTENDANCE_CHECK_IN", "ATTENDANCE", saved.getId(), null,
            Map.of("userId", user.getId(), "date", today.toString(), "late", isLate, "lateMinutes", lateMins));
        return toAttendanceDto(saved, ep);
    }

    @Transactional
    public AttendanceDto checkOut(CheckInOutRequest req) {
        ensureProfilesSynced();
        User user = cu.require();
        EmployeeProfile ep = profiles.findByUserId(user.getId()).orElseThrow();
        LocalDate today = clock.today();

        EmployeeAttendance att = attendanceRepo.findByUserIdAndAttendanceDate(user.getId(), today)
            .orElseThrow(() -> new BusinessException("You must check in before checking out"));

        if (att.getCheckIn() == null) {
            throw new BusinessException("You must check in before checking out");
        }
        if (att.getCheckOut() != null) {
            throw new BusinessException("You have already checked out today");
        }

        Instant now = Instant.now();
        if (now.isBefore(att.getCheckIn())) {
            throw new BusinessException("Check-out time cannot be earlier than check-in time");
        }

        PayrollSettings settings = getOrCreateSettings();
        long workedMins = Math.max(1L, Duration.between(att.getCheckIn(), now).toMinutes());
        BigDecimal workedHours = BigDecimal.valueOf(workedMins)
            .divide(new BigDecimal("60"), 2, RoundingMode.HALF_UP);

        BigDecimal stdHours = settings.getStandardDailyHours();
        BigDecimal overtime = workedHours.compareTo(stdHours) > 0
            ? workedHours.subtract(stdHours).setScale(2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        att.setCheckOut(now);
        att.setWorkedHours(workedHours);
        att.setOvertimeHours(overtime);
        if (req != null && req.notes() != null && !req.notes().isBlank()) {
            att.setNotes(req.notes().trim());
        }
        att.setUpdatedAt(now);

        EmployeeAttendance saved = attendanceRepo.save(att);
        audit.log("ATTENDANCE_CHECK_OUT", "ATTENDANCE", saved.getId(), null,
            Map.of("userId", user.getId(), "date", today.toString(), "workedHours", workedHours, "overtimeHours", overtime));
        return toAttendanceDto(saved, ep);
    }

    @Transactional
    public AttendanceDto markAttendance(MarkAttendanceRequest r) {
        ensureProfilesSynced();
        User caller = cu.require();
        if (cu.isStaff()) {
            throw new AccessDeniedException("Only Owner or Manager can manually record or modify attendance");
        }

        User target = users.findById(r.userId()).orElseThrow(() -> new NotFoundException("Employee not found"));
        EmployeeProfile ep = profiles.findByUserId(target.getId()).orElseThrow();
        PayrollSettings settings = getOrCreateSettings();

        EmployeeAttendance att = attendanceRepo.findByUserIdAndAttendanceDate(target.getId(), r.attendanceDate())
            .orElseGet(EmployeeAttendance::new);

        att.setUser(target);
        att.setAttendanceDate(r.attendanceDate());
        att.setStatus(r.status().toUpperCase());

        if ("LEAVE".equals(att.getStatus())) {
            att.setLeaveType(r.leaveType() != null && !r.leaveType().isBlank() ? r.leaveType().toUpperCase() : "PAID");
        } else {
            att.setLeaveType(null);
        }

        Instant checkInInst = parseDateAndTime(r.attendanceDate(), r.checkInTime());
        Instant checkOutInst = parseDateAndTime(r.attendanceDate(), r.checkOutTime());
        if (checkInInst != null && checkOutInst != null && checkOutInst.isBefore(checkInInst)) {
            checkOutInst = checkOutInst.plus(1, ChronoUnit.DAYS);
        }
        att.setCheckIn(checkInInst);
        att.setCheckOut(checkOutInst);

        // Late calculation
        if (r.late() != null) {
            att.setLate(r.late());
            att.setLateMinutes(r.lateMinutes() != null ? Math.max(0, r.lateMinutes()) : 0);
        } else if (checkInInst != null && ("PRESENT".equals(att.getStatus()) || "HALF_DAY".equals(att.getStatus()))) {
            LocalTime inLocal = LocalDateTime.ofInstant(checkInInst, clock.zone()).toLocalTime();
            LocalTime shiftStart = parseLocalTime(ep.getShiftStartTime(), LocalTime.of(9, 0));
            if (inLocal.isAfter(shiftStart.plusMinutes(settings.getGraceLateMinutes()))) {
                att.setLate(true);
                att.setLateMinutes((int) ChronoUnit.MINUTES.between(shiftStart, inLocal));
            } else {
                att.setLate(false);
                att.setLateMinutes(0);
            }
        } else {
            att.setLate(false);
            att.setLateMinutes(0);
        }

        // Worked hours & overtime calculation
        BigDecimal worked = r.workedHours();
        if (worked == null) {
            if (checkInInst != null && checkOutInst != null) {
                long mins = Math.max(0L, Duration.between(checkInInst, checkOutInst).toMinutes());
                worked = BigDecimal.valueOf(mins).divide(new BigDecimal("60"), 2, RoundingMode.HALF_UP);
            } else if ("PRESENT".equals(att.getStatus())) {
                worked = settings.getStandardDailyHours();
            } else if ("HALF_DAY".equals(att.getStatus())) {
                worked = settings.getStandardDailyHours().divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
            } else {
                worked = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            }
        }
        att.setWorkedHours(scale2(worked));

        BigDecimal overtime = r.overtimeHours();
        if (overtime == null) {
            BigDecimal std = settings.getStandardDailyHours();
            overtime = worked.compareTo(std) > 0 ? worked.subtract(std) : BigDecimal.ZERO;
        }
        att.setOvertimeHours(scale2(overtime));

        att.setNotes(trimOrNull(r.notes()));
        att.setRecordedBy(caller.getId());
        att.setUpdatedAt(Instant.now());

        EmployeeAttendance saved = attendanceRepo.save(att);
        audit.log("MARK_ATTENDANCE", "ATTENDANCE", saved.getId(), null,
            Map.of("userId", target.getId(), "date", r.attendanceDate().toString(), "status", att.getStatus()));
        return toAttendanceDto(saved, ep);
    }

    @Transactional(readOnly = true)
    public List<AttendanceDto> getAttendanceForDate(LocalDate date) {
        LocalDate targetDate = date == null ? clock.today() : date;
        User caller = cu.require();

        Map<Long, EmployeeAttendance> byUser = new HashMap<>();
        for (EmployeeAttendance a : attendanceRepo.findByAttendanceDateOrderByUserIdAsc(targetDate)) {
            byUser.put(a.getUser().getId(), a);
        }

        return profiles.findAll().stream()
            .filter(ep -> !cu.isStaff() || ep.getUser().getId().equals(caller.getId()))
            .sorted(Comparator.comparing(ep -> ep.getUser().getId()))
            .map(ep -> {
                EmployeeAttendance a = byUser.get(ep.getUser().getId());
                if (a != null) return toAttendanceDto(a, ep);
                return new AttendanceDto(
                    null, ep.getUser().getId(), ep.getEmployeeCode(), ep.getUser().getFullName(),
                    ep.getDesignation(), ep.getAssignedShift(), ep.getShiftStartTime(), ep.getShiftEndTime(),
                    targetDate, "NOT_MARKED", null, null, null, false, 0,
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), null
                );
            })
            .toList();
    }

    @Transactional
    public AttendanceCalendarResponse getMonthlyAttendanceCalendar(String month, Long filterUserId) {
        ensureProfilesSynced();
        User caller = cu.require();
        Long effectiveUserId = cu.isStaff() ? caller.getId() : filterUserId;

        YearMonth ym = parseYearMonth(month);
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.atEndOfMonth();
        LocalDate today = clock.today();
        LocalDate effectiveEndForElapsed = to.isAfter(today) ? today : to;

        List<String> dates = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            dates.add(d.toString());
        }

        List<EmployeeAttendance> allRecords = effectiveUserId != null
            ? attendanceRepo.findByUserIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(effectiveUserId, from, to)
            : attendanceRepo.findByAttendanceDateBetweenOrderByAttendanceDateDescUserIdAsc(from, to);

        Map<Long, List<EmployeeAttendance>> byUser = new HashMap<>();
        for (EmployeeAttendance a : allRecords) {
            byUser.computeIfAbsent(a.getUser().getId(), k -> new ArrayList<>()).add(a);
        }

        PayrollSettings settings = getOrCreateSettings();
        int elapsedDays = effectiveEndForElapsed.isBefore(from)
            ? 0
            : (int) (ChronoUnit.DAYS.between(from, effectiveEndForElapsed) + 1);
        int denomDays = Math.max(1, Math.min(settings.getStandardWorkingDays(), Math.max(1, elapsedDays)));

        List<EmployeeProfile> targetProfiles = profiles.findAll().stream()
            .filter(ep -> effectiveUserId == null || ep.getUser().getId().equals(effectiveUserId))
            .sorted(Comparator.comparing(ep -> ep.getUser().getId()))
            .toList();

        List<EmployeeAttendanceSummaryDto> summaries = new ArrayList<>();
        for (EmployeeProfile ep : targetProfiles) {
            List<EmployeeAttendance> empRecords = byUser.getOrDefault(ep.getUser().getId(), List.of());
            BigDecimal present = BigDecimal.ZERO;
            int halfDays = 0;
            BigDecimal absent = BigDecimal.ZERO;
            BigDecimal paidLeave = BigDecimal.ZERO;
            BigDecimal unpaidLeave = BigDecimal.ZERO;
            int weekOff = 0;
            int lateDays = 0;
            BigDecimal workedHours = BigDecimal.ZERO;
            BigDecimal overtimeHours = BigDecimal.ZERO;
            Map<String, String> dailyMap = new LinkedHashMap<>();

            for (EmployeeAttendance a : empRecords) {
                String dStr = a.getAttendanceDate().toString();
                String st = a.getStatus();
                dailyMap.put(dStr, st);
                if (a.isLate()) lateDays++;
                workedHours = workedHours.add(a.getWorkedHours() == null ? BigDecimal.ZERO : a.getWorkedHours());
                overtimeHours = overtimeHours.add(a.getOvertimeHours() == null ? BigDecimal.ZERO : a.getOvertimeHours());

                switch (st) {
                    case "PRESENT" -> present = present.add(BigDecimal.ONE);
                    case "HALF_DAY" -> {
                        halfDays++;
                        present = present.add(HALF);
                        absent = absent.add(HALF);
                    }
                    case "ABSENT" -> absent = absent.add(BigDecimal.ONE);
                    case "LEAVE" -> {
                        if ("UNPAID".equalsIgnoreCase(a.getLeaveType())) {
                            unpaidLeave = unpaidLeave.add(BigDecimal.ONE);
                        } else {
                            paidLeave = paidLeave.add(BigDecimal.ONE);
                        }
                    }
                    case "WEEK_OFF" -> weekOff++;
                }
            }

            int markedDays = empRecords.size();
            int effectiveDenom = Math.max(markedDays, denomDays);
            BigDecimal effectivePresent = present.add(paidLeave).add(BigDecimal.valueOf(weekOff));
            BigDecimal attPct = effectiveDenom > 0
                ? effectivePresent.multiply(HUNDRED).divide(BigDecimal.valueOf(effectiveDenom), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            if (attPct.compareTo(HUNDRED) > 0) attPct = HUNDRED.setScale(2, RoundingMode.HALF_UP);

            summaries.add(new EmployeeAttendanceSummaryDto(
                ep.getUser().getId(),
                ep.getEmployeeCode(),
                ep.getUser().getFullName(),
                ep.getDesignation(),
                ep.getAssignedShift(),
                ym.lengthOfMonth(),
                settings.getStandardWorkingDays(),
                present.setScale(1, RoundingMode.HALF_UP),
                halfDays,
                absent.setScale(1, RoundingMode.HALF_UP),
                paidLeave.setScale(1, RoundingMode.HALF_UP),
                unpaidLeave.setScale(1, RoundingMode.HALF_UP),
                weekOff,
                lateDays,
                scale2(workedHours),
                scale2(overtimeHours),
                attPct,
                dailyMap
            ));
        }

        Map<Long, EmployeeProfile> profileMap = new HashMap<>();
        targetProfiles.forEach(ep -> profileMap.put(ep.getUser().getId(), ep));
        List<AttendanceDto> recordDtos = allRecords.stream()
            .map(a -> toAttendanceDto(a, profileMap.get(a.getUser().getId())))
            .toList();

        return new AttendanceCalendarResponse(ym.toString(), from, to, dates, summaries, recordDtos);
    }

    private AttendanceDto toAttendanceDto(EmployeeAttendance a, EmployeeProfile ep) {
        User u = a.getUser();
        return new AttendanceDto(
            a.getId(),
            u.getId(),
            ep != null ? ep.getEmployeeCode() : "EMP-" + u.getId(),
            u.getFullName(),
            ep != null ? ep.getDesignation() : u.getRole().getName(),
            ep != null ? ep.getAssignedShift() : "GENERAL",
            ep != null ? ep.getShiftStartTime() : "09:00",
            ep != null ? ep.getShiftEndTime() : "18:00",
            a.getAttendanceDate(),
            a.getStatus(),
            a.getLeaveType(),
            a.getCheckIn(),
            a.getCheckOut(),
            a.isLate(),
            a.getLateMinutes(),
            scale2(a.getWorkedHours()),
            scale2(a.getOvertimeHours()),
            a.getNotes()
        );
    }

    // =========================================================================
    // 3. LEAVE MANAGEMENT
    // =========================================================================

    @Transactional
    public LeaveRequestDto applyLeave(ApplyLeaveRequest r) {
        ensureProfilesSynced();
        User caller = cu.require();
        Long targetUserId = (r.userId() != null && !cu.isStaff()) ? r.userId() : caller.getId();

        User targetUser = users.findById(targetUserId).orElseThrow(() -> new NotFoundException("Employee not found"));
        EmployeeProfile ep = profiles.findByUserId(targetUserId).orElseThrow();

        if (r.endDate().isBefore(r.startDate())) {
            throw new BusinessException("Leave end date cannot be before start date");
        }

        boolean halfDay = Boolean.TRUE.equals(r.halfDay()) && r.startDate().equals(r.endDate());
        long spanDays = ChronoUnit.DAYS.between(r.startDate(), r.endDate()) + 1;
        BigDecimal totalDays = halfDay
            ? HALF
            : BigDecimal.valueOf(spanDays).setScale(1, RoundingMode.HALF_UP);

        String leaveType = r.leaveType().trim().toUpperCase();
        boolean isPaid = !"UNPAID".equals(leaveType) && (r.paid() == null || r.paid());

        if (isPaid) {
            LeaveBalanceDto bal = computeLeaveBalance(ep, r.startDate().getYear());
            if (bal.paidLeavesRemaining().compareTo(totalDays) < 0) {
                throw new BusinessException("Insufficient paid leave balance (" + bal.paidLeavesRemaining()
                    + " days remaining). Apply as UNPAID leave or reduce days.");
            }
        }

        LeaveRequest lr = new LeaveRequest();
        lr.setUser(targetUser);
        lr.setLeaveType(leaveType);
        lr.setPaid(isPaid);
        lr.setStartDate(r.startDate());
        lr.setEndDate(r.endDate());
        lr.setTotalDays(totalDays);
        lr.setReason(r.reason().trim());
        lr.setStatus("PENDING");

        LeaveRequest saved = leaveRepo.save(lr);
        audit.log("APPLY_LEAVE", "LEAVE", saved.getId(), null,
            Map.of("userId", targetUserId, "type", leaveType, "paid", isPaid, "days", totalDays));
        return toLeaveDto(saved, ep, null);
    }

    @Transactional
    public LeaveRequestDto reviewLeave(Long leaveId, ReviewLeaveRequest r) {
        ensureProfilesSynced();
        User reviewer = cu.require();
        if (cu.isStaff()) {
            throw new AccessDeniedException("Only Owner or Manager can approve or reject leave applications");
        }

        LeaveRequest lr = leaveRepo.findById(leaveId).orElseThrow(() -> new NotFoundException("Leave request not found"));
        if (!"PENDING".equals(lr.getStatus())) {
            throw new BusinessException("Leave request has already been " + lr.getStatus().toLowerCase());
        }
        if (!cu.isOwner() && lr.getUser().getId().equals(reviewer.getId())) {
            throw new AccessDeniedException("Managers cannot approve their own leave requests");
        }

        String decision = r.status().trim().toUpperCase();
        lr.setStatus(decision);
        lr.setReviewedBy(reviewer.getId());
        lr.setReviewedAt(Instant.now());
        lr.setReviewNote(trimOrNull(r.reviewNote()));
        LeaveRequest saved = leaveRepo.save(lr);

        // Automatically sync attendance days when leave is APPROVED
        if ("APPROVED".equals(decision)) {
            boolean isHalf = lr.getTotalDays().compareTo(HALF) == 0 && lr.getStartDate().equals(lr.getEndDate());
            for (LocalDate d = lr.getStartDate(); !d.isAfter(lr.getEndDate()); d = d.plusDays(1)) {
                EmployeeAttendance att = attendanceRepo.findByUserIdAndAttendanceDate(lr.getUser().getId(), d)
                    .orElseGet(EmployeeAttendance::new);
                att.setUser(lr.getUser());
                att.setAttendanceDate(d);
                att.setStatus(isHalf ? "HALF_DAY" : "LEAVE");
                att.setLeaveType(lr.isPaid() ? "PAID" : "UNPAID");
                att.setNotes("Approved Leave (" + lr.getLeaveType() + "): " + lr.getReason());
                att.setRecordedBy(reviewer.getId());
                att.setUpdatedAt(Instant.now());
                attendanceRepo.save(att);
            }
        }

        EmployeeProfile ep = profiles.findByUserId(lr.getUser().getId()).orElse(null);
        audit.log("REVIEW_LEAVE", "LEAVE", saved.getId(), null,
            Map.of("status", decision, "userId", lr.getUser().getId(), "reviewedBy", reviewer.getUsername()));
        return toLeaveDto(saved, ep, reviewer.getFullName());
    }

    @Transactional
    public List<LeaveRequestDto> listLeaves(Long filterUserId, String status) {
        ensureProfilesSynced();
        User caller = cu.require();
        Long effectiveUserId = cu.isStaff() ? caller.getId() : filterUserId;

        List<LeaveRequest> list = effectiveUserId != null
            ? leaveRepo.findByUserIdOrderByCreatedAtDesc(effectiveUserId)
            : leaveRepo.findAllByOrderByCreatedAtDesc();

        String st = status == null ? "" : status.trim().toUpperCase();
        Map<Long, EmployeeProfile> profMap = new HashMap<>();
        profiles.findAll().forEach(p -> profMap.put(p.getUser().getId(), p));
        Map<Long, String> userNames = new HashMap<>();
        users.findAll().forEach(u -> userNames.put(u.getId(), u.getFullName()));

        return list.stream()
            .filter(l -> st.isEmpty() || "ALL".equals(st) || st.equals(l.getStatus()))
            .map(l -> toLeaveDto(l, profMap.get(l.getUser().getId()),
                l.getReviewedBy() != null ? userNames.get(l.getReviewedBy()) : null))
            .toList();
    }

    @Transactional
    public List<LeaveBalanceDto> listLeaveBalances() {
        ensureProfilesSynced();
        User caller = cu.require();
        int year = clock.today().getYear();
        return profiles.findAll().stream()
            .filter(ep -> !cu.isStaff() || ep.getUser().getId().equals(caller.getId()))
            .sorted(Comparator.comparing(ep -> ep.getUser().getId()))
            .map(ep -> computeLeaveBalance(ep, year))
            .toList();
    }

    private LeaveBalanceDto computeLeaveBalance(EmployeeProfile ep, int year) {
        LocalDate yearStart = LocalDate.of(year, 1, 1);
        LocalDate yearEnd = LocalDate.of(year, 12, 31);
        List<LeaveRequest> reqs = leaveRepo.findByUserIdOrderByCreatedAtDesc(ep.getUser().getId());

        BigDecimal paidUsed = BigDecimal.ZERO;
        BigDecimal paidPending = BigDecimal.ZERO;
        BigDecimal unpaidUsed = BigDecimal.ZERO;

        for (LeaveRequest r : reqs) {
            if (r.getStartDate().isBefore(yearStart) || r.getStartDate().isAfter(yearEnd)) continue;
            if ("APPROVED".equals(r.getStatus())) {
                if (r.isPaid()) paidUsed = paidUsed.add(r.getTotalDays());
                else unpaidUsed = unpaidUsed.add(r.getTotalDays());
            } else if ("PENDING".equals(r.getStatus()) && r.isPaid()) {
                paidPending = paidPending.add(r.getTotalDays());
            }
        }

        BigDecimal quota = BigDecimal.valueOf(ep.getPaidLeaveQuota()).setScale(1, RoundingMode.HALF_UP);
        BigDecimal remaining = quota.subtract(paidUsed).subtract(paidPending);
        if (remaining.signum() < 0) remaining = BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);

        return new LeaveBalanceDto(
            ep.getUser().getId(),
            ep.getEmployeeCode(),
            ep.getUser().getFullName(),
            ep.getPaidLeaveQuota(),
            paidUsed.setScale(1, RoundingMode.HALF_UP),
            paidPending.setScale(1, RoundingMode.HALF_UP),
            remaining.setScale(1, RoundingMode.HALF_UP),
            unpaidUsed.setScale(1, RoundingMode.HALF_UP)
        );
    }

    private LeaveRequestDto toLeaveDto(LeaveRequest l, EmployeeProfile ep, String reviewerName) {
        return new LeaveRequestDto(
            l.getId(),
            l.getUser().getId(),
            ep != null ? ep.getEmployeeCode() : "EMP-" + l.getUser().getId(),
            l.getUser().getFullName(),
            ep != null ? ep.getDesignation() : l.getUser().getRole().getName(),
            l.getLeaveType(),
            l.isPaid(),
            l.getStartDate(),
            l.getEndDate(),
            l.getTotalDays(),
            l.getReason(),
            l.getStatus(),
            reviewerName,
            l.getReviewedAt(),
            l.getReviewNote(),
            l.getCreatedAt()
        );
    }

    // =========================================================================
    // 4. PAYROLL SETTINGS, ADVANCES, PAYROLL CALCULATION & SALARY PAYMENTS
    // =========================================================================

    @Transactional
    public PayrollSettings getOrCreateSettings() {
        return settingsRepo.findById(1L).orElseGet(() -> {
            PayrollSettings s = new PayrollSettings();
            s.setId(1L);
            return settingsRepo.save(s);
        });
    }

    @Transactional
    public PayrollSettingsDto getPayrollSettingsDto() {
        PayrollSettings s = getOrCreateSettings();
        return toSettingsDto(s);
    }

    @Transactional
    public PayrollSettingsDto updatePayrollSettings(UpdatePayrollSettingsRequest r) {
        if (!cu.isOwner()) {
            throw new AccessDeniedException("Only the Store Owner can modify payroll rules");
        }
        PayrollSettings s = getOrCreateSettings();
        s.setStandardWorkingDays(r.standardWorkingDays());
        s.setStandardDailyHours(scale2(r.standardDailyHours()));
        s.setGraceLateMinutes(r.graceLateMinutes());
        s.setOvertimeMultiplier(scale2(r.overtimeMultiplier()));
        s.setDefaultAnnualPaidLeaves(r.defaultAnnualPaidLeaves());
        s.setDeductAbsentAutomatically(r.deductAbsentAutomatically());
        s.setAutoConvertAbsentToPaidLeave(r.autoConvertAbsentToPaidLeave());
        s.setFreeAbsentDaysAllowed(r.freeAbsentDaysAllowed().setScale(1, RoundingMode.HALF_UP));
        s.setLateDaysThreshold(r.lateDaysThreshold());
        s.setLatePenaltyPerDay(scale2(r.latePenaltyPerDay()));
        s.setUpdatedAt(Instant.now());

        PayrollSettings saved = settingsRepo.save(s);
        audit.log("UPDATE_PAYROLL_RULES", "PAYROLL_SETTINGS", 1L, null,
            Map.of("workingDays", s.getStandardWorkingDays(), "deductAbsentAuto", s.isDeductAbsentAutomatically()));
        return toSettingsDto(saved);
    }

    private PayrollSettingsDto toSettingsDto(PayrollSettings s) {
        return new PayrollSettingsDto(
            s.getStandardWorkingDays(),
            s.getStandardDailyHours(),
            s.getGraceLateMinutes(),
            s.getOvertimeMultiplier(),
            s.getDefaultAnnualPaidLeaves(),
            s.isDeductAbsentAutomatically(),
            s.isAutoConvertAbsentToPaidLeave(),
            s.getFreeAbsentDaysAllowed(),
            s.getLateDaysThreshold(),
            s.getLatePenaltyPerDay(),
            s.getUpdatedAt()
        );
    }

    @Transactional
    public SalaryAdvanceDto createAdvance(CreateAdvanceRequest r) {
        ensureProfilesSynced();
        User caller = cu.require();
        if (!cu.isOwner()) {
            throw new AccessDeniedException("Only the Store Owner can issue salary advances");
        }
        User empUser = users.findById(r.userId()).orElseThrow(() -> new NotFoundException("Employee not found"));
        EmployeeProfile ep = profiles.findByUserId(r.userId()).orElseThrow();

        LocalDate advDate = r.advanceDate() == null ? clock.today() : r.advanceDate();
        BigDecimal amt = scale2(r.amount());
        PaymentMethod method = PaymentMethod.valueOf(r.method().toUpperCase());

        // Record cash/UPI outflow as STAFF_SALARY expense
        Expense exp = new Expense();
        exp.setCategory(ExpenseCategory.STAFF_SALARY);
        exp.setAmount(amt);
        exp.setMethod(method);
        exp.setDescription("Salary Advance - " + empUser.getFullName() + " (" + ep.getEmployeeCode() + ")"
            + (r.reason() != null && !r.reason().isBlank() ? ": " + r.reason().trim() : ""));
        exp.setExpenseDate(advDate);
        exp.setCreatedBy(caller.getId());
        shifts.findByUserIdAndStatus(caller.getId(), ShiftStatus.OPEN).ifPresent(sh -> exp.setShiftId(sh.getId()));
        Expense savedExp = expenses.save(exp);

        SalaryAdvance adv = new SalaryAdvance();
        adv.setUser(empUser);
        adv.setAmount(amt);
        adv.setDeductedAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        adv.setAdvanceDate(advDate);
        adv.setMethod(method.name());
        adv.setReason(trimOrNull(r.reason()));
        adv.setStatus("OPEN");
        adv.setExpenseId(savedExp.getId());
        adv.setCreatedBy(caller.getId());
        SalaryAdvance saved = advanceRepo.save(adv);

        audit.log("SALARY_ADVANCE", "SALARY_ADVANCE", saved.getId(), null,
            Map.of("userId", empUser.getId(), "amount", amt, "method", method.name()));
        return toAdvanceDto(saved, ep);
    }

    @Transactional
    public List<SalaryAdvanceDto> listAdvances(Long filterUserId) {
        ensureProfilesSynced();
        User caller = cu.require();
        if (!cu.isOwner() && (filterUserId == null || !caller.getId().equals(filterUserId))) {
            if (cu.isStaff()) filterUserId = caller.getId();
            else throw new AccessDeniedException("Confidential salary advances are restricted to the Store Owner");
        }
        Map<Long, EmployeeProfile> profMap = new HashMap<>();
        profiles.findAll().forEach(p -> profMap.put(p.getUser().getId(), p));

        List<SalaryAdvance> list = filterUserId != null
            ? advanceRepo.findByUserIdOrderByAdvanceDateDesc(filterUserId)
            : advanceRepo.findAllByOrderByAdvanceDateDescIdDesc();
        return list.stream().map(a -> toAdvanceDto(a, profMap.get(a.getUser().getId()))).toList();
    }

    private SalaryAdvanceDto toAdvanceDto(SalaryAdvance a, EmployeeProfile ep) {
        BigDecimal rem = scale2(a.getAmount().subtract(a.getDeductedAmount()));
        return new SalaryAdvanceDto(
            a.getId(),
            a.getUser().getId(),
            ep != null ? ep.getEmployeeCode() : "EMP-" + a.getUser().getId(),
            a.getUser().getFullName(),
            scale2(a.getAmount()),
            scale2(a.getDeductedAmount()),
            rem,
            a.getAdvanceDate(),
            a.getMethod(),
            a.getReason(),
            a.getStatus(),
            a.getCreatedAt()
        );
    }

    @Transactional
    public List<PayrollRecordDto> generateMonthlyPayroll(String month, Long specificUserId) {
        ensureProfilesSynced();
        User caller = cu.require();
        if (!cu.isOwner()) {
            throw new AccessDeniedException("Only the Store Owner can generate and calculate payroll");
        }

        YearMonth ym = parseYearMonth(month);
        String ymStr = ym.toString();
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.atEndOfMonth();
        PayrollSettings settings = getOrCreateSettings();

        List<EmployeeProfile> targets = profiles.findAll().stream()
            .filter(ep -> specificUserId == null || ep.getUser().getId().equals(specificUserId))
            .filter(ep -> ep.getUser().isActive() || !"INACTIVE".equalsIgnoreCase(ep.getEmploymentStatus()))
            .sorted(Comparator.comparing(ep -> ep.getUser().getId()))
            .toList();

        List<PayrollRecordDto> out = new ArrayList<>();
        for (EmployeeProfile ep : targets) {
            PayrollRecord existing = payrollRepo.findByUserIdAndPayrollMonth(ep.getUser().getId(), ymStr).orElse(null);
            // Do not overwrite a payroll record that has already been paid or partially paid
            if (existing != null && !"PENDING".equals(existing.getPaymentStatus())) {
                out.add(toPayrollDto(existing, ep));
                continue;
            }
            PayrollRecord pr = calculateEmployeePayroll(ep, existing, ymStr, from, to, settings, caller.getId());
            out.add(toPayrollDto(pr, ep));
        }

        audit.log("GENERATE_PAYROLL", "PAYROLL", null, null,
            Map.of("month", ymStr, "employeesProcessed", out.size()));
        return out;
    }

    private PayrollRecord calculateEmployeePayroll(
            EmployeeProfile ep,
            PayrollRecord existing,
            String ymStr,
            LocalDate from,
            LocalDate to,
            PayrollSettings settings,
            Long generatedById) {

        User emp = ep.getUser();
        List<EmployeeAttendance> attList = attendanceRepo
            .findByUserIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(emp.getId(), from, to);

        BigDecimal presentDays = BigDecimal.ZERO;
        int halfDays = 0;
        BigDecimal paidLeaveDays = BigDecimal.ZERO;
        BigDecimal unpaidLeaveDays = BigDecimal.ZERO;
        BigDecimal absentDays = BigDecimal.ZERO;
        int weekOffDays = 0;
        int lateDays = 0;
        BigDecimal overtimeHours = BigDecimal.ZERO;

        for (EmployeeAttendance a : attList) {
            if (a.isLate()) lateDays++;
            if (a.getOvertimeHours() != null) overtimeHours = overtimeHours.add(a.getOvertimeHours());
            switch (a.getStatus()) {
                case "PRESENT" -> presentDays = presentDays.add(BigDecimal.ONE);
                case "HALF_DAY" -> {
                    halfDays++;
                    presentDays = presentDays.add(HALF);
                    absentDays = absentDays.add(HALF);
                }
                case "ABSENT" -> absentDays = absentDays.add(BigDecimal.ONE);
                case "LEAVE" -> {
                    if ("UNPAID".equalsIgnoreCase(a.getLeaveType())) {
                        unpaidLeaveDays = unpaidLeaveDays.add(BigDecimal.ONE);
                    } else {
                        paidLeaveDays = paidLeaveDays.add(BigDecimal.ONE);
                    }
                }
                case "WEEK_OFF" -> weekOffDays++;
            }
        }

        // Policy-aware absence & paid leave conversion:
        // Do not blindly deduct salary for every absence if the employee has unused paid leave quota
        // and autoConvertAbsentToPaidLeave is enabled, or if deductAbsentAutomatically is false.
        BigDecimal effectiveAbsentDays = absentDays;
        BigDecimal effectivePaidLeaveDays = paidLeaveDays;
        if (settings.isAutoConvertAbsentToPaidLeave() && effectiveAbsentDays.signum() > 0) {
            LeaveBalanceDto lb = computeLeaveBalance(ep, from.getYear());
            BigDecimal canConvert = lb.paidLeavesRemaining().min(effectiveAbsentDays);
            if (canConvert.signum() > 0) {
                effectivePaidLeaveDays = effectivePaidLeaveDays.add(canConvert);
                effectiveAbsentDays = effectiveAbsentDays.subtract(canConvert);
            }
        }

        int stdWorkingDays = Math.max(1, settings.getStandardWorkingDays());
        BigDecimal basicSalary = scale2(ep.getBasicSalary());
        BigDecimal dailyWage = ep.getDailyWage() != null && ep.getDailyWage().signum() > 0
            ? scale2(ep.getDailyWage())
            : basicSalary.divide(BigDecimal.valueOf(stdWorkingDays), 2, RoundingMode.HALF_UP);

        BigDecimal earnedBasic;
        BigDecimal leaveDeduction = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal payableDays;

        if ("DAILY".equalsIgnoreCase(ep.getSalaryType())) {
            payableDays = presentDays.add(effectivePaidLeaveDays).add(BigDecimal.valueOf(weekOffDays));
            earnedBasic = scale2(dailyWage.multiply(payableDays));
        } else {
            // MONTHLY salary structure
            earnedBasic = basicSalary;
            BigDecimal deductibleDays = unpaidLeaveDays;
            if (settings.isDeductAbsentAutomatically()) {
                BigDecimal chargeableAbsent = effectiveAbsentDays.subtract(settings.getFreeAbsentDaysAllowed());
                if (chargeableAbsent.signum() > 0) {
                    deductibleDays = deductibleDays.add(chargeableAbsent);
                }
            }
            if (deductibleDays.compareTo(BigDecimal.valueOf(stdWorkingDays)) > 0) {
                deductibleDays = BigDecimal.valueOf(stdWorkingDays);
            }
            leaveDeduction = scale2(deductibleDays.multiply(dailyWage));
            payableDays = BigDecimal.valueOf(stdWorkingDays).subtract(deductibleDays).max(BigDecimal.ZERO);
        }

        // Overtime pay
        BigDecimal overtimePay = scale2(
            overtimeHours.multiply(ep.getOvertimeHourlyRate()).multiply(settings.getOvertimeMultiplier())
        );

        // Preserve any manual bonus/incentives if recalculating an existing PENDING draft
        BigDecimal bonus = existing != null ? scale2(existing.getBonus()) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal incentives = existing != null ? scale2(existing.getIncentives()) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        // Late penalty (if configured in payroll rules)
        BigDecimal latePenalty = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        if (settings.getLatePenaltyPerDay().signum() > 0 && lateDays > settings.getLateDaysThreshold()) {
            latePenalty = scale2(
                settings.getLatePenaltyPerDay().multiply(BigDecimal.valueOf(lateDays - settings.getLateDaysThreshold()))
            );
        }
        BigDecimal otherDeductions = existing != null && existing.getOtherDeductions().signum() > 0
            ? scale2(existing.getOtherDeductions())
            : latePenalty;

        // Open salary advances for this employee
        List<SalaryAdvance> openAdvances = advanceRepo.findByUserIdAndStatusOrderByAdvanceDateAsc(emp.getId(), "OPEN");
        BigDecimal totalOpenAdvance = openAdvances.stream()
            .map(a -> a.getAmount().subtract(a.getDeductedAmount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal grossSalary = scale2(earnedBasic.add(overtimePay).add(bonus).add(incentives));
        BigDecimal maxPossibleAdvanceDeduction = grossSalary.subtract(leaveDeduction).subtract(otherDeductions).max(BigDecimal.ZERO);
        BigDecimal advanceDeduction = scale2(totalOpenAdvance.min(maxPossibleAdvanceDeduction));

        BigDecimal netSalary = scale2(
            grossSalary.subtract(leaveDeduction).subtract(advanceDeduction).subtract(otherDeductions).max(BigDecimal.ZERO)
        );

        PayrollRecord pr = existing != null ? existing : new PayrollRecord();
        if (pr.getPayslipNo() == null) {
            pr.setPayslipNo(nextPayslipNo(ymStr));
        }
        pr.setUser(emp);
        pr.setPayrollMonth(ymStr);
        pr.setSalaryType(ep.getSalaryType());
        pr.setBasicSalary(basicSalary);
        pr.setDailyWage(dailyWage);
        pr.setWorkingDays(stdWorkingDays);
        pr.setPresentDays(presentDays.setScale(1, RoundingMode.HALF_UP));
        pr.setHalfDays(halfDays);
        pr.setPaidLeaveDays(effectivePaidLeaveDays.setScale(1, RoundingMode.HALF_UP));
        pr.setUnpaidLeaveDays(unpaidLeaveDays.setScale(1, RoundingMode.HALF_UP));
        pr.setAbsentDays(effectiveAbsentDays.setScale(1, RoundingMode.HALF_UP));
        pr.setLateDays(lateDays);
        pr.setPayableDays(payableDays.setScale(1, RoundingMode.HALF_UP));
        pr.setOvertimeHours(scale2(overtimeHours));
        pr.setEarnedBasic(earnedBasic);
        pr.setOvertimePay(overtimePay);
        pr.setBonus(bonus);
        pr.setIncentives(incentives);
        pr.setLeaveDeduction(leaveDeduction);
        pr.setAdvanceDeduction(advanceDeduction);
        pr.setOtherDeductions(otherDeductions);
        pr.setGrossSalary(grossSalary);
        pr.setNetSalary(netSalary);
        pr.setPaidAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        pr.setPendingAmount(netSalary);
        pr.setPaymentStatus(netSalary.signum() == 0 ? "PAID" : "PENDING");
        pr.setGeneratedBy(generatedById);
        pr.setUpdatedAt(Instant.now());

        return payrollRepo.save(pr);
    }

    @Transactional
    public PayrollRecordDto updatePayrollAdjustments(Long payrollId, UpdatePayrollAdjustmentsRequest r) {
        ensureProfilesSynced();
        if (!cu.isOwner()) {
            throw new AccessDeniedException("Only the Store Owner can modify payroll adjustments");
        }
        PayrollRecord pr = payrollRepo.findById(payrollId)
            .orElseThrow(() -> new NotFoundException("Payroll record not found"));

        if ("PAID".equals(pr.getPaymentStatus())) {
            throw new BusinessException("Cannot modify a payroll record that has already been paid in full");
        }

        BigDecimal bonus = scale2(r.bonus());
        BigDecimal incentives = scale2(r.incentives());
        BigDecimal leaveDed = scale2(r.leaveDeduction());
        BigDecimal advDed = scale2(r.advanceDeduction());
        BigDecimal otherDed = scale2(r.otherDeductions());

        BigDecimal gross = scale2(pr.getEarnedBasic().add(pr.getOvertimePay()).add(bonus).add(incentives));
        BigDecimal totalDed = leaveDed.add(advDed).add(otherDed);
        if (totalDed.compareTo(gross) > 0) {
            throw new BusinessException("Total deductions (" + totalDed + ") cannot exceed gross salary (" + gross + ")");
        }
        BigDecimal net = scale2(gross.subtract(totalDed));
        if (net.compareTo(pr.getPaidAmount()) < 0) {
            throw new BusinessException("Net salary (" + net + ") cannot be less than amount already paid (" + pr.getPaidAmount() + ")");
        }

        pr.setBonus(bonus);
        pr.setIncentives(incentives);
        pr.setLeaveDeduction(leaveDed);
        pr.setAdvanceDeduction(advDed);
        pr.setOtherDeductions(otherDed);
        pr.setGrossSalary(gross);
        pr.setNetSalary(net);
        pr.setPendingAmount(scale2(net.subtract(pr.getPaidAmount())));
        if (pr.getPendingAmount().signum() == 0 && pr.getPaidAmount().signum() > 0) {
            pr.setPaymentStatus("PAID");
        } else if (pr.getPaidAmount().signum() > 0) {
            pr.setPaymentStatus("PARTIALLY_PAID");
        } else {
            pr.setPaymentStatus("PENDING");
        }
        pr.setNotes(trimOrNull(r.notes()));
        pr.setUpdatedAt(Instant.now());

        PayrollRecord saved = payrollRepo.save(pr);
        EmployeeProfile ep = profiles.findByUserId(pr.getUser().getId()).orElse(null);
        audit.log("UPDATE_PAYROLL", "PAYROLL", saved.getId(), null,
            Map.of("payslipNo", saved.getPayslipNo(), "gross", gross, "net", net));
        return toPayrollDto(saved, ep);
    }

    @Transactional
    public PayrollRecordDto paySalary(Long payrollId, PaySalaryRequest r) {
        ensureProfilesSynced();
        User caller = cu.require();
        if (!cu.isOwner()) {
            throw new AccessDeniedException("Only the Store Owner can disburse salary payments");
        }

        PayrollRecord pr = payrollRepo.findById(payrollId)
            .orElseThrow(() -> new NotFoundException("Payroll record not found"));

        if ("PAID".equals(pr.getPaymentStatus()) || pr.getPendingAmount().signum() <= 0) {
            throw new BusinessException("Salary for " + pr.getUser().getFullName() + " (" + pr.getPayrollMonth() + ") is already paid in full");
        }

        BigDecimal payAmt = scale2(r.amount());
        if (payAmt.compareTo(pr.getPendingAmount()) > 0) {
            throw new BusinessException("Payment amount (" + payAmt + ") exceeds pending salary balance (" + pr.getPendingAmount() + ")");
        }

        PaymentMethod method = PaymentMethod.valueOf(r.method().toUpperCase());
        EmployeeProfile ep = profiles.findByUserId(pr.getUser().getId()).orElse(null);

        // If this is the first payment on this payroll and advanceDeduction > 0, settle open advances
        if (pr.getPaidAmount().signum() == 0 && pr.getAdvanceDeduction().signum() > 0) {
            BigDecimal toRecover = pr.getAdvanceDeduction();
            List<SalaryAdvance> openAdvs = advanceRepo.findByUserIdAndStatusOrderByAdvanceDateAsc(pr.getUser().getId(), "OPEN");
            for (SalaryAdvance adv : openAdvs) {
                if (toRecover.signum() <= 0) break;
                BigDecimal rem = adv.getAmount().subtract(adv.getDeductedAmount());
                BigDecimal take = rem.min(toRecover);
                adv.setDeductedAmount(scale2(adv.getDeductedAmount().add(take)));
                if (adv.getDeductedAmount().compareTo(adv.getAmount()) >= 0) {
                    adv.setStatus("DEDUCTED");
                }
                advanceRepo.save(adv);
                toRecover = toRecover.subtract(take);
            }
        }

        // Create store expense under STAFF_SALARY so P&L and cash/UPI balances stay accurate
        Expense exp = new Expense();
        exp.setCategory(ExpenseCategory.STAFF_SALARY);
        exp.setAmount(payAmt);
        exp.setMethod(method);
        exp.setDescription("Salary Payment " + pr.getPayslipNo() + " (" + pr.getPayrollMonth() + ") - " + pr.getUser().getFullName());
        exp.setExpenseDate(clock.today());
        exp.setCreatedBy(caller.getId());
        shifts.findByUserIdAndStatus(caller.getId(), ShiftStatus.OPEN).ifPresent(sh -> exp.setShiftId(sh.getId()));
        Expense savedExp = expenses.save(exp);

        PayrollPayment payment = new PayrollPayment();
        payment.setPayroll(pr);
        payment.setAmount(payAmt);
        payment.setMethod(method.name());
        payment.setExpenseId(savedExp.getId());
        payment.setNote(trimOrNull(r.note()));
        payment.setPaidBy(caller.getId());
        payment.setPaidAt(Instant.now());
        paymentRepo.save(payment);

        BigDecimal newPaid = scale2(pr.getPaidAmount().add(payAmt));
        BigDecimal newPending = scale2(pr.getNetSalary().subtract(newPaid));
        pr.setPaidAmount(newPaid);
        pr.setPendingAmount(newPending);
        pr.setPaymentStatus(newPending.signum() == 0 ? "PAID" : "PARTIALLY_PAID");
        pr.setLastPaymentMethod(method.name());
        pr.setLastPaidAt(payment.getPaidAt());
        pr.setUpdatedAt(Instant.now());
        PayrollRecord saved = payrollRepo.save(pr);

        audit.log("PAY_SALARY", "PAYROLL", saved.getId(), null,
            Map.of("payslipNo", saved.getPayslipNo(), "amountPaid", payAmt, "method", method.name(),
                "status", saved.getPaymentStatus(), "pending", newPending));

        return toPayrollDto(saved, ep);
    }

    @Transactional
    public List<PayrollRecordDto> listPayrollRecords(String month, Long filterUserId) {
        ensureProfilesSynced();
        User caller = cu.require();

        // Role enforcement:
        // OWNER: can view all payroll records.
        // MANAGER & STAFF: can only view their own personal payslips.
        Long effectiveUserId = cu.isOwner() ? filterUserId : caller.getId();

        List<PayrollRecord> records;
        if (effectiveUserId != null && month != null && !month.isBlank()) {
            records = payrollRepo.findByUserIdAndPayrollMonth(effectiveUserId, month.trim())
                .map(List::of).orElse(List.of());
        } else if (effectiveUserId != null) {
            records = payrollRepo.findByUserIdOrderByPayrollMonthDesc(effectiveUserId);
        } else if (month != null && !month.isBlank()) {
            records = payrollRepo.findByPayrollMonthOrderByUserIdAsc(month.trim());
        } else {
            records = payrollRepo.findAllByOrderByPayrollMonthDescUserIdAsc();
        }

        Map<Long, EmployeeProfile> profMap = new HashMap<>();
        profiles.findAll().forEach(p -> profMap.put(p.getUser().getId(), p));

        return records.stream().map(r -> toPayrollDto(r, profMap.get(r.getUser().getId()))).toList();
    }

    @Transactional(readOnly = true)
    public PayrollRecordDto getPayslip(Long payrollId) {
        User caller = cu.require();
        PayrollRecord pr = payrollRepo.findById(payrollId)
            .orElseThrow(() -> new NotFoundException("Payslip not found"));
        if (!cu.isOwner() && !pr.getUser().getId().equals(caller.getId())) {
            throw new AccessDeniedException("You can only view your own payslips");
        }
        EmployeeProfile ep = profiles.findByUserId(pr.getUser().getId()).orElse(null);
        return toPayrollDto(pr, ep);
    }

    private PayrollRecordDto toPayrollDto(PayrollRecord pr, EmployeeProfile ep) {
        Map<Long, String> userNames = new HashMap<>();
        users.findAll().forEach(u -> userNames.put(u.getId(), u.getFullName()));

        List<PayrollPaymentDto> payments = paymentRepo.findByPayrollIdOrderByPaidAtDesc(pr.getId()).stream()
            .map(p -> new PayrollPaymentDto(
                p.getId(),
                scale2(p.getAmount()),
                p.getMethod(),
                p.getExpenseId(),
                p.getNote(),
                p.getPaidBy() != null ? userNames.getOrDefault(p.getPaidBy(), "Owner") : "Owner",
                p.getPaidAt()
            ))
            .toList();

        return new PayrollRecordDto(
            pr.getId(),
            pr.getPayslipNo(),
            pr.getUser().getId(),
            ep != null ? ep.getEmployeeCode() : "EMP-" + pr.getUser().getId(),
            pr.getUser().getFullName(),
            ep != null ? ep.getDesignation() : pr.getUser().getRole().getName(),
            ep != null ? ep.getPhone() : null,
            ep != null ? ep.getBankAccountNo() : null,
            ep != null ? ep.getIfscCode() : null,
            ep != null ? ep.getUpiId() : null,
            pr.getPayrollMonth(),
            pr.getSalaryType(),
            scale2(pr.getBasicSalary()),
            scale2(pr.getDailyWage()),
            pr.getWorkingDays(),
            pr.getPresentDays(),
            pr.getHalfDays(),
            pr.getPaidLeaveDays(),
            pr.getUnpaidLeaveDays(),
            pr.getAbsentDays(),
            pr.getLateDays(),
            pr.getPayableDays(),
            scale2(pr.getOvertimeHours()),
            scale2(pr.getEarnedBasic()),
            scale2(pr.getOvertimePay()),
            scale2(pr.getBonus()),
            scale2(pr.getIncentives()),
            scale2(pr.getLeaveDeduction()),
            scale2(pr.getAdvanceDeduction()),
            scale2(pr.getOtherDeductions()),
            scale2(pr.getGrossSalary()),
            scale2(pr.getNetSalary()),
            scale2(pr.getPaidAmount()),
            scale2(pr.getPendingAmount()),
            pr.getPaymentStatus(),
            pr.getLastPaymentMethod(),
            pr.getLastPaidAt(),
            pr.getNotes(),
            pr.getCreatedAt(),
            payments
        );
    }

    // =========================================================================
    // 5. EMPLOYEE DASHBOARD (KPIs, ATTENDANCE & SALES PERFORMANCE)
    // =========================================================================

    @Transactional
    public HrDashboardResponse getDashboard(String month) {
        ensureProfilesSynced();
        User caller = cu.require();
        LocalDate today = clock.today();
        YearMonth ym = parseYearMonth(month);
        String ymStr = ym.toString();

        List<EmployeeProfile> allProfiles = profiles.findAll();
        int totalEmployees = allProfiles.size();
        int activeEmployees = (int) allProfiles.stream()
            .filter(ep -> ep.getUser().isActive() && !"INACTIVE".equalsIgnoreCase(ep.getEmploymentStatus()))
            .count();

        List<AttendanceDto> todayList = getAttendanceForDate(today);
        int presentToday = 0;
        int absentToday = 0;
        int onLeaveToday = 0;
        int lateToday = 0;
        int notMarkedToday = 0;

        for (AttendanceDto a : todayList) {
            if (a.late()) lateToday++;
            switch (a.status()) {
                case "PRESENT", "HALF_DAY" -> presentToday++;
                case "ABSENT" -> absentToday++;
                case "LEAVE" -> onLeaveToday++;
                default -> notMarkedToday++;
            }
        }

        AttendanceCalendarResponse cal = getMonthlyAttendanceCalendar(ymStr, cu.isStaff() ? caller.getId() : null);
        BigDecimal avgAttPct = cal.employees().isEmpty()
            ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
            : cal.employees().stream()
                .map(EmployeeAttendanceSummaryDto::attendancePercentage)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(cal.employees().size()), 2, RoundingMode.HALF_UP);

        BigDecimal totalOvertimeHours = cal.employees().stream()
            .map(EmployeeAttendanceSummaryDto::totalOvertimeHours)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(2, RoundingMode.HALF_UP);

        PayrollSettings settings = getOrCreateSettings();
        Map<Long, EmployeeProfile> profByUserId = new HashMap<>();
        allProfiles.forEach(ep -> profByUserId.put(ep.getUser().getId(), ep));

        BigDecimal totalOvertimePay = cal.employees().stream()
            .map(s -> {
                EmployeeProfile ep = profByUserId.get(s.userId());
                BigDecimal rate = ep != null ? ep.getOvertimeHourlyRate() : new BigDecimal("100.00");
                return s.totalOvertimeHours().multiply(rate).multiply(settings.getOvertimeMultiplier());
            })
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(2, RoundingMode.HALF_UP);

        // Salary totals (only visible to OWNER)
        boolean salaryConfidential = !cu.isOwner();
        BigDecimal totalMonthlySalary = null;
        BigDecimal paidMonthlySalary = null;
        BigDecimal pendingSalaries = null;

        if (cu.isOwner()) {
            List<PayrollRecord> monthPayrolls = payrollRepo.findByPayrollMonthOrderByUserIdAsc(ymStr);
            if (!monthPayrolls.isEmpty()) {
                totalMonthlySalary = scale2(monthPayrolls.stream().map(PayrollRecord::getNetSalary).reduce(BigDecimal.ZERO, BigDecimal::add));
                paidMonthlySalary = scale2(monthPayrolls.stream().map(PayrollRecord::getPaidAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
                pendingSalaries = scale2(monthPayrolls.stream().map(PayrollRecord::getPendingAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
            } else {
                totalMonthlySalary = scale2(allProfiles.stream()
                    .filter(ep -> ep.getUser().isActive())
                    .map(EmployeeProfile::getBasicSalary)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
                paidMonthlySalary = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                pendingSalaries = totalMonthlySalary;
            }
        }

        int pendingLeaves = (int) leaveRepo.findAllByOrderByCreatedAtDesc().stream()
            .filter(l -> "PENDING".equals(l.getStatus()))
            .count();

        // Employee-wise sales performance for the selected month
        LocalDate mStart = ym.atDay(1);
        LocalDate mEnd = ym.atEndOfMonth();
        MapSqlParameterSource salesParams = new MapSqlParameterSource()
            .addValue("from", clock.odt(clock.startOf(mStart)), Types.TIMESTAMP_WITH_TIMEZONE)
            .addValue("to", clock.odt(clock.startOf(mEnd.plusDays(1))), Types.TIMESTAMP_WITH_TIMEZONE);

        String salesSql =
            "SELECT s.cashier_id AS user_id, " +
            "       COUNT(DISTINCT s.id) AS bills, " +
            "       COALESCE(SUM(GREATEST(si.quantity - COALESCE(si.returned_qty, 0), 0)), 0) AS qty, " +
            "       COALESCE(SUM(si.line_total), 0) AS rev, " +
            "       COALESCE(SUM(si.line_total - (si.cost_price * si.quantity)), 0) AS profit " +
            "FROM sales s " +
            "JOIN sale_items si ON si.sale_id = s.id " +
            "WHERE COALESCE(s.status, 'COMPLETED') != 'CANCELLED' " +
            "  AND s.created_at >= :from AND s.created_at < :to " +
            "GROUP BY s.cashier_id";

        Map<Long, Map<String, Object>> salesByUser = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(salesSql, salesParams)) {
            salesByUser.put(((Number) r.get("user_id")).longValue(), r);
        }

        Map<Long, EmployeeAttendanceSummaryDto> attSummaryByUser = new HashMap<>();
        cal.employees().forEach(s -> attSummaryByUser.put(s.userId(), s));

        List<EmployeeSalesPerformanceDto> perfList = allProfiles.stream()
            .filter(ep -> !cu.isStaff() || ep.getUser().getId().equals(caller.getId()))
            .map(ep -> {
                Long uid = ep.getUser().getId();
                Map<String, Object> sr = salesByUser.get(uid);
                long bills = sr != null ? ((Number) sr.get("bills")).longValue() : 0L;
                BigDecimal qty = sr != null ? ((BigDecimal) sr.get("qty")).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                BigDecimal rev = sr != null ? scale2((BigDecimal) sr.get("rev")) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                BigDecimal prof = sr != null ? scale2((BigDecimal) sr.get("profit")) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

                EmployeeAttendanceSummaryDto as = attSummaryByUser.get(uid);
                return new EmployeeSalesPerformanceDto(
                    uid,
                    ep.getEmployeeCode(),
                    ep.getUser().getFullName(),
                    ep.getUser().getRole().getName(),
                    ep.getDesignation(),
                    ep.getAssignedShift(),
                    bills,
                    qty,
                    rev,
                    prof,
                    as != null ? as.presentDays() : BigDecimal.ZERO,
                    as != null ? as.absentDays() : BigDecimal.ZERO,
                    as != null ? as.lateDays() : 0,
                    as != null ? as.totalOvertimeHours() : BigDecimal.ZERO,
                    as != null ? as.attendancePercentage() : BigDecimal.ZERO
                );
            })
            .sorted(Comparator.comparing(EmployeeSalesPerformanceDto::revenue).reversed())
            .toList();

        return new HrDashboardResponse(
            today,
            ymStr,
            totalEmployees,
            activeEmployees,
            presentToday,
            absentToday,
            onLeaveToday,
            lateToday,
            notMarkedToday,
            totalMonthlySalary,
            paidMonthlySalary,
            pendingSalaries,
            avgAttPct,
            totalOvertimeHours,
             salaryConfidential ? null : totalOvertimePay,
            pendingLeaves,
            salaryConfidential,
            todayList,
            perfList
        );
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private YearMonth parseYearMonth(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.from(clock.today());
        }
        try {
            return YearMonth.parse(month.trim());
        } catch (Exception e) {
            throw new BusinessException("Invalid month format. Expected YYYY-MM");
        }
    }

    private LocalTime parseLocalTime(String hhmm, LocalTime fallback) {
        if (hhmm == null || hhmm.isBlank()) return fallback;
        try {
            return LocalTime.parse(hhmm.trim(), DateTimeFormatter.ofPattern("H:mm"));
        } catch (Exception e) {
            return fallback;
        }
    }

    private Instant parseDateAndTime(LocalDate date, String hhmm) {
        if (date == null || hhmm == null || hhmm.isBlank()) return null;
        try {
            LocalTime lt = LocalTime.parse(hhmm.trim(), DateTimeFormatter.ofPattern("H:mm"));
            return ZonedDateTime.of(date, lt, clock.zone()).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal scale2(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    private static String trimOrNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
