package com.grocery.supplier;

import com.grocery.audit.AuditService;
import com.grocery.common.CurrentUser;
import com.grocery.common.Exceptions.*;
import com.grocery.common.PaymentMethod;
import com.grocery.product.ProductRepository;
import com.grocery.shift.ShiftRepository;
import com.grocery.shift.ShiftStatus;
import com.grocery.supplier.SupplierDtos.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;

@Service
public class SupplierService {
    private final SupplierRepository suppliers; private final SupplierProductRepository supplierProducts;
    private final SupplierLedgerRepository ledger; private final SupplierPaymentRepository payments;
    private final ProductRepository products; private final ShiftRepository shifts; private final CurrentUser cu; private final AuditService audit;

    public SupplierService(SupplierRepository s, SupplierProductRepository sp, SupplierLedgerRepository l, SupplierPaymentRepository p,
                           ProductRepository pr, ShiftRepository sh, CurrentUser cu, AuditService a) {
        suppliers = s; supplierProducts = sp; ledger = l; payments = p; products = pr; shifts = sh; this.cu = cu; audit = a;
    }

    /** Outstanding = Opening Balance + Credit Purchases + Debit Adj - Payments - Credit Adj */
    public BigDecimal outstanding(Supplier s) { return s.getOpeningBalance().add(ledger.netMovement(s.getId())); }

    private SupplierDto toDto(Supplier s) {
        return new SupplierDto(s.getId(), s.getName(), s.getContactPerson(), s.getPhone(), s.getEmail(), s.getAddress(), s.getPaymentTermsDays(),
            s.getOpeningBalance(), s.isApproved(), s.isActive(), outstanding(s));
    }

    private Supplier find(Long id) { return suppliers.findById(id).orElseThrow(() -> new NotFoundException("Supplier not found")); }

    @Transactional(readOnly = true)
    public List<SupplierDto> list() { return suppliers.findAllByOrderByNameAsc().stream().map(this::toDto).toList(); }

    @Transactional(readOnly = true)
    public SupplierDto get(Long id) { return toDto(find(id)); }

    @Transactional
    public SupplierDto create(SupplierRequest r) {
        Supplier s = new Supplier();
        apply(s, r);
        boolean owner = cu.isOwner();
        s.setOpeningBalance(owner && r.openingBalance() != null ? r.openingBalance() : BigDecimal.ZERO);
        s.setApproved(owner);                       // supplier approvals are an OWNER decision
        if (owner) s.setApprovedBy(cu.require().getId());
        s = suppliers.save(s);
        SupplierDto dto = toDto(s);
        audit.log("CREATE", "SUPPLIER", s.getId(), null, dto);
        return dto;
    }

    @Transactional
    public SupplierDto update(Long id, SupplierRequest r) {
        Supplier s = find(id);
        SupplierDto before = toDto(s);
        apply(s, r);
        if (cu.isOwner() && r.openingBalance() != null) s.setOpeningBalance(r.openingBalance());
        SupplierDto after = toDto(s);
        audit.log("UPDATE", "SUPPLIER", id, before, after);
        return after;
    }

    private void apply(Supplier s, SupplierRequest r) {
        s.setName(r.name().trim()); s.setContactPerson(r.contactPerson()); s.setPhone(r.phone()); s.setEmail(r.email());
        s.setAddress(r.address()); s.setPaymentTermsDays(r.paymentTermsDays() == null ? 0 : r.paymentTermsDays());
    }

    @Transactional
    public SupplierDto approve(Long id) {
        Supplier s = find(id);
        s.setApproved(true); s.setApprovedBy(cu.require().getId());
        audit.log("APPROVE", "SUPPLIER", id, Map.of("approved", false), Map.of("approved", true));
        return toDto(s);
    }

    @Transactional
    public SupplierDto setActive(Long id, boolean active) {
        Supplier s = find(id); s.setActive(active);
        audit.log(active ? "ACTIVATE" : "DEACTIVATE", "SUPPLIER", id, null, Map.of("active", active));
        return toDto(s);
    }

    @Transactional(readOnly = true)
    public LedgerStatement statement(Long id) {
        Supplier s = find(id);
        BigDecimal running = s.getOpeningBalance();
        List<LedgerRow> rows = new ArrayList<>();
        for (SupplierLedgerEntry e : ledger.findBySupplierIdOrderByCreatedAtAscIdAsc(id)) {
            boolean up = e.getEntryType() == LedgerType.PURCHASE || e.getEntryType() == LedgerType.DEBIT_ADJUSTMENT;
            running = up ? running.add(e.getAmount()) : running.subtract(e.getAmount());
            rows.add(new LedgerRow(e.getId(), e.getCreatedAt(), e.getEntryType(), e.getAmount(), e.getRefType(), e.getRefId(), e.getNote(), running));
        }
        return new LedgerStatement(toDto(s), rows);
    }

