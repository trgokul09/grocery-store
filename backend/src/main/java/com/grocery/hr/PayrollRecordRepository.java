package com.grocery.hr;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PayrollRecordRepository extends JpaRepository<PayrollRecord, Long> {
    Optional<PayrollRecord> findByUserIdAndPayrollMonth(Long userId, String payrollMonth);
    List<PayrollRecord> findByPayrollMonthOrderByUserIdAsc(String payrollMonth);
    List<PayrollRecord> findByUserIdOrderByPayrollMonthDesc(Long userId);
    List<PayrollRecord> findAllByOrderByPayrollMonthDescUserIdAsc();
}
