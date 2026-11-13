package com.grocery.expense;

import com.grocery.common.PaymentMethod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;

public interface ExpenseRepository extends JpaRepository<Expense, Long>, JpaSpecificationExecutor<Expense> {
    @Query("select coalesce(sum(e.amount), 0) from Expense e where e.shiftId = :sid and e.method = :m")
    BigDecimal sumByShiftAndMethod(@Param("sid") Long shiftId, @Param("m") PaymentMethod m);
}
