package com.grocery.audit;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;
import java.time.Instant;

@Entity @Immutable @Table(name = "audit_logs")
@Getter @Setter @NoArgsConstructor
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private Long userId;
    private String username;
    private String action;
    private String entityType;
    private String entityId;
    @Column(columnDefinition = "text") private String oldValue;
    @Column(columnDefinition = "text") private String newValue;
    private Instant createdAt = Instant.now();
}
