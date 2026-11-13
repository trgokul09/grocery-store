package com.grocery.purchase;

import com.grocery.common.PaymentMethod;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity @Table(name = "purchase_receipts")
@Getter @Setter @NoArgsConstructor
public class PurchaseReceipt {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "purchase_order_id") private PurchaseOrder purchaseOrder;
    private Long receivedBy;
    private Instant receivedAt = Instant.now();
    private BigDecimal totalValue = BigDecimal.ZERO;
    private BigDecimal paidAmount = BigDecimal.ZERO;
    @Enumerated(EnumType.STRING) private PaymentMethod paymentMethod;
    private String note;
    @OneToMany(mappedBy = "receipt", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PurchaseReceiptItem> items = new ArrayList<>();
}
