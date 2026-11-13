package com.grocery.purchase;

import com.grocery.audit.AuditService;
import com.grocery.common.CurrentUser;
import com.grocery.common.Exceptions.*;
import com.grocery.common.PaymentMethod;
import com.grocery.inventory.InventoryService;
import com.grocery.inventory.MovementType;
import com.grocery.inventory.StockMovement;
import com.grocery.notification.NotificationService;
import com.grocery.product.Product;
import com.grocery.product.ProductRepository;
import com.grocery.purchase.PurchaseDtos.*;
import com.grocery.supplier.Supplier;
import com.grocery.supplier.SupplierProduct;
import com.grocery.supplier.SupplierProductRepository;
import com.grocery.supplier.SupplierRepository;
import com.grocery.supplier.SupplierService;
import com.grocery.user.User;
import com.grocery.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.Year;
import java.util.*;

/**
 * PO lifecycle: DRAFT -> PENDING_APPROVAL -> APPROVED -> PARTIALLY_RECEIVED -> RECEIVED (or CANCELLED).
 * Receiving is atomic: stock + stock_movements + supplier ledger (+ optional payment) commit together.
 */
@Service
public class PurchaseService {
    private final PurchaseOrderRepository pos; private final PurchaseReceiptRepository receipts; private final SupplierRepository suppliers;
    private final SupplierService supplierService; private final SupplierProductRepository supplierProducts; private final ProductRepository products;
    private final InventoryService inventory; private final NotificationService notifications; private final AuditService audit;
    private final CurrentUser cu; private final UserRepository users; private final EntityManager em;

    public PurchaseService(PurchaseOrderRepository pos, PurchaseReceiptRepository receipts, SupplierRepository suppliers, SupplierService ss,
                           SupplierProductRepository sp, ProductRepository products, InventoryService inv, NotificationService n, AuditService a,
                           CurrentUser cu, UserRepository users, EntityManager em) {
        this.pos = pos; this.receipts = receipts; this.suppliers = suppliers; supplierService = ss; supplierProducts = sp; this.products = products;
        inventory = inv; notifications = n; audit = a; this.cu = cu; this.users = users; this.em = em;
    }

    private static BigDecimal nz(BigDecimal b) { return b == null ? BigDecimal.ZERO : b; }
    private PurchaseOrder find(Long id) { return pos.findById(id).orElseThrow(() -> new NotFoundException("Purchase order not found")); }

    private String nextNumber() {
        Number n = (Number) em.createNativeQuery("select nextval('po_seq')").getSingleResult();
        return "PO-" + Year.now().getValue() + "-" + String.format("%06d", n.longValue());
    }

    // ---------- queries ----------
    @Transactional(readOnly = true)
    public Page<PoDto> list(PoStatus status, Long supplierId, boolean staffView, Pageable pg) {
        Specification<PurchaseOrder> spec = (root, q, cb) -> {
            var p = new ArrayList<Predicate>();
            if (staffView) p.add(root.get("status").in(PoStatus.APPROVED, PoStatus.PARTIALLY_RECEIVED));
            else if (status != null) p.add(cb.equal(root.get("status"), status));
            if (supplierId != null) p.add(cb.equal(root.get("supplier").get("id"), supplierId));
            return cb.and(p.toArray(new Predicate[0]));
        };
        return pos.findAll(spec, pg).map(po -> toDto(po, false));
    }

    @Transactional(readOnly = true)
    public PoDto get(Long id, boolean staffView) {
        PurchaseOrder po = find(id);
        if (staffView && po.getStatus() != PoStatus.APPROVED && po.getStatus() != PoStatus.PARTIALLY_RECEIVED && po.getStatus() != PoStatus.RECEIVED)
            throw new NotFoundException("Purchase order not found");
        return toDto(po, true);
    }

