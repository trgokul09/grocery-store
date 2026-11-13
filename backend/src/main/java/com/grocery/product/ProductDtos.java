package com.grocery.product;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class ProductDtos {
    public record ProductRequest(
        @NotBlank @Size(max = 150) String name,
        @NotBlank @Size(max = 60) String sku,
        @Size(max = 60) String barcode,
        @Size(max = 100) String brand,
        Long categoryId,
        @NotNull @DecimalMin("0.00") BigDecimal purchasePrice,
        @NotNull @DecimalMin("0.00") BigDecimal sellingPrice,
        @DecimalMin("0.000") BigDecimal minStockLevel,
        @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal taxRate,
        @DecimalMin("0.000") BigDecimal openingStock,
        @NotNull Unit unit,
        LocalDate expiryDate) {}

    public record ProductDto(Long id, String name, String sku, String barcode, String brand,
        Long categoryId, String categoryName, BigDecimal purchasePrice, BigDecimal sellingPrice,
        BigDecimal currentStock, BigDecimal minStockLevel, BigDecimal taxRate, Unit unit, LocalDate expiryDate,
        boolean active, boolean lowStock) {}

    public record RowError(int row, String sku, String message) {}
    public record ImportResult(boolean success, int totalRows, int imported, List<RowError> errors) {}
}
