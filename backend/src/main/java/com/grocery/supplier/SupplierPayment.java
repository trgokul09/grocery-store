package com.grocery.supplier;

import com.grocery.common.PaymentMethod;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;

@Entity @Table(name = "supplier_payments")
@Getter @Setter @NoArgsConstructor
public class SupplierPayment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "supplier_id") private Supplier supplier;
    private BigDecimal amount;
    @Enumerated(EnumType.STRING) private PaymentMethod method;
    private Long receiptId;
    private Long shiftId;
    private String note;
    private Instant paidAt = Instant.now();
    private Long createdBy;
}
