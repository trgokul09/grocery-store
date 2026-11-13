package com.grocery.product;

import com.grocery.category.Category;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity @Table(name = "products")
public class Product {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private String name;
    @Column(nullable = false, unique = true) private String sku;
    private String barcode;
    private String brand;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "category_id") private Category category;
    @Column(name = "purchase_price", nullable = false) private BigDecimal purchasePrice;
    @Column(name = "selling_price", nullable = false) private BigDecimal sellingPrice;
    // Mutated ONLY by the Phase 2 inventory engine (pessimistic lock) - never via master-data update.
    @Column(name = "current_stock", nullable = false) private BigDecimal currentStock = BigDecimal.ZERO;
    @Column(name = "min_stock_level", nullable = false) private BigDecimal minStockLevel = BigDecimal.ZERO;
    @Column(name = "tax_rate", nullable = false) private BigDecimal taxRate = BigDecimal.ZERO;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private Unit unit;
    @Column(name = "expiry_date") private LocalDate expiryDate;
    @Column(name = "is_active", nullable = false) private boolean active = true;
    @Version private Long version;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    @PreUpdate void touch() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public String getName() { return name; } public void setName(String v) { name = v; }
    public String getSku() { return sku; } public void setSku(String v) { sku = v; }
    public String getBarcode() { return barcode; } public void setBarcode(String v) { barcode = v; }
    public String getBrand() { return brand; } public void setBrand(String v) { brand = v; }
    public Category getCategory() { return category; } public void setCategory(Category v) { category = v; }
    public BigDecimal getPurchasePrice() { return purchasePrice; } public void setPurchasePrice(BigDecimal v) { purchasePrice = v; }
    public BigDecimal getSellingPrice() { return sellingPrice; } public void setSellingPrice(BigDecimal v) { sellingPrice = v; }
    public BigDecimal getCurrentStock() { return currentStock; } public void setCurrentStock(BigDecimal v) { currentStock = v; }
    public BigDecimal getMinStockLevel() { return minStockLevel; } public void setMinStockLevel(BigDecimal v) { minStockLevel = v; }
    public BigDecimal getTaxRate() { return taxRate; } public void setTaxRate(BigDecimal v) { taxRate = v; }
    public Unit getUnit() { return unit; } public void setUnit(Unit v) { unit = v; }
    public LocalDate getExpiryDate() { return expiryDate; } public void setExpiryDate(LocalDate v) { expiryDate = v; }
    public boolean isActive() { return active; } public void setActive(boolean v) { active = v; }
    public Long getVersion() { return version; }
}
