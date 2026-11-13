package com.grocery.notification;

import com.grocery.common.CurrentUser;
import com.grocery.common.Exceptions.NotFoundException;
import org.springframework.data.domain.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    public record NotificationDto(Long id, NotificationType type, String message, String refType, Long refId, boolean read, Instant createdAt) {}

    private final NotificationRepository repo; private final CurrentUser cu;
    public NotificationController(NotificationRepository repo, CurrentUser cu) { this.repo = repo; this.cu = cu; }

    @GetMapping
    public Page<NotificationDto> list(@RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var pg = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        String role = cu.role();
        var res = unreadOnly ? repo.findByTargetRoleAndSeenFalseOrderByCreatedAtDesc(role, pg) : repo.findByTargetRoleOrderByCreatedAtDesc(role, pg);
        return res.map(n -> new NotificationDto(n.getId(), n.getType(), n.getMessage(), n.getRefType(), n.getRefId(), n.isSeen(), n.getCreatedAt()));
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unread() { return Map.of("count", repo.countByTargetRoleAndSeenFalse(cu.role())); }

    @PostMapping("/{id}/read") @Transactional
    public void read(@PathVariable Long id) {
        Notification n = repo.findById(id).orElseThrow(() -> new NotFoundException("Notification not found"));
        if (n.getTargetRole().equals(cu.role())) n.setSeen(true);
    }

    @PostMapping("/read-all") @Transactional
    public Map<String, Integer> readAll() { return Map.of("updated", repo.markAllSeen(cu.role())); }
}
