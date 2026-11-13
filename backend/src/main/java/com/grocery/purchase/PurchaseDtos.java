package com.grocery.purchase;

import com.grocery.common.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public class PurchaseDtos {
    public record PoLineRequest(@NotNull Long productId, @NotNull @DecimalMin("0.001") BigDecimal quantity, @DecimalMin("0.00") BigDecimal unitCost) {}
    public record PoRequest(@NotNull Long supplierId, LocalDate expectedDate, @Size(max = 500) String notes, @NotEmpty @Valid List<PoLineRequest> items) {}
    public record ReceiveLine(@NotNull Long itemId, @NotNull @DecimalMin("0.000") BigDecimal receivedQty, @DecimalMin("0.000") BigDecimal damagedQty) {}
    public record ReceiveRequest(@NotEmpty @Valid List<ReceiveLine> lines, @DecimalMin("0.00") BigDecimal paidAmount, PaymentMethod paymentMethod, @Size(max = 255) String note) {}
    public record RejectRequest(@Size(max = 300) String reason) {}

    public record ItemDto(Long id, Long productId, String productName, String sku, String unit, BigDecimal orderedQty, BigDecimal unitCost,
                          BigDecimal receivedQty, BigDecimal damagedQty) {}
    public record ReceiptLineDto(String productName, BigDecimal receivedQty, BigDecimal damagedQty, BigDecimal unitCost) {}
    public record ReceiptDto(Long id, Instant receivedAt, String receivedBy, BigDecimal totalValue, BigDecimal paidAmount, PaymentMethod paymentMethod,
                             String note, List<ReceiptLineDto> lines) {}
    public record PoDto(Long id, String poNumber, Long supplierId, String supplierName, PoStatus status, String notes, LocalDate expectedDate,
                        Instant createdAt, BigDecimal total, List<ItemDto> items, List<ReceiptDto> receipts) {}
}
