package com.grocery.sale;

import com.grocery.common.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public class SaleDtos {
    public record CartLine(@NotNull Long productId, @NotNull @DecimalMin("0.001") BigDecimal quantity, @DecimalMin("0.00") BigDecimal discount) {}
    public record PaymentLine(@NotNull PaymentMethod method, @NotNull @DecimalMin("0.01") BigDecimal amount) {}
    public record CheckoutRequest(@NotEmpty @Valid List<CartLine> items, @NotEmpty @Valid List<PaymentLine> payments, @Size(max = 100) String customerName) {}

    public record SaleItemDto(Long productId, String productName, String sku, BigDecimal quantity, BigDecimal unitPrice, BigDecimal discount,
                              BigDecimal taxRate, BigDecimal taxAmount, BigDecimal lineTotal) {}
    public record SalePaymentDto(PaymentMethod method, BigDecimal amount) {}
    public record SaleDto(Long id, String invoiceNo, Instant createdAt, String cashier, String customerName, BigDecimal subtotal, BigDecimal discountTotal,
                          BigDecimal taxTotal, BigDecimal total, List<SaleItemDto> items, List<SalePaymentDto> payments) {}
}
