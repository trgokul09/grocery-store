package com.grocery.sale;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;

public interface SaleRepository extends JpaRepository<Sale, Long>, JpaSpecificationExecutor<Sale> {
    @Query("select si.product.id from SaleItem si where si.sale.createdAt >= :since group by si.product.id order by sum(si.quantity) desc")
    List<Long> topProductIds(@Param("since") Instant since, Pageable pg);
}
