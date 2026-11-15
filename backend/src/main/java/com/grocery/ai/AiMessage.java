package com.grocery.ai;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "ai_messages")
public class AiMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "conversation_id", nullable = false)
    public Long conversationId;

    @Column(nullable = false, length = 15)
    public String role; // USER | ASSISTANT

    @Column(nullable = false, columnDefinition = "TEXT")
    public String content;

    @Column(length = 60)
    public String intent;

    @Column(name = "tools_invoked", length = 255)
    public String toolsInvoked;

    @Column(name = "structured_data", columnDefinition = "TEXT")
    public String structuredData;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();
}
