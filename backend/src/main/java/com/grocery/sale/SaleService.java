package com.grocery.sale;

import com.grocery.audit.AuditService;
import com.grocery.common.CurrentUser;
import com.grocery.common.Exceptions.*;
import com.grocery.common.StoreClock;
import com.grocery.inventory.InventoryService;
import com.grocery.inventory.MovementType;
import com.grocery.inventory.StockMovement;
import com.grocery.product.Product;
import com.grocery.product.Unit;
import com.grocery.sale.SaleDtos.*;
import com.grocery.shift.Shift;
import com.grocery.shift.ShiftRepository;
import com.grocery.shift.ShiftStatus;
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
import java.time.LocalDate;
import java.util.*;

@Service
public class SaleService {
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final SaleRepository sales; private final InventoryService inventory; private final ShiftRepository shifts;
    private final CurrentUser cu; private final UserRepository users; private final AuditService audit; private final EntityManager em; private final StoreClock clock;

    public SaleService(SaleRepository sales, InventoryService inventory, ShiftRepository shifts, CurrentUser cu, UserRepository users,
                       AuditService audit, EntityManager em, StoreClock clock) {
        this.sales = sales; this.inventory = inventory; this.shifts = shifts; this.cu = cu; this.users = users; this.audit = audit; this.em = em; this.clock = clock;
    }

