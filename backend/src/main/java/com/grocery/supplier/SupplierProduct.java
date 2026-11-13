package com.grocery.supplier;

import com.grocery.product.Product;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;

@Entity @Table(name = "supplier_products")
@Getter @Setter @NoArgsConstructor
public class SupplierProduct {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "supplier_id") private Supplier supplier;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "product_id") private Product product;
    private BigDecimal supplierPrice;
    private int leadTimeDays = 3;
    private boolean preferred;
}
