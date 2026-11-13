package com.grocery.product;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {
    /** Pessimistic write lock: serialises every stock mutation of a product (checkout, receipts, adjustments). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") Long id);

    @Query("select p from Product p where p.active = true and p.currentStock > 0 and p.expiryDate is not null and p.expiryDate <= :limit")
    List<Product> findExpiring(@Param("limit") LocalDate limit);

    boolean existsBySkuIgnoreCase(String sku);
    boolean existsByBarcode(String barcode);
    Optional<Product> findByBarcode(String barcode);
    @Query("select lower(p.sku) from Product p where lower(p.sku) in :skus")
    List<String> findExistingSkus(Collection<String> skus);
    @Query("select p.barcode from Product p where p.barcode in :codes")
    List<String> findExistingBarcodes(Collection<String> codes);
}