    /**
     * Atomic checkout. Everything below runs in ONE transaction - any failure (insufficient stock, bad payment total,
     * inactive product...) rolls back the sale, its items, payments, stock deductions and movements together.
     * Products are locked in ascending id order (PESSIMISTIC_WRITE) so concurrent carts cannot deadlock.
     */
    @Transactional
    public SaleDto checkout(CheckoutRequest r) {
        User user = cu.require();
        Shift shift = shifts.findByUserIdAndStatus(user.getId(), ShiftStatus.OPEN).orElse(null);
        if (shift == null && user.getRole().getName().equals("STAFF")) throw new BusinessException("Open your shift before billing");

        // merge duplicate cart lines, then sort by product id for a deterministic lock order
        TreeMap<Long, CartLine> lines = new TreeMap<>();
        for (CartLine l : r.items())
            lines.merge(l.productId(), l, (a, b) -> new CartLine(a.productId(), a.quantity().add(b.quantity()),
                (a.discount() == null ? BigDecimal.ZERO : a.discount()).add(b.discount() == null ? BigDecimal.ZERO : b.discount())));

        Sale sale = new Sale();
        sale.setInvoiceNo(nextInvoice()); sale.setCashierId(user.getId()); sale.setShiftId(shift == null ? null : shift.getId());
        sale.setCustomerName(r.customerName() == null || r.customerName().isBlank() ? null : r.customerName().trim());
        sale = sales.save(sale);   // id needed as the stock-movement reference

        BigDecimal subtotal = BigDecimal.ZERO, discountTotal = BigDecimal.ZERO, taxTotal = BigDecimal.ZERO, total = BigDecimal.ZERO, cogs = BigDecimal.ZERO;
        for (CartLine l : lines.values()) {
            StockMovement m = inventory.apply(l.productId(), MovementType.SALE, l.quantity(), "SALE", sale.getId(), "POS " + sale.getInvoiceNo());
            Product p = m.getProduct();
            if (!p.isActive()) throw new BusinessException("Product is inactive: " + p.getName());
            if ((p.getUnit() == Unit.PCS || p.getUnit() == Unit.PKTS || p.getUnit() == Unit.BOXES) && l.quantity().stripTrailingZeros().scale() > 0)
                throw new BusinessException(p.getName() + " must be sold in whole " + p.getUnit().name().toLowerCase());

            BigDecimal gross = p.getSellingPrice().multiply(l.quantity()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal discount = (l.discount() == null ? BigDecimal.ZERO : l.discount()).setScale(2, RoundingMode.HALF_UP);
            if (discount.compareTo(gross) > 0) throw new BusinessException("Discount exceeds line amount for " + p.getName());
            BigDecimal net = gross.subtract(discount);
            BigDecimal tax = BigDecimal.ZERO;   // no tax: customer pays the plain selling price
            BigDecimal lineTotal = net.add(tax);

            SaleItem it = new SaleItem();
            it.setSale(sale); it.setProduct(p); it.setProductName(p.getName()); it.setSku(p.getSku()); it.setQuantity(l.quantity());
            it.setUnitPrice(p.getSellingPrice()); it.setCostPrice(p.getPurchasePrice()); it.setDiscount(discount);
            it.setTaxRate(BigDecimal.ZERO); it.setTaxAmount(tax); it.setLineTotal(lineTotal);
            sale.getItems().add(it);

            subtotal = subtotal.add(gross); discountTotal = discountTotal.add(discount); taxTotal = taxTotal.add(tax); total = total.add(lineTotal);
            cogs = cogs.add(p.getPurchasePrice().multiply(l.quantity()));
        }

        BigDecimal paid = BigDecimal.ZERO;
        for (PaymentLine pl : r.payments()) {
            SalePayment sp = new SalePayment();
            sp.setSale(sale); sp.setMethod(pl.method()); sp.setAmount(pl.amount().setScale(2, RoundingMode.HALF_UP));
            sale.getPayments().add(sp);
            paid = paid.add(sp.getAmount());
        }
        if (paid.compareTo(total) != 0)
            throw new BusinessException("Payments (" + paid + ") must equal the bill total (" + total + ")");

        sale.setSubtotal(subtotal); sale.setDiscountTotal(discountTotal); sale.setTaxTotal(taxTotal); sale.setTotal(total);
        sale.setCogs(cogs.setScale(2, RoundingMode.HALF_UP));
        audit.log("SALE", "SALE", sale.getId(), null, Map.of("invoice", sale.getInvoiceNo(), "total", total, "items", lines.size()));
        return toDto(sale, user.getFullName());
    }

    private String nextInvoice() {
        Number n = (Number) em.createNativeQuery("select nextval('invoice_seq')").getSingleResult();
        return "INV-" + clock.today().toString().replace("-", "") + "-" + String.format("%05d", n.longValue());
    }

    @Transactional(readOnly = true)
    public SaleDto get(Long id) {
        Sale s = sales.findById(id).orElseThrow(() -> new NotFoundException("Sale not found"));
        if (cu.isStaff() && !s.getCashierId().equals(cu.require().getId())) throw new NotFoundException("Sale not found");
        return toDto(s, name(s.getCashierId()));
    }

    @Transactional(readOnly = true)
    public Page<SaleDto> search(LocalDate from, LocalDate to, String invoice, Pageable pg) {
        boolean staff = cu.isStaff(); Long uid = cu.require().getId();
        LocalDate effFrom = staff ? clock.today() : from;     // staff only see their own sales of today
        Specification<Sale> spec = (root, q, cb) -> {
            var p = new ArrayList<Predicate>();
            if (staff) p.add(cb.equal(root.get("cashierId"), uid));
            if (effFrom != null) p.add(cb.greaterThanOrEqualTo(root.<Instant>get("createdAt"), clock.startOf(effFrom)));
            if (to != null) p.add(cb.lessThan(root.<Instant>get("createdAt"), clock.startOf(to.plusDays(1))));
            if (invoice != null && !invoice.isBlank()) p.add(cb.like(cb.lower(root.get("invoiceNo")), "%" + invoice.trim().toLowerCase() + "%"));
            return cb.and(p.toArray(new Predicate[0]));
        };
        Map<Long, String> cache = new HashMap<>();
        return sales.findAll(spec, pg).map(s -> toDto(s, cache.computeIfAbsent(s.getCashierId(), this::name)));
    }

    /** Quick-access grid: best sellers of the last 30 days, padded with other in-stock active products. */
    @Transactional(readOnly = true)
    public List<Product> quickGrid(int limit, com.grocery.product.ProductRepository products) {
        List<Long> ids = sales.topProductIds(Instant.now().minusSeconds(30L * 86400), PageRequest.of(0, limit * 2));
        List<Product> out = new ArrayList<>();
        for (Long id : ids) {
            if (out.size() >= limit) break;
            products.findById(id).filter(p -> p.isActive() && p.getCurrentStock().signum() > 0).ifPresent(out::add);
        }
        if (out.size() < limit) {
            Set<Long> have = new HashSet<>(); out.forEach(p -> have.add(p.getId()));
            for (Product p : products.findAll(Sort.by("name")))
                if (out.size() < limit && p.isActive() && p.getCurrentStock().signum() > 0 && have.add(p.getId())) out.add(p);
        }
        return out;
    }

    private String name(Long uid) { return users.findById(uid).map(User::getFullName).orElse("?"); }

    private SaleDto toDto(Sale s, String cashier) {
        return new SaleDto(s.getId(), s.getInvoiceNo(), s.getCreatedAt(), cashier, s.getCustomerName(), s.getSubtotal(), s.getDiscountTotal(), s.getTaxTotal(), s.getTotal(),
            s.getItems().stream().map(i -> new SaleItemDto(i.getProduct().getId(), i.getProductName(), i.getSku(), i.getQuantity(), i.getUnitPrice(), i.getDiscount(),
                i.getTaxRate(), i.getTaxAmount(), i.getLineTotal())).toList(),
            s.getPayments().stream().map(p -> new SalePaymentDto(p.getMethod(), p.getAmount())).toList());
    }
}