    // ---------- commands ----------
    @Transactional
    public PoDto create(PoRequest r) {
        PurchaseOrder po = new PurchaseOrder();
        po.setPoNumber(nextNumber());
        po.setCreatedBy(cu.require().getId());
        fill(po, r);
        po = pos.save(po);
        audit.log("CREATE", "PURCHASE_ORDER", po.getId(), null, Map.of("poNumber", po.getPoNumber(), "supplierId", r.supplierId()));
        return toDto(po, false);
    }

    @Transactional
    public PoDto update(Long id, PoRequest r) {
        PurchaseOrder po = find(id);
        if (po.getStatus() != PoStatus.DRAFT) throw new BusinessException("Only DRAFT purchase orders can be edited");
        po.getItems().clear();
        em.flush();
        fill(po, r);
        audit.log("UPDATE", "PURCHASE_ORDER", id, null, Map.of("poNumber", po.getPoNumber()));
        return toDto(po, false);
    }

    private void fill(PurchaseOrder po, PoRequest r) {
        Supplier s = suppliers.findById(r.supplierId()).orElseThrow(() -> new NotFoundException("Supplier not found"));
        if (!s.isActive()) throw new BusinessException("Supplier is inactive");
        po.setSupplier(s); po.setExpectedDate(r.expectedDate()); po.setNotes(r.notes());
        Map<Long, PoLineRequest> merged = new LinkedHashMap<>();
        for (PoLineRequest l : r.items()) merged.merge(l.productId(), l, (a, b) -> new PoLineRequest(a.productId(), a.quantity().add(b.quantity()), a.unitCost()));
        for (PoLineRequest l : merged.values()) {
            Product p = products.findById(l.productId()).orElseThrow(() -> new NotFoundException("Product not found: " + l.productId()));
            if (!p.isActive()) throw new BusinessException("Product is inactive: " + p.getName());
            BigDecimal cost = l.unitCost() != null ? l.unitCost()
                : supplierProducts.findBySupplierIdAndProductId(s.getId(), p.getId()).map(SupplierProduct::getSupplierPrice).filter(Objects::nonNull).orElse(p.getPurchasePrice());
            PurchaseItem it = new PurchaseItem();
            it.setPurchaseOrder(po); it.setProduct(p); it.setOrderedQty(l.quantity()); it.setUnitCost(cost);
            po.getItems().add(it);
        }
    }

    /** Used by the reorder engine: OWNER gets an APPROVED PO immediately, others a PENDING_APPROVAL one. */
    @Transactional
    public PoDto createFromReorder(PoRequest r) {
        PoDto dto = create(r);
        PurchaseOrder po = find(dto.id());
        po.setStatus(PoStatus.PENDING_APPROVAL);
        if (cu.isOwner() && po.getSupplier().isApproved()) {
            po.setStatus(PoStatus.APPROVED); po.setApprovedBy(cu.require().getId()); po.setApprovedAt(Instant.now());
        } else notifications.poPendingApproval(po.getId(), po.getPoNumber(), po.getSupplier().getName());
        audit.log("REORDER_TO_PO", "PURCHASE_ORDER", po.getId(), null, Map.of("status", po.getStatus().name()));
        return toDto(po, false);
    }

    @Transactional
    public PoDto submit(Long id) {
        PurchaseOrder po = find(id);
        if (po.getStatus() != PoStatus.DRAFT) throw new BusinessException("Only DRAFT purchase orders can be submitted");
        if (po.getItems().isEmpty()) throw new BusinessException("Purchase order has no items");
        po.setStatus(PoStatus.PENDING_APPROVAL);
        notifications.poPendingApproval(po.getId(), po.getPoNumber(), po.getSupplier().getName());
        audit.log("SUBMIT", "PURCHASE_ORDER", id, Map.of("status", "DRAFT"), Map.of("status", "PENDING_APPROVAL"));
        return toDto(po, false);
    }

