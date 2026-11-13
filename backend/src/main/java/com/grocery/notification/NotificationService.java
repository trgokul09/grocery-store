package com.grocery.notification;

import com.grocery.product.Product;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;

@Service
public class NotificationService {
    private static final String[] MGMT = {"OWNER", "MANAGER"};
    private final NotificationRepository repo;
    public NotificationService(NotificationRepository repo) { this.repo = repo; }

    @Transactional
    public void notifyRoles(NotificationType type, String message, String refType, Long refId, String dedupeKey, String... roles) {
        for (String role : roles) {
            if (dedupeKey != null && repo.existsByTargetRoleAndDedupeKey(role, dedupeKey)) continue;
            Notification n = new Notification();
            n.setType(type); n.setMessage(message.length() > 300 ? message.substring(0, 300) : message);
            n.setTargetRole(role); n.setRefType(refType); n.setRefId(refId); n.setDedupeKey(dedupeKey);
            repo.save(n);
        }
    }

    public void lowStock(Product p) {
        notifyRoles(NotificationType.LOW_STOCK, "Low stock: " + p.getName() + " has " + plain(p.getCurrentStock()) + " "
            + p.getUnit() + " left (min " + plain(p.getMinStockLevel()) + ")", "PRODUCT", p.getId(), null, MGMT);
    }
    public void poPendingApproval(Long poId, String poNumber, String supplier) {
        notifyRoles(NotificationType.PO_APPROVAL, "Purchase order " + poNumber + " (" + supplier + ") awaits approval",
            "PURCHASE_ORDER", poId, null, "OWNER");
    }
    public void shiftVariance(Long shiftId, String who, BigDecimal variance) {
        notifyRoles(NotificationType.SHIFT_VARIANCE, "Cash variance of " + variance.toPlainString() + " in " + who + "'s closed shift",
            "SHIFT", shiftId, null, MGMT);
    }
    public void expiry(Product p, String message, String dedupeKey) {
        notifyRoles(NotificationType.EXPIRY, message, "PRODUCT", p.getId(), dedupeKey, MGMT);
    }
    private static String plain(BigDecimal b) { return b.stripTrailingZeros().toPlainString(); }
}
