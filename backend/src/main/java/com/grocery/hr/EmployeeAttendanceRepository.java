package com.grocery.hr;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface EmployeeAttendanceRepository extends JpaRepository<EmployeeAttendance, Long> {
    Optional<EmployeeAttendance> findByUserIdAndAttendanceDate(Long userId, LocalDate attendanceDate);
    List<EmployeeAttendance> findByAttendanceDateOrderByUserIdAsc(LocalDate attendanceDate);
    List<EmployeeAttendance> findByUserIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(Long userId, LocalDate from, LocalDate to);
    List<EmployeeAttendance> findByAttendanceDateBetweenOrderByAttendanceDateDescUserIdAsc(LocalDate from, LocalDate to);
}
