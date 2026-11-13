package com.grocery.notification;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

@Entity @Table(name = "notifications")
@Getter @Setter @NoArgsConstructor
public class Notification {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Enumerated(EnumType.STRING) private NotificationType type;
    private String message;
    private String targetRole;
    private String refType;
    private Long refId;
    private String dedupeKey;
    @Column(name = "is_read") private boolean seen;
    private Instant createdAt = Instant.now();
}
