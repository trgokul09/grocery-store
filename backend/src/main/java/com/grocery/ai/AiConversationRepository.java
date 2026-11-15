package com.grocery.ai;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface AiConversationRepository extends JpaRepository<AiConversation, Long> {
    List<AiConversation> findTop30ByUserIdOrderByUpdatedAtDesc(Long userId);
    Optional<AiConversation> findByIdAndUserId(Long id, Long userId);
    void deleteByUserId(Long userId);
}