    @Transactional
    public PoDto approve(Long id) {
        PurchaseOrder po = find(id);
        if (po.getStatus() != PoStatus.PENDING_APPROVAL) throw new BusinessException("Only PENDING_APPROVAL purchase orders can be approved");
        if (!po.getSupplier().isApproved()) throw new BusinessException("Supplier is not approved yet - approve the supplier first");
        po.setStatus(PoStatus.APPROVED); po.setApprovedBy(cu.require().getId()); po.setApprovedAt(Instant.now());
        audit.log("APPROVE", "PURCHASE_ORDER", id, Map.of("status", "PENDING_APPROVAL"), Map.of("status", "APPROVED"));
        return toDto(po, false);
    }

    @Transactional
    public PoDto reject(Long id, String reason) {
        PurchaseOrder po = find(id);
        if (po.getStatus() != PoStatus.PENDING_APPROVAL) throw new BusinessException("Only PENDING_APPROVAL purchase orders can be rejected");
        po.setStatus(PoStatus.DRAFT);
        if (reason != null && !reason.isBlank()) po.setNotes(((po.getNotes() == null ? "" : po.getNotes() + " | ") + "Rejected: " + reason));
        audit.log("REJECT", "PURCHASE_ORDER", id, Map.of("status", "PENDING_APPROVAL"), Map.of("status", "DRAFT", "reason", String.valueOf(reason)));
        return toDto(po, false);
    }

    @Transactional
    public PoDto cancel(Long id) {
        PurchaseOrder po = pos.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Purchase order not found"));
        if (po.getStatus() == PoStatus.RECEIVED || po.getStatus() == PoStatus.PARTIALLY_RECEIVED || po.getStatus() == PoStatus.CANCELLED)
            throw new BusinessException("A purchase order that is received, partially received or cancelled cannot be cancelled");
        String before = po.getStatus().name();
        po.setStatus(PoStatus.CANCELLED);
        audit.log("CANCEL", "PURCHASE_ORDER", id, Map.of("status", before), Map.of("status", "CANCELLED"));
        return toDto(po, false);
    }

