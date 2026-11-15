package com.grocery.ai;

import com.grocery.ai.AiDtos.NavAction;
import com.grocery.ai.AiDtos.TablePayload;
import com.grocery.ai.GroceryBusinessToolService.ToolExecutionResult;
import com.grocery.ai.PeriodResolver.Period;
import com.grocery.analytics.AnalyticsDtos.AnalyticsDashboardResponse;
import com.grocery.analytics.AnalyticsDtos.ProductPerformanceDto;
import com.grocery.analytics.AnalyticsService;
import com.grocery.common.StoreClock;
import com.grocery.finance.FinanceService;
import com.grocery.hr.HrDtos.HrDashboardResponse;
import com.grocery.hr.HrService;
import com.grocery.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Types;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * Turns live PostgreSQL records into plain-language business explanations and evidence-backed advice.
 * Uses the same net-revenue rules as Sales Analytics: cancelled bills are excluded, refunds and item returns are deducted.
 * Every number in every sentence comes from a query below - nothing is estimated or invented.
 */
@Service
public class BusinessInsightService {

    private static final Logger log = LoggerFactory.getLogger(BusinessInsightService.class);

    private static final String SALE_OK = "COALESCE(s.status, 'COMPLETED') <> 'CANCELLED'";
    private static final String NET_SALE = "GREATEST(s.total - COALESCE(s.refunded_amount, 0), 0)";
    private static final String NET_QTY = "GREATEST(si.quantity - COALESCE(si.returned_qty, 0), 0)";
    private static final String NET_REV =
        "(CASE WHEN si.quantity > 0 THEN si.line_total * (" + NET_QTY + " / si.quantity) ELSE 0 END) * " +
        "(CASE WHEN s.total > 0 THEN GREATEST(s.total - COALESCE(s.refunded_amount, 0), 0) / s.total ELSE 1 END)";
    private static final String NET_COGS = "(si.cost_price * " + NET_QTY + ")";
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final NamedParameterJdbcTemplate jdbc;
    private final StoreClock clock;
    private final AnalyticsService analyticsService;
    private final FinanceService financeService;
    private final HrService hrService;

