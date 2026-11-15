package com.grocery.hr;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SalaryAdvanceRepository extends JpaRepository<SalaryAdvance, Long> {
    List<SalaryAdvance> findByUserIdOrderByAdvanceDateDesc(Long userId);
    List<SalaryAdvance> findByUserIdAndStatusOrderByAdvanceDateAsc(Long userId, String status);
    List<SalaryAdvance> findAllByOrderByAdvanceDateDescIdDesc();
}
