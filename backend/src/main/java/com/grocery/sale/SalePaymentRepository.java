package com.grocery.sale;

import com.grocery.common.PaymentMethod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;

public interface SalePaymentRepository extends JpaRepository<SalePayment, Long> {
    @Query("select coalesce(sum(p.amount), 0) from SalePayment p where p.sale.shiftId = :sid and p.method = :m")
    BigDecimal sumByShiftAndMethod(@Param("sid") Long shiftId, @Param("m") PaymentMethod m);
}
