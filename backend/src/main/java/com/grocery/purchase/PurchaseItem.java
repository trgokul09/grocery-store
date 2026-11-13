package com.grocery.purchase;

import com.grocery.product.Product;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;

@Entity @Table(name = "purchase_items")
@Getter @Setter @NoArgsConstructor
public class PurchaseItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "purchase_order_id") private PurchaseOrder purchaseOrder;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "product_id") private Product product;
    private BigDecimal orderedQty;
    private BigDecimal unitCost;
    private BigDecimal receivedQty = BigDecimal.ZERO;   // physical quantity received so far (incl. damaged)
    private BigDecimal damagedQty = BigDecimal.ZERO;    // damaged portion of receivedQty (not added to stock, not paid for)
}
