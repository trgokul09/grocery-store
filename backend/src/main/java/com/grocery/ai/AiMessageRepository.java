package com.grocery.ai;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AiMessageRepository extends JpaRepository<AiMessage, Long> {
    List<AiMessage> findByConversationIdOrderByCreatedAtAsc(Long conversationId);
    List<AiMessage> findTop10ByConversationIdOrderByCreatedAtDesc(Long conversationId);
}
