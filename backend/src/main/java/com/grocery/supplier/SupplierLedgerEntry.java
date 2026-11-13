package com.grocery.supplier;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;

@Entity @Table(name = "supplier_ledger")
@Getter @Setter @NoArgsConstructor
public class SupplierLedgerEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "supplier_id") private Supplier supplier;
    @Enumerated(EnumType.STRING) private LedgerType entryType;
    private BigDecimal amount;
    private String refType;
    private Long refId;
    private String note;
    private Instant createdAt = Instant.now();
    private Long createdBy;
}
