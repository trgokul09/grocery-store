package com.grocery.finance;

import com.grocery.common.StoreClock;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * Database-driven financial figures.
 *   Net Revenue      = Sum(subtotal - discounts)      (tax collected is excluded - it is a liability, not income)
 *   Gross Profit     = Net Revenue - COGS             (COGS snapshotted per sale line at the time of sale)
 *   Operating Profit = Gross Profit - Operating Expenses
 * Supplier purchases are NOT expenses (they flow through inventory -> COGS), so P&L never double counts.
 */
@Service
public class FinanceService {
    public record Summary(BigDecimal totalSales, BigDecimal netRevenue, BigDecimal taxCollected, int billCount, BigDecimal cashSales, BigDecimal upiSales,
                          BigDecimal cogs, BigDecimal grossProfit, BigDecimal expenses, BigDecimal operatingProfit, BigDecimal purchases,
                          BigDecimal supplierOutstanding, BigDecimal inventoryValuation, int lowStockCount, int expiringCount,
                          BigDecimal cashBalance, BigDecimal upiBalance, List<Map<String, Object>> activeShifts) {}
    public record Dashboard(LocalDate from, LocalDate to, String group, Summary summary, List<Map<String, Object>> salesSeries,
                            List<Map<String, Object>> topItems, List<Map<String, Object>> slowMoving, List<Map<String, Object>> expenseBreakdown) {}
    public record Pnl(LocalDate from, LocalDate to, BigDecimal netRevenue, BigDecimal cogs, BigDecimal grossProfit, BigDecimal grossMarginPct,
                      List<Map<String, Object>> expensesByCategory, BigDecimal operatingExpenses, BigDecimal operatingProfit, BigDecimal taxCollected) {}

    private final NamedParameterJdbcTemplate jdbc; private final StoreClock clock;
    public FinanceService(NamedParameterJdbcTemplate jdbc, StoreClock clock) { this.jdbc = jdbc; this.clock = clock; }