    @Transactional
    public PoDto receive(Long id, ReceiveRequest r) {
        PurchaseOrder po = pos.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Purchase order not found"));
        if (po.getStatus() != PoStatus.APPROVED && po.getStatus() != PoStatus.PARTIALLY_RECEIVED)
            throw new BusinessException("Only APPROVED or PARTIALLY_RECEIVED purchase orders can be received");
        User user = cu.require();
        Map<Long, PurchaseItem> byId = new HashMap<>();
        po.getItems().forEach(i -> byId.put(i.getId(), i));

        PurchaseReceipt rc = new PurchaseReceipt();
        rc.setPurchaseOrder(po); rc.setReceivedBy(user.getId()); rc.setNote(r.note());
        rc = receipts.save(rc);

        BigDecimal value = BigDecimal.ZERO; boolean any = false;
        for (ReceiveLine l : r.lines()) {
            PurchaseItem it = byId.get(l.itemId());
            if (it == null) throw new BusinessException("Line " + l.itemId() + " does not belong to this purchase order");
            BigDecimal rcv = nz(l.receivedQty()), dmg = nz(l.damagedQty());
            if (rcv.signum() == 0 && dmg.signum() == 0) continue;
            if (dmg.compareTo(rcv) > 0) throw new BusinessException("Damaged quantity cannot exceed received quantity for " + it.getProduct().getName());
            if (it.getReceivedQty().add(rcv).compareTo(it.getOrderedQty()) > 0)
                throw new BusinessException("Cannot receive more than ordered for " + it.getProduct().getName());
            BigDecimal good = rcv.subtract(dmg);
            it.setReceivedQty(it.getReceivedQty().add(rcv));
            it.setDamagedQty(it.getDamagedQty().add(dmg));
            if (good.signum() > 0) {
                StockMovement m = inventory.apply(it.getProduct().getId(), MovementType.PURCHASE_RECEIPT, good, "PURCHASE_RECEIPT", rc.getId(),
                    po.getPoNumber() + (dmg.signum() > 0 ? " (" + dmg.stripTrailingZeros().toPlainString() + " damaged, excluded)" : ""));
                m.getProduct().setPurchasePrice(it.getUnitCost());     // latest cost drives COGS for subsequent sales
            }
            value = value.add(good.multiply(it.getUnitCost()));
            PurchaseReceiptItem ri = new PurchaseReceiptItem();
            ri.setReceipt(rc); ri.setPurchaseItem(it); ri.setReceivedQty(rcv); ri.setDamagedQty(dmg); ri.setUnitCost(it.getUnitCost());
            rc.getItems().add(ri);
            any = true;
        }
        if (!any) throw new BusinessException("Enter at least one received quantity");
        value = value.setScale(2, RoundingMode.HALF_UP);
        rc.setTotalValue(value);

        Supplier supplier = suppliers.findByIdForUpdate(po.getSupplier().getId()).orElseThrow();
        if (value.signum() > 0) supplierService.recordPurchase(supplier, value, "PURCHASE_RECEIPT", rc.getId(), po.getPoNumber());

        BigDecimal paid = nz(r.paidAmount()).setScale(2, RoundingMode.HALF_UP);
        if (paid.signum() > 0) {
            if (user.getRole().getName().equals("STAFF")) throw new BusinessException("Only the owner or manager can record supplier payments");
            if (r.paymentMethod() == null) throw new BusinessException("Select a payment method");
            if (paid.compareTo(value) > 0) throw new BusinessException("Payment cannot exceed the received value (" + value + ")");
            supplierService.recordPayment(supplier, paid, r.paymentMethod(), rc.getId(), po.getPoNumber() + " payment");
            rc.setPaidAmount(paid); rc.setPaymentMethod(r.paymentMethod());
        }

        boolean complete = po.getItems().stream().allMatch(i -> i.getReceivedQty().compareTo(i.getOrderedQty()) >= 0);
        po.setStatus(complete ? PoStatus.RECEIVED : PoStatus.PARTIALLY_RECEIVED);
        audit.log("RECEIVE", "PURCHASE_ORDER", id, null, Map.of("receiptId", rc.getId(), "value", value, "paid", paid, "status", po.getStatus().name()));
        return toDto(po, true);
    }

    // ---------- mapping ----------
    private PoDto toDto(PurchaseOrder po, boolean withReceipts) {
        List<ItemDto> items = po.getItems().stream().map(i -> new ItemDto(i.getId(), i.getProduct().getId(), i.getProduct().getName(), i.getProduct().getSku(),
            i.getProduct().getUnit().name(), i.getOrderedQty(), i.getUnitCost(), i.getReceivedQty(), i.getDamagedQty())).toList();
        BigDecimal total = po.getItems().stream().map(i -> i.getOrderedQty().multiply(i.getUnitCost())).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        List<ReceiptDto> rs = List.of();
        if (withReceipts) {
            var list = receipts.findByPurchaseOrderIdOrderByReceivedAtDesc(po.getId());
            Map<Long, String> names = new HashMap<>();
            users.findAllById(list.stream().map(PurchaseReceipt::getReceivedBy).filter(Objects::nonNull).toList()).forEach(u -> names.put(u.getId(), u.getFullName()));
            rs = list.stream().map(rc -> new ReceiptDto(rc.getId(), rc.getReceivedAt(), names.get(rc.getReceivedBy()), rc.getTotalValue(), rc.getPaidAmount(),
                rc.getPaymentMethod(), rc.getNote(), rc.getItems().stream().map(x -> new ReceiptLineDto(x.getPurchaseItem().getProduct().getName(),
                    x.getReceivedQty(), x.getDamagedQty(), x.getUnitCost())).toList())).toList();
        }
        return new PoDto(po.getId(), po.getPoNumber(), po.getSupplier().getId(), po.getSupplier().getName(), po.getStatus(), po.getNotes(),
            po.getExpectedDate(), po.getCreatedAt(), total, items, rs);
    }
}
