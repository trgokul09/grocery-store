package com.grocery.hr;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PayrollPaymentRepository extends JpaRepository<PayrollPayment, Long> {
    List<PayrollPayment> findByPayrollIdOrderByPaidAtDesc(Long payrollId);
}
