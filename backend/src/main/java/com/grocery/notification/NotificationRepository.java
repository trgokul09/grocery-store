package com.grocery.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    Page<Notification> findByTargetRoleOrderByCreatedAtDesc(String role, Pageable p);
    Page<Notification> findByTargetRoleAndSeenFalseOrderByCreatedAtDesc(String role, Pageable p);
    long countByTargetRoleAndSeenFalse(String role);
    boolean existsByTargetRoleAndDedupeKey(String role, String key);
    @Modifying @Query("update Notification n set n.seen = true where n.targetRole = :role and n.seen = false")
    int markAllSeen(@Param("role") String role);
}
