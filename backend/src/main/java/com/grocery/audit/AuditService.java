package com.grocery.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grocery.common.CurrentUser;
import com.grocery.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Writes audit rows in the caller's transaction, so a rolled-back change leaves no audit trail. */
@Service
public class AuditService {
    private final AuditLogRepository repo; private final ObjectMapper om; private final CurrentUser cu;
    public AuditService(AuditLogRepository repo, ObjectMapper om, CurrentUser cu) { this.repo = repo; this.om = om; this.cu = cu; }

    @Transactional
    public void log(String action, String entityType, Object entityId, Object oldValue, Object newValue) {
        AuditLog a = new AuditLog();
        User u = cu.find().orElse(null);
        a.setUserId(u == null ? null : u.getId());
        a.setUsername(u == null ? "system" : u.getUsername());
        a.setAction(action);
        a.setEntityType(entityType);
        a.setEntityId(entityId == null ? null : String.valueOf(entityId));
        a.setOldValue(json(oldValue));
        a.setNewValue(json(newValue));
        repo.save(a);
    }

    private String json(Object o) {
        if (o == null) return null;
        try { return om.writeValueAsString(o); } catch (Exception e) { return String.valueOf(o); }
    }
}