    public BusinessInsightService(NamedParameterJdbcTemplate jdbc, StoreClock clock, AnalyticsService analyticsService,
                                  FinanceService financeService, HrService hrService) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.analyticsService = analyticsService;
        this.financeService = financeService;
        this.hrService = hrService;
    }

    // ---------------------------------------------------------------------------------------------
    // Data primitives
    // ---------------------------------------------------------------------------------------------

    record Snapshot(BigDecimal sales, long bills, BigDecimal refunds, BigDecimal lineRevenue, BigDecimal cogs, BigDecimal qty) {
        BigDecimal avgBill() { return bills == 0 ? BigDecimal.ZERO : sales.divide(BigDecimal.valueOf(bills), 2, RoundingMode.HALF_UP); }
        BigDecimal grossProfit() { return lineRevenue.subtract(cogs); }
        BigDecimal marginPct() { return lineRevenue.signum() == 0 ? BigDecimal.ZERO : grossProfit().multiply(HUNDRED).divide(lineRevenue, 1, RoundingMode.HALF_UP); }
    }

    private MapSqlParameterSource range(LocalDate from, LocalDate to, Long cashierId) {
        return new MapSqlParameterSource()
            .addValue("from", clock.odt(clock.startOf(from)), Types.TIMESTAMP_WITH_TIMEZONE)
            .addValue("to", clock.odt(clock.startOf(to.plusDays(1))), Types.TIMESTAMP_WITH_TIMEZONE)
            .addValue("fromDate", Date.valueOf(from), Types.DATE)
            .addValue("toDate", Date.valueOf(to), Types.DATE)
            .addValue("uid", cashierId, Types.BIGINT);
    }

    private static String cashierFilter(Long cashierId) { return cashierId == null ? "" : " AND s.cashier_id = :uid"; }

    Snapshot snapshot(LocalDate from, LocalDate to, Long cashierId) {
        MapSqlParameterSource p = range(from, to, cashierId);
        Map<String, Object> h = jdbc.queryForMap(
            "SELECT COALESCE(SUM(" + NET_SALE + "), 0) AS sales, COUNT(*) AS bills, COALESCE(SUM(COALESCE(s.refunded_amount, 0)), 0) AS refunds " +
            "FROM sales s WHERE " + SALE_OK + " AND s.created_at >= :from AND s.created_at < :to" + cashierFilter(cashierId), p);
        Map<String, Object> l = jdbc.queryForMap(
            "SELECT COALESCE(SUM(" + NET_REV + "), 0) AS rev, COALESCE(SUM(" + NET_COGS + "), 0) AS cogs, COALESCE(SUM(" + NET_QTY + "), 0) AS qty " +
            "FROM sale_items si JOIN sales s ON s.id = si.sale_id WHERE " + SALE_OK + " AND s.created_at >= :from AND s.created_at < :to" + cashierFilter(cashierId), p);
        return new Snapshot(money(h.get("sales")), ((Number) h.get("bills")).longValue(), money(h.get("refunds")),
            money(l.get("rev")), money(l.get("cogs")), bd(l.get("qty")));
    }

    /** product name -> [revenue, qty] for the range, ordered by revenue desc. */
    LinkedHashMap<String, BigDecimal[]> productRevenue(LocalDate from, LocalDate to, Long cashierId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT si.product_name AS name, COALESCE(SUM(" + NET_REV + "), 0) AS rev, COALESCE(SUM(" + NET_QTY + "), 0) AS qty " +
            "FROM sale_items si JOIN sales s ON s.id = si.sale_id WHERE " + SALE_OK + " AND s.created_at >= :from AND s.created_at < :to" +
            cashierFilter(cashierId) + " GROUP BY si.product_name ORDER BY rev DESC", range(from, to, cashierId));
        LinkedHashMap<String, BigDecimal[]> out = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) out.put(String.valueOf(r.get("name")), new BigDecimal[]{money(r.get("rev")), bd(r.get("qty"))});
        return out;
    }

    BigDecimal expensesTotal(LocalDate from, LocalDate to) {
        return money(jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM expenses WHERE expense_date >= :fromDate AND expense_date <= :toDate",
            range(from, to, null), BigDecimal.class));
    }

    LinkedHashMap<String, BigDecimal> expensesByCategory(LocalDate from, LocalDate to) {
        LinkedHashMap<String, BigDecimal> out = new LinkedHashMap<>();
        jdbc.queryForList("SELECT category, COALESCE(SUM(amount), 0) AS amt FROM expenses WHERE expense_date >= :fromDate AND expense_date <= :toDate " +
                "GROUP BY category ORDER BY amt DESC", range(from, to, null))
            .forEach(r -> out.put(String.valueOf(r.get("category")), money(r.get("amt"))));
        return out;
    }

    // ---------------------------------------------------------------------------------------------
    // 1. Sales for any period, explained
    // ---------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ToolExecutionResult salesForPeriod(User user, Period p) {
        boolean staff = isStaff(user);
        Long cashier = staff ? user.getId() : null;
        Snapshot cur = snapshot(p.from(), p.to(), cashier);
        Snapshot prev = snapshot(p.prevFrom(), p.prevTo(), cashier);
        boolean isToday = p.singleDay() && p.to().equals(clock.today());
        String who = staff ? "you've billed" : "you've made";
        String lead = isToday ? "So far today" : cap(p.label());

        StringBuilder md = new StringBuilder();
        if (cur.bills() == 0) {
            md.append(isToday ? "No sales have been recorded yet today." : "There were no sales recorded " + p.label() + ".");
            if (prev.bills() > 0) md.append(String.format(" For reference, %s %s **%s** from %d bills.", p.prevLabel(), staff ? "you billed" : "you made", inr(prev.sales()), prev.bills()));
            if (staff) md.append("\n\n*I'm only showing bills you created - store-wide totals are visible to the owner and manager.*");
        } else {
            md.append(String.format("%s, %s **%s** from **%d %s** (average bill %s).", lead, who, inr(cur.sales()), cur.bills(), cur.bills() == 1 ? "bill" : "bills", inr(cur.avgBill())));
            md.append(' ').append(compareSentence(cur.sales(), prev.sales(), prev.bills(), p.prevLabel()));
            if (isToday && cur.sales().compareTo(prev.sales()) < 0) md.append(" The day isn't over yet, so this gap can still close.");

            LinkedHashMap<String, BigDecimal[]> products = productRevenue(p.from(), p.to(), cashier);
            if (!products.isEmpty()) {
                List<Map.Entry<String, BigDecimal[]>> top = new ArrayList<>(products.entrySet()).subList(0, Math.min(3, products.size()));
                Map.Entry<String, BigDecimal[]> first = top.get(0);
                md.append(String.format("\n\nYour top seller %s is **%s** (%s from %s units)", isToday ? "today" : p.label(), first.getKey(), inr(first.getValue()[0]), qty(first.getValue()[1])));
                if (top.size() > 1) md.append(", followed by ").append(joinNames(top.subList(1, top.size()).stream().map(Map.Entry::getKey).toList()));
                md.append('.');
            }
            if (!staff && cur.lineRevenue().signum() > 0) {
                md.append(String.format(" These sales earned an estimated **%s gross profit** (%s%% margin) before expenses.", inr(cur.grossProfit()), cur.marginPct().toPlainString()));
            }
            if (cur.refunds().signum() > 0) md.append(String.format("\n\n%s in refunds has already been deducted.", inr(cur.refunds())));
            if (staff) md.append("\n\n*These are the bills you created - store-wide totals are visible to the owner and manager.*");
        }

        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Net sales", inr(cur.sales()), inr(prev.sales())));
        rows.add(List.of("Bills", String.valueOf(cur.bills()), String.valueOf(prev.bills())));
        rows.add(List.of("Average bill", inr(cur.avgBill()), inr(prev.avgBill())));
        rows.add(List.of("Units sold", qty(cur.qty()), qty(prev.qty())));
        if (!staff) rows.add(List.of("Gross profit", inr(cur.grossProfit()), inr(prev.grossProfit())));
        TablePayload table = new TablePayload("Sales: " + p.label() + " vs " + p.prevLabel(), List.of("Metric", cap(p.label()), cap(p.prevLabel())), rows);

        List<String> follow = new ArrayList<>();
        if (cur.bills() > 0 || prev.bills() > 0) follow.add(cur.sales().compareTo(prev.sales()) >= 0 ? "Why are sales higher?" : "Why are sales lower?");
        if (!"yesterday".equals(p.label())) follow.add("What about yesterday?");
        if (!"this month".equals(p.label())) follow.add("And this month?");
        follow.add("Do you have any suggestions?");

        return new ToolExecutionResult("SALES_PERIOD", false, md.toString(), List.of(table),
            staff ? List.of(new NavAction("Open Sales", "/sales", "See your bills")) : List.of(new NavAction("Open Sales Analytics", "/analytics", "Explore trends in detail")),
            List.of(), follow);
    }

    // ---------------------------------------------------------------------------------------------
    // 2. "Why" - explain the difference between two periods
    // ---------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ToolExecutionResult explainSalesChange(User user, LocalDate aFrom, LocalDate aTo, String aLabel, LocalDate bFrom, LocalDate bTo, String bLabel) {
        boolean staff = isStaff(user);
        Long cashier = staff ? user.getId() : null;
        Snapshot a = snapshot(aFrom, aTo, cashier);
        Snapshot b = snapshot(bFrom, bTo, cashier);

        if (a.bills() == 0 && b.bills() == 0) {
            return new ToolExecutionResult("SALES_EXPLAIN", false,
                String.format("There are no recorded sales for either %s or %s, so there's no difference to explain.", aLabel, bLabel),
                List.of(), List.of(), List.of(), List.of("How are my sales this month?", "Do you have any suggestions?"));
        }

        BigDecimal diff = a.sales().subtract(b.sales());
        String dir = diff.signum() > 0 ? "higher" : diff.signum() < 0 ? "lower" : "the same as";
        StringBuilder md = new StringBuilder();
        if (diff.signum() == 0) {
            md.append(String.format("%s's sales (**%s**) are exactly the same as %s's.", cap(aLabel), inr(a.sales()), bLabel));
        } else if (b.sales().signum() == 0) {
            md.append(String.format("%s had **%s** in sales while %s had none, so the entire amount is new business.", cap(aLabel), inr(a.sales()), bLabel));
        } else {
            md.append(String.format("%s's sales (**%s**) are **%s (%s%%) %s** than %s's (**%s**). Here's what the records show:",
                cap(aLabel), inr(a.sales()), inr(diff.abs()), pct(diff.abs(), b.sales()).toPlainString(), dir, bLabel, inr(b.sales())));
        }

        // Volume vs basket-size decomposition
        md.append(String.format("\n\n- **Number of bills:** %d vs %d (%s)", a.bills(), b.bills(), signedCount(a.bills() - b.bills())));
        md.append(String.format("\n- **Average bill size:** %s vs %s", inr(a.avgBill()), inr(b.avgBill())));
        if (diff.signum() != 0 && a.bills() > 0 && b.bills() > 0) {
            BigDecimal billEffect = BigDecimal.valueOf(a.bills() - b.bills()).multiply(b.avgBill());
            BigDecimal basketEffect = diff.subtract(billEffect);
            boolean volumeDriven = billEffect.abs().compareTo(basketEffect.abs()) >= 0;
            md.append(volumeDriven
                ? String.format("\n- Most of the change came from **%s customers/bills** rather than basket size.", a.bills() >= b.bills() ? "more" : "fewer")
                : String.format("\n- Most of the change came from **%s baskets** - customers spent %s per bill.", a.avgBill().compareTo(b.avgBill()) >= 0 ? "bigger" : "smaller", a.avgBill().compareTo(b.avgBill()) >= 0 ? "more" : "less"));
        }

        // Product-level contribution
        LinkedHashMap<String, BigDecimal[]> pa = productRevenue(aFrom, aTo, cashier);
        LinkedHashMap<String, BigDecimal[]> pb = productRevenue(bFrom, bTo, cashier);
        Set<String> names = new LinkedHashSet<>(pa.keySet());
        names.addAll(pb.keySet());
        List<Map.Entry<String, BigDecimal>> deltas = new ArrayList<>();
        for (String n : names) {
            BigDecimal ra = pa.containsKey(n) ? pa.get(n)[0] : BigDecimal.ZERO;
            BigDecimal rb = pb.containsKey(n) ? pb.get(n)[0] : BigDecimal.ZERO;
            BigDecimal d = ra.subtract(rb);
            if (d.signum() != 0) deltas.add(Map.entry(n, d));
        }
        List<Map.Entry<String, BigDecimal>> ups = deltas.stream().filter(e -> e.getValue().signum() > 0)
            .sorted((x, y) -> y.getValue().compareTo(x.getValue())).limit(3).toList();
        List<Map.Entry<String, BigDecimal>> downs = deltas.stream().filter(e -> e.getValue().signum() < 0)
            .sorted(Map.Entry.comparingByValue()).limit(3).toList();

        List<Map.Entry<String, BigDecimal>> drivers = diff.signum() >= 0 ? ups : downs;
        List<Map.Entry<String, BigDecimal>> offsets = diff.signum() >= 0 ? downs : ups;
        if (!drivers.isEmpty()) {
            md.append(diff.signum() >= 0 ? "\n\n**Products that pushed sales up:** " : "\n\n**Products that pulled sales down:** ");
            md.append(String.join(", ", drivers.stream().map(e -> e.getKey() + " (" + signedInr(e.getValue()) + ")").toList())).append('.');
        }
        if (!offsets.isEmpty()) {
            md.append("\n**Partly offset by:** ").append(String.join(", ", offsets.stream().map(e -> e.getKey() + " (" + signedInr(e.getValue()) + ")").toList())).append('.');
        }
        if (aTo.equals(clock.today())) md.append("\n\n*Note: " + aLabel + " is still in progress, so these numbers will keep changing.*");
        md.append("\n\n*This is based on recorded sales only - things like weather or local events aren't tracked in the system.*");

        List<List<String>> rows = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : deltas.stream().sorted((x, y) -> y.getValue().abs().compareTo(x.getValue().abs())).limit(8).toList()) {
            BigDecimal ra = pa.containsKey(e.getKey()) ? pa.get(e.getKey())[0] : BigDecimal.ZERO;
            BigDecimal rb = pb.containsKey(e.getKey()) ? pb.get(e.getKey())[0] : BigDecimal.ZERO;
            rows.add(List.of(e.getKey(), inr(ra), inr(rb), signedInr(e.getValue())));
        }
        List<TablePayload> tables = rows.isEmpty() ? List.of()
            : List.of(new TablePayload("Biggest product changes: " + aLabel + " vs " + bLabel, List.of("Product", cap(aLabel), cap(bLabel), "Change"), rows));

        return new ToolExecutionResult("SALES_EXPLAIN", false, md.toString(), tables,
            staff ? List.of() : List.of(new NavAction("Open Sales Analytics", "/analytics", "Compare periods in detail")), List.of(),
            List.of("Which product sells the most?", "Which products have declining sales?", "Do you have any suggestions?"));
    }

    // ---------------------------------------------------------------------------------------------
    // 3. Profit for a period, explained
    // ---------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ToolExecutionResult profitForPeriod(User user, Period p) {
        if (isStaff(user)) return restricted("PROFIT_PERIOD", "Profit figures are visible to the owner and manager only. I can still tell you about your own sales today or what needs restocking.");
        Snapshot cur = snapshot(p.from(), p.to(), null);
        Snapshot prev = snapshot(p.prevFrom(), p.prevTo(), null);
        BigDecimal exp = expensesTotal(p.from(), p.to());
        BigDecimal prevExp = expensesTotal(p.prevFrom(), p.prevTo());
        BigDecimal net = cur.grossProfit().subtract(exp);
        BigDecimal prevNet = prev.grossProfit().subtract(prevExp);
        LinkedHashMap<String, BigDecimal> cats = expensesByCategory(p.from(), p.to());

        StringBuilder md = new StringBuilder();
        if (cur.bills() == 0 && exp.signum() == 0) {
            md.append(String.format("There are no sales or expenses recorded for %s yet, so there's no profit to report.", p.label()));
        } else {
            if (net.signum() >= 0) md.append(String.format("You made a **net profit of %s** %s.", inr(net), p.label()));
            else md.append(String.format("You're running at a **loss of %s** %s.", inr(net.abs()), p.label()));
            md.append(String.format("\n\nHere's how it breaks down: out of **%s** in sales, **%s** went to the cost of the goods you sold, leaving a **gross profit of %s** (%s%% margin). After **%s** in expenses, ",
                inr(cur.lineRevenue()), inr(cur.cogs()), inr(cur.grossProfit()), cur.marginPct().toPlainString(), inr(exp)));
            md.append(net.signum() >= 0 ? String.format("you keep **%s**.", inr(net)) : String.format("you're short by **%s**.", inr(net.abs())));
            if (!cats.isEmpty()) {
                Map.Entry<String, BigDecimal> top = cats.entrySet().iterator().next();
                md.append(String.format(" Your biggest expense was **%s** (%s).", pretty(top.getKey()), inr(top.getValue())));
            }
            if (prev.bills() > 0 || prevExp.signum() > 0) {
                BigDecimal d = net.subtract(prevNet);
                md.append(String.format("\n\nCompared with %s (net %s), profit is **%s by %s**", p.prevLabel(), inr(prevNet), d.signum() >= 0 ? "up" : "down", inr(d.abs())));
                if (prevNet.signum() > 0) md.append(String.format(" (%s%%)", pct(d.abs(), prevNet).toPlainString()));
                md.append('.');
                BigDecimal marginDelta = cur.marginPct().subtract(prev.marginPct());
                if (prev.lineRevenue().signum() > 0 && marginDelta.abs().compareTo(new BigDecimal("2")) >= 0) {
                    md.append(String.format(" Your gross margin moved from %s%% to %s%%, which %s.", prev.marginPct().toPlainString(), cur.marginPct().toPlainString(),
                        marginDelta.signum() > 0 ? "means you're keeping more from every rupee sold" : "means each sale is earning you less - worth checking purchase costs and discounts"));
                }
            }
            if (net.signum() < 0) md.append("\n\nTo get back to profit, either grow sales of your higher-margin products or bring the largest expense categories down.");
        }

        TablePayload table = new TablePayload("Profit & Loss: " + p.label(), List.of("Line", cap(p.label()), cap(p.prevLabel())), List.of(
            List.of("Net sales (after refunds)", inr(cur.lineRevenue()), inr(prev.lineRevenue())),
            List.of("Cost of goods sold", inr(cur.cogs()), inr(prev.cogs())),
            List.of("Gross profit", inr(cur.grossProfit()), inr(prev.grossProfit())),
            List.of("Gross margin", cur.marginPct() + "%", prev.marginPct() + "%"),
            List.of("Expenses", inr(exp), inr(prevExp)),
            List.of("Net profit", inr(net), inr(prevNet))));

        return new ToolExecutionResult("PROFIT_PERIOD", false, md.toString(), List.of(table),
            List.of(new NavAction("Open Sales Analytics", "/analytics", "Explore sales, profit & exports")),
            List.of(new AiDtos.DownloadAction("Download P&L CSV", "/reports/pnl.csv?from=" + p.from() + "&to=" + p.to(), "pnl-" + p.from() + "-to-" + p.to() + ".csv")),
            List.of("What are my highest expenses?", "What about last month?", "Do you have any suggestions?"));
    }

    // ---------------------------------------------------------------------------------------------
    // 4. Expenses with anomaly detection
    // ---------------------------------------------------------------------------------------------

    record ExpenseSpike(String category, BigDecimal current, BigDecimal expected, BigDecimal pctAbove, boolean isNew) {}

    /** Categories whose daily spend in [from,to] is >= 30% above their daily average over the 90 days before `from`. */
    List<ExpenseSpike> expenseSpikes(LocalDate from, LocalDate to) {
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        LocalDate baseFrom = from.minusDays(90), baseTo = from.minusDays(1);
        LinkedHashMap<String, BigDecimal> cur = expensesByCategory(from, to);
        LinkedHashMap<String, BigDecimal> base = expensesByCategory(baseFrom, baseTo);
        boolean hasHistory = !base.isEmpty();
        List<ExpenseSpike> out = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : cur.entrySet()) {
            if (e.getValue().compareTo(new BigDecimal("500")) < 0) continue; // ignore small noise
            BigDecimal baseTotal = base.getOrDefault(e.getKey(), BigDecimal.ZERO);
            BigDecimal expected = baseTotal.multiply(BigDecimal.valueOf(days)).divide(new BigDecimal("90"), 2, RoundingMode.HALF_UP);
            if (expected.signum() == 0) {
                if (hasHistory) out.add(new ExpenseSpike(e.getKey(), e.getValue(), BigDecimal.ZERO, BigDecimal.ZERO, true));
                continue;
            }
            BigDecimal ratio = e.getValue().divide(expected, 4, RoundingMode.HALF_UP);
            if (ratio.compareTo(new BigDecimal("1.30")) >= 0) {
                out.add(new ExpenseSpike(e.getKey(), e.getValue(), expected, ratio.subtract(BigDecimal.ONE).multiply(HUNDRED).setScale(0, RoundingMode.HALF_UP), false));
            }
        }
        out.sort((x, y) -> y.current().subtract(y.expected()).compareTo(x.current().subtract(x.expected())));
        return out;
    }

    @Transactional(readOnly = true)
    public ToolExecutionResult expensesInsight(User user, Period p) {
        if (isStaff(user)) return restricted("EXPENSES", "Store expense totals are visible to the owner and manager. You can still record an expense from the Expenses page.");
        LinkedHashMap<String, BigDecimal> cats = expensesByCategory(p.from(), p.to());
        BigDecimal total = cats.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal prevTotal = expensesTotal(p.prevFrom(), p.prevTo());
        Snapshot sales = snapshot(p.from(), p.to(), null);

        StringBuilder md = new StringBuilder();
        List<TablePayload> tables = new ArrayList<>();
        if (cats.isEmpty()) {
            md.append(String.format("No expenses have been recorded for %s.", p.label()));
            if (prevTotal.signum() > 0) md.append(String.format(" For comparison, %s you spent %s.", p.prevLabel(), inr(prevTotal)));
        } else {
            List<Map.Entry<String, BigDecimal>> list = new ArrayList<>(cats.entrySet());
            Map.Entry<String, BigDecimal> top = list.get(0);
            md.append(String.format("You've spent **%s** on expenses %s. The biggest item is **%s at %s** - that's %s%% of all spending.",
                inr(total), p.label(), pretty(top.getKey()), inr(top.getValue()), pct(top.getValue(), total).toPlainString()));
            if (list.size() > 1) {
                md.append(" Next come ").append(joinNames(list.subList(1, Math.min(3, list.size())).stream()
                    .map(e -> pretty(e.getKey()) + " (" + inr(e.getValue()) + ")").toList())).append('.');
            }
            if (prevTotal.signum() > 0) {
                BigDecimal d = total.subtract(prevTotal);
                md.append(String.format("\n\nThat's **%s (%s%%) %s** than %s (%s).", inr(d.abs()), pct(d.abs(), prevTotal).toPlainString(), d.signum() >= 0 ? "more" : "less", p.prevLabel(), inr(prevTotal)));
            }
            if (sales.sales().signum() > 0) {
                md.append(String.format(" Expenses are eating up **%s%% of your sales** for this period.", pct(total, sales.sales()).toPlainString()));
            }
            List<ExpenseSpike> spikes = expenseSpikes(p.from(), p.to());
            if (!spikes.isEmpty()) {
                md.append("\n\n**Unusual spending I noticed:**");
                for (ExpenseSpike s : spikes.stream().limit(3).toList()) {
                    md.append(s.isNew()
                        ? String.format("\n- **%s**: %s this period, with no spending in this category over the previous 90 days.", pretty(s.category()), inr(s.current()))
                        : String.format("\n- **%s**: %s, about **%s%% above** your usual %s for a period this long.", pretty(s.category()), inr(s.current()), s.pctAbove().toPlainString(), inr(s.expected())));
                }
                md.append("\n\nIt's worth checking whether these were one-off costs or something that will repeat.");
            }
            List<List<String>> rows = new ArrayList<>();
            for (Map.Entry<String, BigDecimal> e : list) rows.add(List.of(pretty(e.getKey()), inr(e.getValue()), pct(e.getValue(), total) + "%"));
            tables.add(new TablePayload("Expenses by category: " + p.label(), List.of("Category", "Amount", "Share"), rows));

            List<Map<String, Object>> biggest = jdbc.queryForList(
                "SELECT description, category, amount, expense_date FROM expenses WHERE expense_date >= :fromDate AND expense_date <= :toDate ORDER BY amount DESC LIMIT 5",
                range(p.from(), p.to(), null));
            if (!biggest.isEmpty()) {
                List<List<String>> bRows = new ArrayList<>();
                for (Map<String, Object> r : biggest) bRows.add(List.of(String.valueOf(r.get("description")), pretty(String.valueOf(r.get("category"))), inr(money(r.get("amount"))), String.valueOf(r.get("expense_date"))));
                tables.add(new TablePayload("Largest individual expenses", List.of("Description", "Category", "Amount", "Date"), bRows));
            }
        }
        return new ToolExecutionResult("EXPENSES", false, md.toString(), tables,
            List.of(new NavAction("Open Expenses", "/expenses", "Review and filter expenses")), List.of(),
            List.of("What is my total profit?", "What about last month?", "Do you have any suggestions?"));
    }

    // ---------------------------------------------------------------------------------------------
    // 5. Customers & receivables
    // ---------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ToolExecutionResult topCustomers(User user, Period p) {
        if (isStaff(user)) return restricted("TOP_CUSTOMERS", "Customer spending reports are visible to the owner and manager.");
        MapSqlParameterSource params = range(p.from(), p.to(), null);
        Map<String, Object> cov = jdbc.queryForMap(
            "SELECT COUNT(*) AS bills, COUNT(*) FILTER (WHERE NULLIF(TRIM(s.customer_name), '') IS NOT NULL) AS named FROM sales s " +
            "WHERE " + SALE_OK + " AND s.created_at >= :from AND s.created_at < :to", params);
        long bills = ((Number) cov.get("bills")).longValue();
        long named = ((Number) cov.get("named")).longValue();

        if (named == 0) {
            String md = bills == 0
                ? String.format("There are no sales recorded for %s, so I can't rank customers yet.", p.label())
                : String.format("I can't rank customers yet - none of your **%d bills** in %s had a customer name attached. " +
                    "Customers aren't registered in this system, so the only way I can recognise them is the optional **Customer name** field at POS checkout.\n\n" +
                    "**Suggestion:** ask cashiers to type the customer's name (or phone) for regular shoppers. Within a few weeks I'll be able to show who your most valuable customers are and how often they visit.",
                    bills, p.label());
            return new ToolExecutionResult("TOP_CUSTOMERS", false, md, List.of(), List.of(new NavAction("Open POS Billing", "/pos", "Add customer names at checkout")), List.of(),
                List.of("Which product sells the most?", "Do you have any suggestions?"));
        }

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT MIN(TRIM(s.customer_name)) AS name, COUNT(*) AS visits, COALESCE(SUM(" + NET_SALE + "), 0) AS spent, MAX(s.created_at) AS last_visit " +
            "FROM sales s WHERE " + SALE_OK + " AND s.created_at >= :from AND s.created_at < :to AND NULLIF(TRIM(s.customer_name), '') IS NOT NULL " +
            "GROUP BY LOWER(TRIM(s.customer_name)) ORDER BY spent DESC LIMIT 10", params);
        Map<String, Object> first = rows.get(0);
        StringBuilder md = new StringBuilder(String.format("Your top customer %s is **%s**, who spent **%s** across %s visits.",
            p.label(), first.get("name"), inr(money(first.get("spent"))), first.get("visits")));
        if (rows.size() > 1) {
            md.append(" Next are ").append(joinNames(rows.subList(1, Math.min(3, rows.size())).stream()
                .map(r -> r.get("name") + " (" + inr(money(r.get("spent"))) + ")").toList())).append('.');
        }
        md.append(String.format("\n\n*Only %d of %d bills (%s%%) had a customer name, so this covers named customers only.*", named, bills,
            pct(BigDecimal.valueOf(named), BigDecimal.valueOf(bills)).toPlainString()));
        if (rows.size() >= 3) md.append("\n\n**Suggestion:** your regulars are worth looking after - a small loyalty discount or priority home delivery for them is an inexpensive way to keep their business.");

        List<List<String>> t = new ArrayList<>();
        int rank = 1;
        for (Map<String, Object> r : rows) {
            Object lv = r.get("last_visit");
            String last = lv == null ? "-" : String.valueOf(lv).substring(0, Math.min(10, String.valueOf(lv).length()));
            t.add(List.of("#" + rank++, String.valueOf(r.get("name")), String.valueOf(r.get("visits")), inr(money(r.get("spent"))), last));
        }
        return new ToolExecutionResult("TOP_CUSTOMERS", false, md.toString(),
            List.of(new TablePayload("Top customers: " + p.label(), List.of("Rank", "Customer", "Visits", "Spent", "Last visit"), t)),
            List.of(new NavAction("Open Sales", "/sales", "See bills by customer")), List.of(),
            List.of("Which product sells the most?", "Do you have any suggestions?"));
    }

    @Transactional(readOnly = true)
    public ToolExecutionResult receivables(User user) {
        if (isStaff(user)) return restricted("RECEIVABLES", "Payment balances are visible to the owner and manager.");
        StringBuilder md = new StringBuilder(
            "Good news - **nobody owes you money right now.** Every sale in this system is paid in full at checkout (cash, UPI or card), " +
            "so there's no customer credit (udhaar) balance to collect.");

        List<Map<String, Object>> sup = jdbc.queryForList(
            "SELECT s.name, (s.opening_balance + COALESCE((SELECT SUM(CASE WHEN sl.entry_type IN ('PURCHASE','DEBIT_ADJUSTMENT') THEN sl.amount ELSE -sl.amount END) " +
            "FROM supplier_ledger sl WHERE sl.supplier_id = s.id), 0)) AS balance FROM suppliers s ORDER BY balance DESC", new MapSqlParameterSource());
        List<Map<String, Object>> owed = sup.stream().filter(r -> money(r.get("balance")).signum() > 0).toList();
        BigDecimal owedTotal = owed.stream().map(r -> money(r.get("balance"))).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<List<String>> rows = new ArrayList<>();
        if (owedTotal.signum() > 0) {
            md.append(String.format("\n\nOn the other side, **you owe %s to %d %s**", inr(owedTotal), owed.size(), owed.size() == 1 ? "supplier" : "suppliers"));
            md.append(" - mostly ").append(joinNames(owed.stream().limit(3).map(r -> r.get("name") + " (" + inr(money(r.get("balance"))) + ")").toList())).append('.');
            owed.forEach(r -> rows.add(List.of("Supplier", String.valueOf(r.get("name")), inr(money(r.get("balance"))))));
        } else {
            md.append("\n\nYou don't owe any suppliers either - all supplier balances are settled.");
        }

        if (isOwner(user)) {
            try {
                HrDashboardResponse hr = hrService.getDashboard(YearMonth.from(clock.today()).toString());
                if (hr.pendingSalaries() != null && hr.pendingSalaries().signum() > 0) {
                    md.append(String.format(" There's also **%s in unpaid salaries** for this month.", inr(hr.pendingSalaries())));
                    rows.add(List.of("Salaries", "Pending payroll (" + YearMonth.from(clock.today()) + ")", inr(hr.pendingSalaries())));
                }
            } catch (Exception e) {
                log.debug("Payroll lookup skipped: {}", e.getMessage());
            }
        }
        List<TablePayload> tables = rows.isEmpty() ? List.of() : List.of(new TablePayload("What you owe", List.of("Type", "To", "Amount"), rows));
        return new ToolExecutionResult("RECEIVABLES", false, md.toString(), tables,
            List.of(new NavAction("Open Suppliers", "/suppliers", "Record supplier payments")), List.of(),
            List.of("What is my current cash balance?", "What is my total profit?", "Do you have any suggestions?"));
    }

    // ---------------------------------------------------------------------------------------------
    // 6. Evidence-backed recommendations
    // ---------------------------------------------------------------------------------------------

    record Advice(int priority, String area, String title, String evidence, String action) {}

    @Transactional(readOnly = true)
    public ToolExecutionResult recommendations(User user) {
        LocalDate today = clock.today();
        boolean staff = isStaff(user);
        List<Advice> advice = new ArrayList<>();

        // a) Restock: low stock items that are actually selling (30-day velocity)
        MapSqlParameterSource r30 = range(today.minusDays(29), today, null);
        List<Map<String, Object>> low = jdbc.queryForList(
            "SELECT p.name, p.unit, p.current_stock, p.min_stock_level, " +
            "COALESCE((SELECT SUM(" + NET_QTY + ") FROM sale_items si JOIN sales s ON s.id = si.sale_id WHERE si.product_id = p.id AND " + SALE_OK +
            " AND s.created_at >= :from AND s.created_at < :to), 0) AS sold30 " +
            "FROM products p WHERE p.is_active = true AND p.current_stock <= p.min_stock_level ORDER BY p.current_stock ASC", r30);
        List<Map<String, Object>> selling = low.stream().filter(r -> bd(r.get("sold30")).signum() > 0).toList();
        long outOfStock = selling.stream().filter(r -> bd(r.get("current_stock")).signum() <= 0).count();
        if (!selling.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Map<String, Object> r : selling.stream().limit(3).toList()) {
                BigDecimal daily = bd(r.get("sold30")).divide(new BigDecimal("30"), 3, RoundingMode.HALF_UP);
                BigDecimal stock = bd(r.get("current_stock"));
                String cover = stock.signum() <= 0 ? "already out of stock" : daily.signum() == 0 ? "low" : "about " + stock.divide(daily, 0, RoundingMode.HALF_UP) + " days left";
                parts.add(r.get("name") + " (" + qty(stock) + " " + r.get("unit") + ", " + cover + ")");
            }
            advice.add(new Advice(outOfStock > 0 ? 1 : 2, "Inventory", outOfStock > 0 ? "Restock items that are selling out" : "Reorder fast movers before they run out",
                String.format("%d selling product%s at or below minimum stock: %s.", selling.size(), selling.size() == 1 ? " is" : "s are", String.join("; ", parts)),
                "Place a purchase order now - empty shelves on items customers already buy means lost sales."));
        } else if (!low.isEmpty()) {
            advice.add(new Advice(3, "Inventory", "Review low-stock items that aren't selling",
                String.format("%d product%s below minimum stock but had no sales in the last 30 days.", low.size(), low.size() == 1 ? " is" : "s are"),
                "Don't rush to reorder these - consider lowering their minimum stock level instead."));
        }

        // b) Expiring stock
        Map<String, Object> exp = jdbc.queryForMap(
            "SELECT COUNT(*) AS n, COALESCE(SUM(current_stock * purchase_price), 0) AS val, STRING_AGG(name, ', ' ORDER BY expiry_date) AS names FROM products " +
            "WHERE is_active = true AND current_stock > 0 AND expiry_date IS NOT NULL AND expiry_date <= :limit",
            new MapSqlParameterSource("limit", Date.valueOf(today.plusDays(7))));
        long expN = ((Number) exp.get("n")).longValue();
        if (expN > 0) {
            String names = String.valueOf(exp.get("names"));
            if (names.length() > 120) names = names.substring(0, 117) + "...";
            advice.add(new Advice(2, "Inventory", "Sell or clear stock that's about to expire",
                String.format("%d product%s expire within 7 days (or already have), worth %s at cost: %s.", expN, expN == 1 ? "" : "s", inr(money(exp.get("val"))), names),
                "Put these at the counter or offer a quick discount - recovering part of the cost beats writing it all off."));
        }

        if (!staff) {
            // c) Sales trend: last 7 days vs previous 7
            Snapshot w = snapshot(today.minusDays(6), today, null);
            Snapshot pw = snapshot(today.minusDays(13), today.minusDays(7), null);
            if (pw.sales().signum() > 0) {
                BigDecimal change = pct(w.sales().subtract(pw.sales()), pw.sales());
                if (change.compareTo(new BigDecimal("-10")) <= 0) {
                    advice.add(new Advice(1, "Sales", "Sales have slowed this week",
                        String.format("Last 7 days: %s vs %s the week before (%s%%), with %d vs %d bills.", inr(w.sales()), inr(pw.sales()), change.toPlainString(), w.bills(), pw.bills()),
                        w.bills() < pw.bills() ? "Fewer customers are coming in - a weekend offer or a WhatsApp message to regulars can bring footfall back."
                            : "Customers are buying less per visit - try placing add-on items near the counter or simple combo offers."));
                } else if (change.compareTo(new BigDecimal("10")) >= 0) {
                    advice.add(new Advice(4, "Sales", "Sales are growing - keep the momentum",
                        String.format("Last 7 days: %s vs %s the week before (+%s%%).", inr(w.sales()), inr(pw.sales()), change.toPlainString()),
                        "Make sure your best sellers stay in stock so you don't lose this demand."));
                }
            }

            // d) Declining products & stars (one analytics call)
            try {
                AnalyticsDashboardResponse dash = analyticsService.getDashboard("LAST_30_DAYS", null, null, null, null, null);
                List<ProductPerformanceDto> declining = dash.decliningProducts().stream()
                    .sorted((x, y) -> y.prevRevenue().subtract(y.revenue()).compareTo(x.prevRevenue().subtract(x.revenue()))).limit(3).toList();
                if (!declining.isEmpty()) {
                    advice.add(new Advice(2, "Sales", "Some products are losing sales",
                        String.join("; ", declining.stream().map(d -> d.productName() + " down from " + inr(d.prevRevenue()) + " to " + inr(d.revenue())).toList())
                            + " (last 30 days vs the 30 days before).",
                        "Check if prices went up, a competitor is cheaper, or the item was out of stock. If demand has truly dropped, order less next time."));
                }
                List<ProductPerformanceDto> stars = dash.topByRevenue().stream().filter(s -> s.revenue().signum() > 0).limit(3).toList();
                if (!stars.isEmpty()) {
                    List<String> thin = stars.stream().filter(s -> s.stockCoverageDays() != null && s.stockCoverageDays().compareTo(new BigDecimal("7")) < 0)
                        .map(ProductPerformanceDto::productName).toList();
                    advice.add(new Advice(thin.isEmpty() ? 4 : 2, "Sales", "Your strongest products",
                        String.join("; ", stars.stream().map(s -> s.productName() + " " + inr(s.revenue()) + " (" + s.marginPct() + "% margin)").toList()) + " in the last 30 days.",
                        thin.isEmpty() ? "These are your bread and butter - keep them well stocked and visible, and consider pairing them with slower items."
                            : "Stock of " + joinNames(thin) + " covers less than a week at the current pace - top up soon."));
                }
                if (dash.summary().peakSalesHour() != null && dash.summary().peakSalesHour().billCount() > 0) {
                    var h = dash.summary().peakSalesHour();
                    advice.add(new Advice(5, "Operations", "Plan staff around your busiest hour",
                        String.format("Your busiest hour over the last 30 days is %s (%d bills, %s).", h.label(), h.billCount(), inr(h.revenue())),
                        "Have your fastest cashier on the counter and shelves restocked before this hour."));
                }
            } catch (Exception e) {
                log.warn("Analytics signals unavailable for recommendations: {}", e.getMessage());
            }

            // e) Margin & loss (month to date vs same days last month)
            Period mtd = PeriodResolver.thisMonth(today);
            Snapshot m = snapshot(mtd.from(), mtd.to(), null);
            Snapshot pm = snapshot(mtd.prevFrom(), mtd.prevTo(), null);
            BigDecimal mExp = expensesTotal(mtd.from(), mtd.to());
            BigDecimal mNet = m.grossProfit().subtract(mExp);
            if (m.bills() > 0 && mNet.signum() < 0) {
                advice.add(new Advice(1, "Profit", "You're running at a loss this month",
                    String.format("Gross profit %s minus expenses %s leaves %s so far this month.", inr(m.grossProfit()), inr(mExp), inr(mNet)),
                    "Look at the biggest expense categories first, and push higher-margin products."));
            }
            if (pm.lineRevenue().signum() > 0 && m.lineRevenue().signum() > 0) {
                BigDecimal drop = pm.marginPct().subtract(m.marginPct());
                if (drop.compareTo(new BigDecimal("2")) >= 0) {
                    advice.add(new Advice(2, "Profit", "Your profit margin is shrinking",
                        String.format("Gross margin is %s%% this month vs %s%% for the same days last month.", m.marginPct().toPlainString(), pm.marginPct().toPlainString()),
                        "Check whether supplier prices went up without a matching price change, or whether discounts have increased."));
                }
            }

            // f) Expense spikes
            List<ExpenseSpike> spikes = expenseSpikes(mtd.from(), mtd.to());
            if (!spikes.isEmpty()) {
                ExpenseSpike s = spikes.get(0);
                advice.add(new Advice(2, "Expenses", "Unusually high " + pretty(s.category()).toLowerCase(Locale.ROOT) + " spending",
                    s.isNew() ? String.format("%s spent on %s this month, with nothing in this category over the previous 90 days.", inr(s.current()), pretty(s.category()))
                        : String.format("%s on %s this month - about %s%% more than your usual %s for this many days.", inr(s.current()), pretty(s.category()), s.pctAbove().toPlainString(), inr(s.expected())),
                    "Confirm whether this was a one-off. If not, compare quotes or look for ways to cut it."));
            }

            // g) Dead stock tying up cash
            List<Map<String, Object>> dead = financeService.slowMoving(30);
            if (!dead.isEmpty()) {
                BigDecimal tied = dead.stream().map(r -> money(r.get("value"))).reduce(BigDecimal.ZERO, BigDecimal::add);
                advice.add(new Advice(3, "Inventory", "Free up cash stuck in unsold stock",
                    String.format("%d products haven't sold in 30 days, holding %s at cost - e.g. %s.", dead.size(), inr(tied),
                        joinNames(dead.stream().limit(3).map(r -> String.valueOf(r.get("name"))).toList())),
                    "Run a clearance or combo offer and stop reordering these until they move."));
            }

            // h) Money owed (owner only for salaries)
            if (isOwner(user)) {
                try {
                    HrDashboardResponse hr = hrService.getDashboard(YearMonth.from(today).toString());
                    if (hr.pendingSalaries() != null && hr.pendingSalaries().signum() > 0) {
                        advice.add(new Advice(3, "Payroll", "Clear pending salaries",
                            String.format("%s of this month's payroll is still unpaid.", inr(hr.pendingSalaries())),
                            "Plan the payment from your cash/UPI balance so staff are paid on time."));
                    }
                } catch (Exception e) {
                    log.debug("Payroll signal skipped: {}", e.getMessage());
                }
            }
        }

        advice.sort(Comparator.comparingInt(Advice::priority));
        StringBuilder md = new StringBuilder();
        if (advice.isEmpty()) {
            md.append("I went through your recent sales, stock and expenses and nothing needs urgent attention - stock levels are healthy and there are no unusual trends. ")
              .append("Keep recording every sale and expense, and I'll flag anything that changes.");
        } else {
            md.append(staff ? "Here's what I'd focus on in the store right now, based on current stock records:\n"
                : "I looked through your last 30 days of sales, stock and expenses. Here's what stands out:\n");
            int i = 1;
            for (Advice a : advice.stream().limit(6).toList()) {
                md.append(String.format("\n%d. **%s** - %s\n   *Suggestion:* %s\n", i++, a.title(), a.evidence(), a.action()));
            }
            if (staff) md.append("\n*Sales, profit and expense insights are shared with the owner and manager.*");
        }
        List<List<String>> rows = advice.stream().map(a -> List.of(priorityLabel(a.priority()), a.area(), a.title(), a.action())).toList();
        return new ToolExecutionResult("RECOMMENDATIONS", false, md.toString(),
            rows.isEmpty() ? List.of() : List.of(new TablePayload("Action plan", List.of("Priority", "Area", "Finding", "What to do"), rows)),
            staff ? List.of(new NavAction("Open Products", "/products", "Check stock")) : List.of(
                new NavAction("Open Smart Reorder", "/reorder", "Create restock orders"),
                new NavAction("Open Sales Analytics", "/analytics", "Dig into the numbers")),
            List.of(),
            staff ? List.of("Which products need restocking?", "Which products are expiring?")
                : List.of("Which products need restocking?", "What are my highest expenses?", "Which products have declining sales?"));
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private ToolExecutionResult restricted(String tool, String msg) {
        return new ToolExecutionResult(tool, true, "**Access Restricted:** " + msg, List.of(), List.of(), List.of(),
            List.of("How are my sales today?", "Which products need restocking?", "Do you have any suggestions?"));
    }

    private static String role(User u) { return u.getRole() != null ? u.getRole().getName() : "STAFF"; }
    private static boolean isStaff(User u) { return "STAFF".equals(role(u)); }
    private static boolean isOwner(User u) { return "OWNER".equals(role(u)); }

    private static String priorityLabel(int p) {
        return switch (p) { case 1 -> "Urgent"; case 2 -> "High"; case 3 -> "Medium"; default -> "Opportunity"; };
    }

    private String compareSentence(BigDecimal cur, BigDecimal prev, long prevBills, String prevLabel) {
        if (prevBills == 0 || prev.signum() == 0) return "There were no sales " + prevLabel + " to compare with.";
        BigDecimal d = cur.subtract(prev);
        if (d.signum() == 0) return "That's exactly the same as " + prevLabel + ".";
        return String.format("That's **%s (%s%%) %s** than %s (%s).", inr(d.abs()), pct(d.abs(), prev).toPlainString(), d.signum() > 0 ? "higher" : "lower", prevLabel, inr(prev));
    }

    static BigDecimal bd(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal b) return b;
        return new BigDecimal(o.toString());
    }

    static BigDecimal money(Object o) { return bd(o).setScale(2, RoundingMode.HALF_UP); }

    static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) return BigDecimal.ZERO;
        return part.multiply(HUNDRED).divide(whole.abs(), 1, RoundingMode.HALF_UP);
    }

    /** Indian-style grouping: 12,34,567 - and drops ".00" for whole rupees. */
    static String inr(BigDecimal v) {
        if (v == null) v = BigDecimal.ZERO;
        BigDecimal s = v.setScale(2, RoundingMode.HALF_UP);
        boolean neg = s.signum() < 0;
        s = s.abs();
        String[] parts = s.toPlainString().split("\\.");
        String intPart = parts[0];
        String grouped;
        if (intPart.length() <= 3) grouped = intPart;
        else {
            String last3 = intPart.substring(intPart.length() - 3);
            String rest = intPart.substring(0, intPart.length() - 3);
            StringBuilder sb = new StringBuilder();
            while (rest.length() > 2) {
                sb.insert(0, "," + rest.substring(rest.length() - 2));
                rest = rest.substring(0, rest.length() - 2);
            }
            sb.insert(0, rest);
            grouped = sb + "," + last3;
        }
        String dec = parts.length > 1 && !"00".equals(parts[1]) ? "." + parts[1] : "";
        return (neg ? "-₹" : "₹") + grouped + dec;
    }

    static String signedInr(BigDecimal v) { return (v.signum() >= 0 ? "+" : "-") + inr(v.abs()); }

    static String qty(BigDecimal q) { return q == null ? "0" : q.stripTrailingZeros().toPlainString(); }

    private static String signedCount(long d) { return d == 0 ? "no change" : (d > 0 ? "+" : "") + d; }

    static String cap(String s) { return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    static String pretty(String category) {
        if (category == null) return "";
        String c = category.replace('_', ' ').toLowerCase(Locale.ROOT);
        return cap(c);
    }

    static String joinNames(List<String> names) {
        if (names.isEmpty()) return "";
        if (names.size() == 1) return names.get(0);
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }
}
