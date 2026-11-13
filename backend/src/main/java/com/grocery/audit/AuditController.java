package com.grocery.audit;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.ArrayList;

@RestController
@RequestMapping("/api/audit-logs")
@PreAuthorize("hasRole('OWNER')")
public class AuditController {
    public record AuditDto(Long id, Long userId, String username, String action, String entityType, String entityId,
                           String oldValue, String newValue, Instant createdAt) {}

    private final AuditLogRepository repo; private final com.grocery.common.StoreClock clock;
    public AuditController(AuditLogRepository repo, com.grocery.common.StoreClock clock) { this.repo = repo; this.clock = clock; }

    @GetMapping
    public Page<AuditDto> list(@RequestParam(required = false) String entityType, @RequestParam(required = false) String action,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "30") int size) {
        Specification<AuditLog> spec = (root, q, cb) -> {
            var p = new ArrayList<Predicate>();
            if (entityType != null && !entityType.isBlank()) p.add(cb.equal(root.get("entityType"), entityType));
            if (action != null && !action.isBlank()) p.add(cb.equal(root.get("action"), action));
            if (username != null && !username.isBlank()) p.add(cb.equal(root.get("username"), username));
            if (from != null) p.add(cb.greaterThanOrEqualTo(root.<Instant>get("createdAt"), clock.startOf(from)));
            if (to != null) p.add(cb.lessThan(root.<Instant>get("createdAt"), clock.startOf(to.plusDays(1))));
            return cb.and(p.toArray(new Predicate[0]));
        };
        var pg = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200), Sort.by(Sort.Direction.DESC, "id"));
        return repo.findAll(spec, pg).map(a -> new AuditDto(a.getId(), a.getUserId(), a.getUsername(), a.getAction(),
            a.getEntityType(), a.getEntityId(), a.getOldValue(), a.getNewValue(), a.getCreatedAt()));
    }
}
