package com.grocery.supplier;

import com.grocery.common.PaymentMethod;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public class SupplierDtos {
    public record SupplierRequest(@NotBlank @Size(max = 150) String name, @Size(max = 100) String contactPerson, @Size(max = 30) String phone,
                                  @Size(max = 120) String email, @Size(max = 255) String address, @Min(0) @Max(365) Integer paymentTermsDays,
                                  @DecimalMin("0.00") BigDecimal openingBalance) {}
    public record SupplierDto(Long id, String name, String contactPerson, String phone, String email, String address, int paymentTermsDays,
                              BigDecimal openingBalance, boolean approved, boolean active, BigDecimal outstanding) {}
    public record LedgerRow(Long id, Instant createdAt, LedgerType type, BigDecimal amount, String refType, Long refId, String note, BigDecimal balance) {}
    public record LedgerStatement(SupplierDto supplier, List<LedgerRow> rows) {}
    public record PaymentRequest(@NotNull @DecimalMin("0.01") BigDecimal amount, @NotNull PaymentMethod method, @Size(max = 255) String note) {}
    public enum AdjustmentKind { DEBIT, CREDIT }
    public record AdjustmentRequest(@NotNull AdjustmentKind kind, @NotNull @DecimalMin("0.01") BigDecimal amount, @NotBlank @Size(max = 255) String note) {}
    public record SupplierProductRequest(@NotNull Long productId, @DecimalMin("0.00") BigDecimal supplierPrice, @Min(0) @Max(365) Integer leadTimeDays, Boolean preferred) {}
    public record SupplierProductDto(Long id, Long productId, String productName, String sku, BigDecimal supplierPrice, int leadTimeDays, boolean preferred) {}
}
