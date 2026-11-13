package com.grocery.reorder;

import com.grocery.common.Exceptions.*;
import com.grocery.product.Product;
import com.grocery.product.ProductRepository;
import com.grocery.purchase.PurchaseDtos.*;
import com.grocery.purchase.PurchaseService;
import com.grocery.supplier.Supplier;
import com.grocery.supplier.SupplierProduct;
import com.grocery.supplier.SupplierProductRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Reorder Point = (Avg Daily Sales * Lead Time) + Safety Stock
 *   avg daily sales = units sold in the last 30 days / 30
 *   lead time       = supplier-product lead time of the preferred (else cheapest) supplier, default 3 days
 *   safety stock    = the product's min stock level
 * A product is recommended when stock <= reorder point. Suggested qty tops stock up to cover (lead time + 7 days) of sales + safety stock.
 */
@Service
public class ReorderService {
    private static final int LOOKBACK_DAYS = 30, REVIEW_DAYS = 7, DEFAULT_LEAD = 3;

    public record Item(Long productId, String name, String sku, String unit, BigDecimal currentStock, BigDecimal avgDailySales, int leadTimeDays,
                       BigDecimal safetyStock, BigDecimal reorderPoint, BigDecimal suggestedQty, BigDecimal unitCost) {}
    public record Group(Long supplierId, String supplierName, boolean supplierApproved, List<Item> items, BigDecimal estimatedTotal) {}
    public record ConvertLine(@NotNull Long productId, @NotNull @DecimalMin("0.001") BigDecimal quantity, @DecimalMin("0.00") BigDecimal unitCost) {}
    public record ConvertRequest(@NotNull Long supplierId, @NotEmpty @Valid List<ConvertLine> items) {}

    private final ProductRepository products; private final SupplierProductRepository supplierProducts;
    private final NamedParameterJdbcTemplate jdbc; private final PurchaseService purchases;

    public ReorderService(ProductRepository p, SupplierProductRepository sp, NamedParameterJdbcTemplate j, PurchaseService ps) { products = p; supplierProducts = sp; jdbc = j; purchases = ps; }

    @Transactional(readOnly = true)
    public List<Group> recommendations() {
        Map<Long, BigDecimal> sold = new HashMap<>();
        jdbc.query("select si.product_id, sum(si.quantity) q from sale_items si join sales s on s.id = si.sale_id where s.created_at >= :since group by si.product_id",
            new MapSqlParameterSource("since", OffsetDateTime.ofInstant(Instant.now().minusSeconds(LOOKBACK_DAYS * 86400L), ZoneOffset.UTC)),
            rs -> { sold.put(rs.getLong(1), rs.getBigDecimal(2)); });

        List<Product> active = products.findAll().stream().filter(Product::isActive).toList();
        Map<Long, List<SupplierProduct>> mappings = new HashMap<>();
        if (!active.isEmpty())
            supplierProducts.findByProductIdIn(active.stream().map(Product::getId).toList()).stream()
                .filter(sp -> sp.getSupplier().isActive())
                .forEach(sp -> mappings.computeIfAbsent(sp.getProduct().getId(), k -> new ArrayList<>()).add(sp));

        Map<Long, List<Item>> byGroup = new LinkedHashMap<>();
        Map<Long, Supplier> supplierById = new HashMap<>();
        for (Product p : active) {
            SupplierProduct best = mappings.getOrDefault(p.getId(), List.of()).stream()
                .min(Comparator.comparing((SupplierProduct sp) -> !sp.isPreferred())
                    .thenComparing(sp -> sp.getSupplierPrice() == null ? BigDecimal.valueOf(Long.MAX_VALUE) : sp.getSupplierPrice())).orElse(null);
            int lead = best == null ? DEFAULT_LEAD : best.getLeadTimeDays();
            BigDecimal avg = sold.getOrDefault(p.getId(), BigDecimal.ZERO).divide(BigDecimal.valueOf(LOOKBACK_DAYS), 3, RoundingMode.HALF_UP);
            BigDecimal safety = p.getMinStockLevel();
            BigDecimal rop = avg.multiply(BigDecimal.valueOf(lead)).add(safety).setScale(3, RoundingMode.HALF_UP);
            if (p.getCurrentStock().compareTo(rop) > 0) continue;
            BigDecimal target = avg.multiply(BigDecimal.valueOf(lead + REVIEW_DAYS)).add(safety);
            BigDecimal qty = target.subtract(p.getCurrentStock());
            if (qty.compareTo(BigDecimal.ONE) < 0) qty = BigDecimal.ONE;
            qty = qty.setScale(0, RoundingMode.CEILING);
            BigDecimal cost = best != null && best.getSupplierPrice() != null ? best.getSupplierPrice() : p.getPurchasePrice();
            long key = best == null ? 0L : best.getSupplier().getId();
            if (best != null) supplierById.put(key, best.getSupplier());
            byGroup.computeIfAbsent(key, k -> new ArrayList<>()).add(new Item(p.getId(), p.getName(), p.getSku(), p.getUnit().name(), p.getCurrentStock(), avg, lead,
                safety, rop, qty, cost));
        }
        List<Group> out = new ArrayList<>();
        byGroup.forEach((sid, items) -> {
            Supplier s = supplierById.get(sid);
            BigDecimal total = items.stream().map(i -> i.suggestedQty().multiply(i.unitCost())).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
            items.sort(Comparator.comparing(Item::name));
            out.add(new Group(s == null ? null : s.getId(), s == null ? "No supplier mapped" : s.getName(), s != null && s.isApproved(), items, total));
        });
        out.sort(Comparator.comparing((Group g) -> g.supplierId() == null).thenComparing(Group::supplierName));
        return out;
    }

    /** One-click: turn a supplier's recommendation group into an official purchase order. */
    @Transactional
    public PoDto convert(ConvertRequest r) {
        List<PoLineRequest> lines = r.items().stream().map(i -> new PoLineRequest(i.productId(), i.quantity(), i.unitCost())).toList();
        return purchases.createFromReorder(new PoRequest(r.supplierId(), null, "Generated from reorder recommendations", lines));
    }
}
