package com.grocery.purchase;

import com.grocery.supplier.Supplier;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity @Table(name = "purchase_orders")
@Getter @Setter @NoArgsConstructor
public class PurchaseOrder {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String poNumber;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "supplier_id") private Supplier supplier;
    @Enumerated(EnumType.STRING) private PoStatus status = PoStatus.DRAFT;
    private String notes;
    private LocalDate expectedDate;
    private Long createdBy;
    private Long approvedBy;
    private Instant createdAt = Instant.now();
    private Instant approvedAt;
    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<PurchaseItem> items = new ArrayList<>();
}
