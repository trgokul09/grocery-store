package com.grocery.supplier;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface SupplierProductRepository extends JpaRepository<SupplierProduct, Long> {
    List<SupplierProduct> findBySupplierId(Long supplierId);
    Optional<SupplierProduct> findBySupplierIdAndProductId(Long supplierId, Long productId);
    List<SupplierProduct> findByProductIdIn(java.util.Collection<Long> productIds);
}
