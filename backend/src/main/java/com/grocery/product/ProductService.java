package com.grocery.product;

import com.grocery.category.*;
import com.grocery.common.Exceptions.*;
import com.grocery.product.ProductDtos.*;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.ArrayList;

@Service
public class ProductService {
    private final ProductRepository products; private final CategoryRepository categories;
    private final com.grocery.inventory.InventoryService inventory; private final com.grocery.audit.AuditService audit;
    public ProductService(ProductRepository p, CategoryRepository c, com.grocery.inventory.InventoryService inv, com.grocery.audit.AuditService a) {
        products = p; categories = c; inventory = inv; audit = a;
    }

    public static ProductDto toDto(Product p) {
        var c = p.getCategory();
        return new ProductDto(p.getId(), p.getName(), p.getSku(), p.getBarcode(), p.getBrand(),
            c == null ? null : c.getId(), c == null ? null : c.getName(), p.getPurchasePrice(), p.getSellingPrice(),
            p.getCurrentStock(), p.getMinStockLevel(), p.getTaxRate(), p.getUnit(), p.getExpiryDate(), p.isActive(),
            p.getCurrentStock().compareTo(p.getMinStockLevel()) <= 0);
    }

    @Transactional(readOnly = true)
    public Page<ProductDto> search(String q, Long categoryId, Boolean active, boolean lowStockOnly, Pageable pg) {
        Specification<Product> spec = (root, query, cb) -> {
            var preds = new ArrayList<Predicate>();
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase() + "%";
                preds.add(cb.or(cb.like(cb.lower(root.get("name")), like), cb.like(cb.lower(root.get("sku")), like),
                    cb.like(cb.lower(root.get("barcode")), like)));
            }
            if (categoryId != null) preds.add(cb.equal(root.get("category").get("id"), categoryId));
            if (active != null) preds.add(cb.equal(root.get("active"), active));
            if (lowStockOnly) preds.add(cb.lessThanOrEqualTo(root.<BigDecimal>get("currentStock"), root.<BigDecimal>get("minStockLevel")));
            return cb.and(preds.toArray(new Predicate[0]));
        };
        return products.findAll(spec, pg).map(ProductService::toDto);
    }

    @Transactional(readOnly = true)
    public ProductDto get(Long id) { return toDto(find(id)); }

    @Transactional(readOnly = true)
    public ProductDto getByBarcode(String code) {
        return toDto(products.findByBarcode(code).orElseThrow(() -> new NotFoundException("No product with barcode " + code)));
    }

    @Transactional
    public ProductDto create(ProductRequest r) {
        String sku = r.sku().trim();
        if (products.existsBySkuIgnoreCase(sku)) throw new BusinessException("SKU already exists: " + sku);
        String barcode = blankToNull(r.barcode());
        if (barcode != null && products.existsByBarcode(barcode)) throw new BusinessException("Barcode already exists: " + barcode);
        Product p = new Product();
        p.setSku(sku);
        apply(p, r);
        p = products.save(p);
        if (r.openingStock() != null && r.openingStock().signum() > 0)
            inventory.apply(p.getId(), com.grocery.inventory.MovementType.MANUAL_ADD, r.openingStock(), "PRODUCT", p.getId(), "Opening stock");
        ProductDto dto = toDto(p);
        audit.log("CREATE", "PRODUCT", p.getId(), null, dto);
        return dto;
    }

    @Transactional
    public ProductDto update(Long id, ProductRequest r) {
        Product p = find(id);
        ProductDto before = toDto(p);
        String sku = r.sku().trim();
        if (!p.getSku().equalsIgnoreCase(sku) && products.existsBySkuIgnoreCase(sku)) throw new BusinessException("SKU already exists: " + sku);
        String barcode = blankToNull(r.barcode());
        if (barcode != null && !barcode.equals(p.getBarcode()) && products.existsByBarcode(barcode))
            throw new BusinessException("Barcode already exists: " + barcode);
        p.setSku(sku);
        apply(p, r); // stock is intentionally NOT editable here
        ProductDto after = toDto(p);
        audit.log("UPDATE", "PRODUCT", id, before, after);
        return after;
    }

    /** Soft delete only - products are never hard deleted. */
    @Transactional
    public ProductDto setActive(Long id, boolean active) {
        Product p = find(id); boolean was = p.isActive(); p.setActive(active);
        audit.log(active ? "ACTIVATE" : "DEACTIVATE", "PRODUCT", id, java.util.Map.of("active", was), java.util.Map.of("active", active));
        return toDto(p);
    }

    private void apply(Product p, ProductRequest r) {
        p.setName(r.name().trim()); p.setBarcode(blankToNull(r.barcode())); p.setBrand(blankToNull(r.brand()));
        p.setPurchasePrice(r.purchasePrice()); p.setSellingPrice(r.sellingPrice());
        p.setMinStockLevel(r.minStockLevel() == null ? BigDecimal.ZERO : r.minStockLevel());
        p.setTaxRate(BigDecimal.ZERO);   // tax is not used
        p.setUnit(r.unit()); p.setExpiryDate(r.expiryDate());
        p.setCategory(r.categoryId() == null ? null : categories.findById(r.categoryId())
            .orElseThrow(() -> new NotFoundException("Category not found")));
    }

    private Product find(Long id) { return products.findById(id).orElseThrow(() -> new NotFoundException("Product not found")); }
    static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
