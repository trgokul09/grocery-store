package com.grocery.purchase;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;

@Entity @Table(name = "purchase_receipt_items")
@Getter @Setter @NoArgsConstructor
public class PurchaseReceiptItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "receipt_id") private PurchaseReceipt receipt;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "purchase_item_id") private PurchaseItem purchaseItem;
    private BigDecimal receivedQty;
    private BigDecimal damagedQty;
    private BigDecimal unitCost;
}