    /** Called by the receiving engine: a credit purchase raises the payable. */
    @Transactional
    public void recordPurchase(Supplier s, BigDecimal amount, String refType, Long refId, String note) {
        addEntry(s, LedgerType.PURCHASE, amount, refType, refId, note);
    }

    /** Records a supplier payment (cash or UPI) and its ledger entry. Overpayment is rejected. */
    @Transactional
    public SupplierPayment recordPayment(Supplier s, BigDecimal amount, PaymentMethod method, Long receiptId, String note) {
        if (amount.signum() <= 0) throw new BusinessException("Payment must be greater than zero");
        if (amount.compareTo(outstanding(s)) > 0) throw new BusinessException("Payment exceeds the supplier's outstanding balance");
        var user = cu.require();
        SupplierPayment draft = new SupplierPayment();
        draft.setSupplier(s); draft.setAmount(amount); draft.setMethod(method); draft.setReceiptId(receiptId); draft.setNote(note); draft.setCreatedBy(user.getId());
        draft.setShiftId(shifts.findByUserIdAndStatus(user.getId(), ShiftStatus.OPEN).map(sh -> sh.getId()).orElse(null));
        SupplierPayment saved = payments.save(draft);
        addEntry(s, LedgerType.PAYMENT, amount, "SUPPLIER_PAYMENT", saved.getId(), note == null ? method.name() + " payment" : note);
        return saved;
    }

    @Transactional
    public SupplierDto pay(Long id, PaymentRequest r) {
        Supplier s = suppliers.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Supplier not found"));
        recordPayment(s, r.amount(), r.method(), null, r.note());
        audit.log("PAYMENT", "SUPPLIER", id, null, Map.of("amount", r.amount(), "method", r.method().name()));
        return toDto(s);
    }

    @Transactional
    public SupplierDto adjust(Long id, AdjustmentRequest r) {
        Supplier s = suppliers.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Supplier not found"));
        boolean debit = r.kind() == AdjustmentKind.DEBIT;
        if (!debit && r.amount().compareTo(outstanding(s)) > 0) throw new BusinessException("Credit adjustment exceeds outstanding balance");
        addEntry(s, debit ? LedgerType.DEBIT_ADJUSTMENT : LedgerType.CREDIT_ADJUSTMENT, r.amount(), "ADJUSTMENT", null, r.note());
        audit.log("ADJUSTMENT", "SUPPLIER", id, null, Map.of("kind", r.kind().name(), "amount", r.amount(), "note", r.note()));
        return toDto(s);
    }

    private void addEntry(Supplier s, LedgerType t, BigDecimal amount, String refType, Long refId, String note) {
        SupplierLedgerEntry e = new SupplierLedgerEntry();
        e.setSupplier(s); e.setEntryType(t); e.setAmount(amount); e.setRefType(refType); e.setRefId(refId); e.setNote(note);
        cu.find().ifPresent(u -> e.setCreatedBy(u.getId()));
        ledger.save(e);
    }

    // ---- supplier-product mapping ----
    @Transactional(readOnly = true)
    public List<SupplierProductDto> products(Long supplierId) {
        return supplierProducts.findBySupplierId(supplierId).stream().map(sp -> new SupplierProductDto(sp.getId(), sp.getProduct().getId(),
            sp.getProduct().getName(), sp.getProduct().getSku(), sp.getSupplierPrice(), sp.getLeadTimeDays(), sp.isPreferred())).toList();
    }

    @Transactional
    public List<SupplierProductDto> mapProduct(Long supplierId, SupplierProductRequest r) {
        Supplier s = find(supplierId);
        var product = products.findById(r.productId()).orElseThrow(() -> new NotFoundException("Product not found"));
        SupplierProduct sp = supplierProducts.findBySupplierIdAndProductId(supplierId, r.productId()).orElseGet(SupplierProduct::new);
        sp.setSupplier(s); sp.setProduct(product); sp.setSupplierPrice(r.supplierPrice());
        sp.setLeadTimeDays(r.leadTimeDays() == null ? 3 : r.leadTimeDays());
        sp.setPreferred(r.preferred() != null && r.preferred());
        supplierProducts.save(sp);
        audit.log("MAP_PRODUCT", "SUPPLIER", supplierId, null, Map.of("productId", r.productId()));
        return products(supplierId);
    }

    @Transactional
    public List<SupplierProductDto> unmapProduct(Long supplierId, Long mappingId) {
        supplierProducts.findById(mappingId).filter(sp -> sp.getSupplier().getId().equals(supplierId)).ifPresent(supplierProducts::delete);
        return products(supplierId);
    }
}
