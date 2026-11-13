package com.grocery.supplier;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;

@Entity @Table(name = "suppliers")
@Getter @Setter @NoArgsConstructor
public class Supplier {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String name;
    private String contactPerson;
    private String phone;
    private String email;
    private String address;
    private int paymentTermsDays;
    private BigDecimal openingBalance = BigDecimal.ZERO;
    private boolean approved;
    private Long approvedBy;
    @Column(name = "is_active") private boolean active = true;
    private Instant createdAt = Instant.now();
}