    /** Resolves TODAY / WEEK / MONTH / CUSTOM into an inclusive local-date range. */
    public LocalDate[] resolve(String range, LocalDate from, LocalDate to) {
        LocalDate today = clock.today();
        return switch (range == null ? "TODAY" : range.toUpperCase()) {
            case "WEEK" -> new LocalDate[]{today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), today};
            case "MONTH" -> new LocalDate[]{today.withDayOfMonth(1), today};
            case "CUSTOM" -> {
                if (from == null || to == null || to.isBefore(from)) throw new com.grocery.common.Exceptions.BusinessException("Provide a valid from/to date range");
                yield new LocalDate[]{from, to};
            }
            default -> new LocalDate[]{today, today};
        };
    }

    private MapSqlParameterSource params(LocalDate from, LocalDate to) {
        return new MapSqlParameterSource()
            .addValue("from", clock.odt(clock.startOf(from))).addValue("to", clock.odt(clock.startOf(to.plusDays(1))))
            .addValue("fromDate", Date.valueOf(from)).addValue("toDate", Date.valueOf(to)).addValue("tz", clock.zone().getId());
    }

    private BigDecimal dec(String sql, MapSqlParameterSource p) {
        BigDecimal v = jdbc.queryForObject(sql, p, BigDecimal.class);
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard(LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        String group = days <= 35 ? "day" : days <= 200 ? "week" : "month";
        return new Dashboard(from, to, group, summary(from, to), salesSeries(from, to, group), topItems(from, to, 10), slowMoving(30), expenseBreakdown(from, to));
    }

    @Transactional(readOnly = true)
    public Summary summary(LocalDate from, LocalDate to) {
        MapSqlParameterSource p = params(from, to);
        Map<String, Object> s = jdbc.queryForMap("select coalesce(sum(total),0) t, coalesce(sum(subtotal - discount_total),0) net, coalesce(sum(tax_total),0) tax, "
            + "coalesce(sum(cogs),0) cogs, count(*) c from sales where created_at >= :from and created_at < :to", p);
        BigDecimal totalSales = (BigDecimal) s.get("t"), net = (BigDecimal) s.get("net"), tax = (BigDecimal) s.get("tax"), cogs = (BigDecimal) s.get("cogs");
        BigDecimal cash = dec("select coalesce(sum(sp.amount),0) from sale_payments sp join sales s on s.id = sp.sale_id where sp.method='CASH' and s.created_at >= :from and s.created_at < :to", p);
        BigDecimal upi = dec("select coalesce(sum(sp.amount),0) from sale_payments sp join sales s on s.id = sp.sale_id where sp.method='UPI' and s.created_at >= :from and s.created_at < :to", p);
        BigDecimal expenses = dec("select coalesce(sum(amount),0) from expenses where expense_date >= :fromDate and expense_date <= :toDate", p);
        BigDecimal purchases = dec("select coalesce(sum(total_value),0) from purchase_receipts where received_at >= :from and received_at < :to", p);
        BigDecimal gross = net.subtract(cogs);

        MapSqlParameterSource none = new MapSqlParameterSource();
        BigDecimal outstanding = dec("select (select coalesce(sum(opening_balance),0) from suppliers) + (select coalesce(sum(case when entry_type in ('PURCHASE','DEBIT_ADJUSTMENT') then amount else -amount end),0) from supplier_ledger)", none);
        BigDecimal valuation = dec("select coalesce(sum(current_stock * purchase_price),0) from products", none);
        Integer low = jdbc.queryForObject("select count(*) from products where is_active and current_stock <= min_stock_level", none, Integer.class);
        MapSqlParameterSource exp = new MapSqlParameterSource("limit", Date.valueOf(clock.today().plusDays(7)));
        Integer expiring = jdbc.queryForObject("select count(*) from products where is_active and current_stock > 0 and expiry_date is not null and expiry_date <= :limit", exp, Integer.class);

        // all-time channel balances derived from ledger streams (sales in, expenses/supplier payments out)
        BigDecimal cashBal = channelBalance("CASH"), upiBal = channelBalance("UPI");
        List<Map<String, Object>> shifts = jdbc.queryForList("select s.id, u.full_name as \"fullName\", s.opened_at as \"openedAt\", s.opening_cash as \"openingCash\" from shifts s join users u on u.id = s.user_id where s.status = 'OPEN' order by s.opened_at", none);

        return new Summary(totalSales.setScale(2, RoundingMode.HALF_UP), net.setScale(2, RoundingMode.HALF_UP), tax.setScale(2, RoundingMode.HALF_UP), ((Number) s.get("c")).intValue(),
            cash, upi, cogs.setScale(2, RoundingMode.HALF_UP), gross.setScale(2, RoundingMode.HALF_UP), expenses, gross.subtract(expenses).setScale(2, RoundingMode.HALF_UP),
            purchases, outstanding, valuation, low == null ? 0 : low, expiring == null ? 0 : expiring, cashBal, upiBal, shifts);
    }

    private BigDecimal channelBalance(String m) {
        MapSqlParameterSource p = new MapSqlParameterSource("m", m);
        return dec("select (select coalesce(sum(amount),0) from sale_payments where method = :m) - (select coalesce(sum(amount),0) from expenses where method = :m) "
            + "- (select coalesce(sum(amount),0) from supplier_payments where method = :m)", p);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> salesSeries(LocalDate from, LocalDate to, String group) {
        String g = switch (group) { case "week", "month" -> group; default -> "day"; };
        List<Map<String, Object>> rows = jdbc.queryForList("select to_char(date_trunc('" + g + "', timezone(:tz, created_at)), 'YYYY-MM-DD') as bucket, "
            + "coalesce(sum(total),0) as sales, coalesce(sum(subtotal - discount_total - cogs),0) as profit, count(*) as bills from sales "
            + "where created_at >= :from and created_at < :to group by 1 order by 1", params(from, to));
        if (!g.equals("day")) return rows;
        Map<String, Map<String, Object>> byDay = new HashMap<>();
        rows.forEach(r -> byDay.put((String) r.get("bucket"), r));
        List<Map<String, Object>> filled = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            Map<String, Object> r = byDay.get(d.toString());
            if (r == null) {
                r = new LinkedHashMap<String, Object>();
                r.put("bucket", d.toString()); r.put("sales", BigDecimal.ZERO); r.put("profit", BigDecimal.ZERO); r.put("bills", 0L);
            }
            filled.add(r);
        }
        return filled;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> topItems(LocalDate from, LocalDate to, int limit) {
        return jdbc.queryForList("select si.product_id as \"productId\", si.product_name as name, sum(si.quantity) as quantity, sum(si.line_total) as revenue "
            + "from sale_items si join sales s on s.id = si.sale_id where s.created_at >= :from and s.created_at < :to "
            + "group by si.product_id, si.product_name order by sum(si.quantity) desc limit " + limit, params(from, to));
    }

    /** Active products with stock that have not sold at all during the last N days. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> slowMoving(int days) {
        MapSqlParameterSource p = new MapSqlParameterSource("cutoff", clock.odt(Instant.now().minus(days, ChronoUnit.DAYS)));
        return jdbc.queryForList("select p.id, p.name, p.sku, p.current_stock as \"currentStock\", round(p.current_stock * p.purchase_price, 2) as value, "
            + "(select max(s.created_at) from sale_items si join sales s on s.id = si.sale_id where si.product_id = p.id) as \"lastSold\" "
            + "from products p where p.is_active and p.current_stock > 0 and p.created_at < :cutoff "
            + "and not exists (select 1 from sale_items si join sales s on s.id = si.sale_id where si.product_id = p.id and s.created_at >= :cutoff) "
            + "order by p.current_stock * p.purchase_price desc limit 20", p);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> expenseBreakdown(LocalDate from, LocalDate to) {
        return jdbc.queryForList("select category, sum(amount) as amount from expenses where expense_date >= :fromDate and expense_date <= :toDate group by category order by sum(amount) desc", params(from, to));
    }

    @Transactional(readOnly = true)
    public Pnl pnl(LocalDate from, LocalDate to) {
        Summary s = summary(from, to);
        BigDecimal margin = s.netRevenue().signum() == 0 ? BigDecimal.ZERO : s.grossProfit().multiply(new BigDecimal("100")).divide(s.netRevenue(), 2, RoundingMode.HALF_UP);
        return new Pnl(from, to, s.netRevenue(), s.cogs(), s.grossProfit(), margin, expenseBreakdown(from, to), s.expenses(), s.operatingProfit(), s.taxCollected());
    }
}
