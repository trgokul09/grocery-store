package com.grocery.supplier;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.util.List;

public interface SupplierLedgerRepository extends JpaRepository<SupplierLedgerEntry, Long> {
    List<SupplierLedgerEntry> findBySupplierIdOrderByCreatedAtAscIdAsc(Long supplierId);

    @Query("select coalesce(sum(case when e.entryType in (com.grocery.supplier.LedgerType.PURCHASE, com.grocery.supplier.LedgerType.DEBIT_ADJUSTMENT) then e.amount else -e.amount end), 0) "
         + "from SupplierLedgerEntry e where e.supplier.id = :sid")
    BigDecimal netMovement(@Param("sid") Long supplierId);
}
