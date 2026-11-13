package com.grocery.inventory;

import com.grocery.audit.AuditService;
import com.grocery.common.CurrentUser;
import com.grocery.common.Exceptions.*;
import com.grocery.common.StoreClock;
import com.grocery.notification.NotificationService;
import com.grocery.product.Product;
import com.grocery.product.ProductRepository;
import com.grocery.user.User;
import com.grocery.user.UserRepository;
import jakarta.persistence.criteria.Predicate;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The single gateway for every stock change. Each change:
 *  1. takes a PESSIMISTIC_WRITE row lock on the product (serialises concurrent checkouts/receipts),
 *  2. rejects negative stock,
 *  3. writes an immutable stock_movements row (previous/new stock, ref, user, reason).
 */
@Service
public class InventoryService {
    public enum AdjustmentType { INCREASE, DECREASE, DAMAGE, RETURN, AUDIT }

    public record AdjustRequest(@NotNull Long productId, @NotNull AdjustmentType type,
                                @NotNull @DecimalMin("0.000") BigDecimal quantity, @NotBlank @Size(min = 3, max = 255) String reason) {}
    public record MovementDto(Long id, Long productId, String productName, String sku, MovementType type, BigDecimal quantity,
                              BigDecimal previousStock, BigDecimal newStock, String refType, Long refId, Instant createdAt,
                              String username, String reason) {}

    private final ProductRepository products; private final StockMovementRepository movements;
    private final NotificationService notifications; private final CurrentUser cu; private final UserRepository users;
    private final AuditService audit; private final StoreClock clock;

    public InventoryService(ProductRepository products, StockMovementRepository movements, NotificationService notifications,
                            CurrentUser cu, UserRepository users, AuditService audit, StoreClock clock) {
        this.products = products; this.movements = movements; this.notifications = notifications;
        this.cu = cu; this.users = users; this.audit = audit; this.clock = clock;
    }

    @Transactional
    public StockMovement apply(Long productId, MovementType type, BigDecimal qty, String refType, Long refId, String reason) {
        if (qty == null || qty.signum() <= 0) throw new BusinessException("Quantity must be greater than zero");
        Product p = products.findByIdForUpdate(productId).orElseThrow(() -> new NotFoundException("Product not found: " + productId));
        BigDecimal prev = p.getCurrentStock();
        BigDecimal next = type.isIncrease() ? prev.add(qty) : prev.subtract(qty);
        if (next.signum() < 0)
            throw new BusinessException("Insufficient stock for " + p.getName() + " (available " + prev.stripTrailingZeros().toPlainString() + " " + p.getUnit() + ")");
        p.setCurrentStock(next);
        StockMovement m = new StockMovement();
        m.setProduct(p); m.setMovementType(type); m.setQuantity(qty); m.setPreviousStock(prev); m.setNewStock(next);
        m.setRefType(refType); m.setRefId(refId); m.setReason(reason);
        cu.find().ifPresent(u -> m.setUserId(u.getId()));
        movements.save(m);
        if (!type.isIncrease() && prev.compareTo(p.getMinStockLevel()) > 0 && next.compareTo(p.getMinStockLevel()) <= 0)
            notifications.lowStock(p);
        return m;
    }

    @Transactional
    public MovementDto adjust(AdjustRequest r) {
        Product p = products.findByIdForUpdate(r.productId()).orElseThrow(() -> new NotFoundException("Product not found"));
        BigDecimal before = p.getCurrentStock();
        StockMovement m;
        switch (r.type()) {
            case INCREASE -> m = apply(p.getId(), MovementType.MANUAL_ADD, r.quantity(), "MANUAL", null, r.reason());
            case DECREASE -> m = apply(p.getId(), MovementType.MANUAL_DEDUCT, r.quantity(), "MANUAL", null, r.reason());
            case DAMAGE -> m = apply(p.getId(), MovementType.DAMAGE, r.quantity(), "MANUAL", null, r.reason());
            case RETURN -> m = apply(p.getId(), MovementType.RETURN, r.quantity(), "MANUAL", null, r.reason());
            default -> { // AUDIT: quantity = physically counted stock
                BigDecimal diff = r.quantity().subtract(before);
                if (diff.signum() == 0) return null;
                m = apply(p.getId(), diff.signum() > 0 ? MovementType.MANUAL_ADD : MovementType.MANUAL_DEDUCT, diff.abs(),
                    "AUDIT", null, "Physical count (" + before.stripTrailingZeros().toPlainString() + " -> "
                        + r.quantity().stripTrailingZeros().toPlainString() + "): " + r.reason());
            }
        }
        audit.log("STOCK_ADJUST", "PRODUCT", p.getId(), Map.of("stock", before),
            Map.of("stock", m.getNewStock(), "type", r.type().name(), "quantity", m.getQuantity(), "reason", r.reason()));
        return toDto(m, cu.find().map(User::getUsername).orElse(null));
    }

    @Transactional(readOnly = true)
    public Page<MovementDto> search(Long productId, MovementType type, LocalDate from, LocalDate to, Pageable pg) {
        Specification<StockMovement> spec = (root, q, cb) -> {
            var preds = new ArrayList<Predicate>();
            if (productId != null) preds.add(cb.equal(root.get("product").get("id"), productId));
            if (type != null) preds.add(cb.equal(root.get("movementType"), type));
            if (from != null) preds.add(cb.greaterThanOrEqualTo(root.<Instant>get("createdAt"), clock.startOf(from)));
            if (to != null) preds.add(cb.lessThan(root.<Instant>get("createdAt"), clock.startOf(to.plusDays(1))));
            return cb.and(preds.toArray(new Predicate[0]));
        };
        Page<StockMovement> page = movements.findAll(spec, pg);
        Set<Long> ids = page.getContent().stream().map(StockMovement::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> names = users.findAllById(ids).stream().collect(Collectors.toMap(User::getId, User::getUsername));
        return page.map(m -> toDto(m, names.get(m.getUserId())));
    }

    private static MovementDto toDto(StockMovement m, String username) {
        Product p = m.getProduct();
        return new MovementDto(m.getId(), p.getId(), p.getName(), p.getSku(), m.getMovementType(), m.getQuantity(), m.getPreviousStock(),
            m.getNewStock(), m.getRefType(), m.getRefId(), m.getCreatedAt(), username, m.getReason());
    }
}
