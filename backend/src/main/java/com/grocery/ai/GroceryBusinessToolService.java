package com.grocery.ai;

import com.grocery.ai.AiDtos.DownloadAction;
import com.grocery.ai.AiDtos.NavAction;
import com.grocery.ai.AiDtos.TablePayload;
import com.grocery.analytics.AnalyticsDtos.*;
import com.grocery.analytics.AnalyticsService;
import com.grocery.common.StoreClock;
import com.grocery.finance.FinanceService;
import com.grocery.hr.HrDtos.*;
import com.grocery.hr.HrService;
import com.grocery.user.User;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Read-only, RBAC-enforced business data retrieval tools for GroceryGPT.
 * Never executes arbitrary LLM-generated SQL. Always runs parameterized queries or existing services.
 */
@Service
public class GroceryBusinessToolService {

    public record ToolExecutionResult(
        String toolName,
        boolean accessDenied,
        String summaryMarkdown,
        List<TablePayload> tables,
        List<NavAction> navLinks,
        List<DownloadAction> downloads,
        List<String> followUpSuggestions
    ) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final StoreClock clock;
    private final FinanceService financeService;
    private final AnalyticsService analyticsService;
    private final HrService hrService;

    public GroceryBusinessToolService(
        NamedParameterJdbcTemplate jdbc,
        StoreClock clock,
        FinanceService financeService,
        AnalyticsService analyticsService,
        HrService hrService
    ) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.financeService = financeService;
        this.analyticsService = analyticsService;
        this.hrService = hrService;
    }

    private String roleOf(User user) {
        return user.getRole() != null ? user.getRole().getName() : "STAFF";
    }

    private String inr(BigDecimal val) {
        if (val == null) return "₹0.00";
        return "₹" + String.format(Locale.US, "%,.2f", val.setScale(2, RoundingMode.HALF_UP));
    }

    private String num(BigDecimal val) {
        if (val == null) return "0";
        return val.stripTrailingZeros().toPlainString();
    }

    private ToolExecutionResult denied(String toolName, String reason, List<String> suggestions) {
        return new ToolExecutionResult(
            toolName,
            true,
            "**Access Restricted (`Role Permission Policy`)**: " + reason,
            List.of(),
            List.of(),
            List.of(),
            suggestions
        );
    }

    // =========================================================================
    // 1. SALES & ANALYTICS TOOLS
    // =========================================================================

    @Transactional(readOnly = true)
    public ToolExecutionResult getTodaySales(User user) {
        LocalDate today = clock.today();
        String role = roleOf(user);
        if ("STAFF".equals(role)) {
            MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("from", clock.odt(clock.startOf(today)))
                .addValue("to", clock.odt(clock.startOf(today.plusDays(1))))
                .addValue("uid", user.getId());
            Map<String, Object> r = jdbc.queryForMap(
                "SELECT COALESCE(SUM(total), 0) AS total, COUNT(*) AS bills FROM sales WHERE cashier_id = :uid AND created_at >= :from AND created_at < :to", p);
            BigDecimal total = (BigDecimal) r.get("total");
            long bills = ((Number) r.get("bills")).longValue();
            String md = String.format(
                "**Your POS Sales Today (%s):**\n- **Total Sales Billed by You**: **%s** across **%d** completed bills.\n*(Note: Store-wide revenue and profit breakdown is restricted to Owner and Manager roles.)*",
                today, inr(total), bills
            );
            return new ToolExecutionResult(
                "TODAY_SALES_CASHIER", false, md,
                List.of(new TablePayload("Your Sales Today (" + today + ")", List.of("Cashier", "Completed Bills", "Total Billed"),
                    List.of(List.of(user.getFullName(), String.valueOf(bills), inr(total))))),
                List.of(new NavAction("Open POS Billing", "/pos", "Create a new bill"), new NavAction("View Sales", "/sales", "View sales list")),
                List.of(),
                List.of("How do I open or close a cash shift?", "Which products need restocking?", "What is my attendance percentage?")
            );
        }

        FinanceService.Summary s = financeService.summary(today, today);
        String md = String.format(
            "### Today's Sales Summary (%s)\n" +
            "- **Total Sales (Net Revenue)**: **%s** across **%d** completed bills\n" +
            "- **Cash Sales**: %s | **UPI Sales**: %s\n" +
            "- **COGS (Cost of Goods Sold)**: %s\n" +
            "- **Gross Profit Today**: **%s** | **Operating Profit Today**: **%s** (after %s expenses)",
            today, inr(s.totalSales()), s.billCount(), inr(s.cashSales()), inr(s.upiSales()),
            inr(s.cogs()), inr(s.grossProfit()), inr(s.operatingProfit()), inr(s.expenses())
        );

        TablePayload table = new TablePayload(
            "Today's Financial Snapshot (" + today + ")",
            List.of("Metric", "Value"),
            List.of(
                List.of("Total Sales / Net Revenue", inr(s.totalSales())),
                List.of("Completed Bills", String.valueOf(s.billCount())),
                List.of("Cash Collected", inr(s.cashSales())),
                List.of("UPI Collected", inr(s.upiSales())),
                List.of("Cost of Goods Sold (COGS)", inr(s.cogs())),
                List.of("Gross Profit", inr(s.grossProfit())),
                List.of("Operating Expenses Today", inr(s.expenses())),
                List.of("Net / Operating Profit", inr(s.operatingProfit()))
            )
        );

        return new ToolExecutionResult(
            "TODAY_SALES", false, md, List.of(table),
            List.of(
                new NavAction("Open Sales Analytics", "/analytics", "View detailed product & hourly trends")
            ),
            List.of(new DownloadAction("Download Today's Sales CSV", "/reports/sales.csv?from=" + today + "&to=" + today, "sales-" + today + ".csv")),
            List.of("Which product sells the most?", "What is the best-performing category?", "Compare this month with last month")
        );
    }

    @Transactional(readOnly = true)
    public ToolExecutionResult getBestAndLeastSellingProducts(User user, boolean focusLeast, String rangeKey) {
        if ("STAFF".equals(roleOf(user))) {
            return denied("PRODUCT_PERFORMANCE", "Detailed store-wide product profit and sales rankings are available to `OWNER` and `MANAGER` roles.",
                List.of("How do I add a product?", "Which products are expiring?"));
        }
        String effectiveRange = (rangeKey == null || rangeKey.isBlank()) ? "LAST_30_DAYS" : rangeKey;
        AnalyticsDashboardResponse dash = analyticsService.getDashboard(effectiveRange, null, null, null, null, null);
        AnalyticsSummary sum = dash.summary();

        if (sum.billCount() == 0) {
            return new ToolExecutionResult(
                focusLeast ? "LEAST_SELLING_PRODUCTS" : "BEST_SELLING_PRODUCTS",
                false,
                String.format("No completed sales transactions were found in the selected period (**%s** to **%s**). Try asking about a broader period or record sales in POS Billing.", dash.from(), dash.to()),
                List.of(),
                List.of(new NavAction("Open Sales Analytics", "/analytics", "Check custom date ranges")),
                List.of(),
                List.of("What is the total inventory value?", "Which products need restocking?")
            );
        }

        StringBuilder md = new StringBuilder();
        List<TablePayload> tables = new ArrayList<>();

        if (!focusLeast) {
            ProductPerformanceDto best = sum.bestSellingProduct();
            ProductPerformanceDto least = sum.leastSellingProduct();
            md.append(String.format("### Product Sales Rankings (%s to %s)\n", dash.from(), dash.to()));
            if (best != null) {
                md.append(String.format("- **#1 Best-Selling Product**: **%s** (`%s`) — **%s %s** sold, generating **%s** revenue and **%s** profit (%s%% margin, %s units/day velocity).\n",
                    best.productName(), best.categoryName(), num(best.quantitySold()), best.unit(), inr(best.revenue()), inr(best.profit()), best.marginPct(), best.avgDailyUnits()));
            }
            if (least != null) {
                md.append(String.format("- **Least-Selling Active Product**: **%s** (`%s`) — **%s %s** sold (%s revenue).\n",
                    least.productName(), least.categoryName(), num(least.quantitySold()), least.unit(), inr(least.revenue())));
            }
            md.append(String.format("- **Zero-Sales Products in Period**: **%d** | **Slow-Moving Products**: **%d**\n",
                sum.zeroSalesProductCount(), sum.slowMovingProductCount()));

            List<List<String>> rows = new ArrayList<>();
            for (ProductPerformanceDto p : dash.topByQuantity()) {
                rows.add(List.of(
                    "#" + p.rankByQuantity(),
                    p.productName(),
                    p.categoryName(),
                    num(p.quantitySold()) + " " + p.unit(),
                    inr(p.revenue()),
                    inr(p.profit()),
                    p.avgDailyUnits() + "/day"
                ));
            }
            tables.add(new TablePayload("Top Selling Products by Quantity (" + dash.from() + " to " + dash.to() + ")",
                List.of("Rank", "Product", "Category", "Qty Sold", "Revenue", "Profit", "Velocity"), rows));
        } else {
            ProductPerformanceDto least = sum.leastSellingProduct();
            md.append(String.format("### Least-Selling & Zero-Sales Products (%s to %s)\n", dash.from(), dash.to()));
            if (least != null) {
                md.append(String.format("- **Lowest-Selling Product**: **%s** (`%s`) — **%s %s** sold (**%s** revenue, **%s** current stock).\n",
                    least.productName(), least.categoryName(), num(least.quantitySold()), least.unit(), inr(least.revenue()), num(least.currentStock())));
            }
            md.append(String.format("- **Products with Zero Sales**: **%d** out of **%d** active products.\n",
                sum.zeroSalesProductCount(), sum.totalActiveProducts()));

            List<List<String>> rows = new ArrayList<>();
            for (ProductPerformanceDto p : dash.bottomProducts()) {
                rows.add(List.of(
                    p.productName(),
                    p.categoryName(),
                    num(p.quantitySold()) + " " + p.unit(),
                    inr(p.revenue()),
                    num(p.currentStock()) + " " + p.unit(),
                    p.velocityStatus()
                ));
            }
            tables.add(new TablePayload("Least-Selling Active Products (" + dash.from() + " to " + dash.to() + ")",
                List.of("Product", "Category", "Qty Sold", "Revenue", "Current Stock", "Velocity Status"), rows));
        }

        return new ToolExecutionResult(
            focusLeast ? "LEAST_SELLING_PRODUCTS" : "BEST_SELLING_PRODUCTS",
            false, md.toString(), tables,
            List.of(new NavAction("Open Sales Analytics", "/analytics", "View full product rankings & velocity table")),
            List.of(),
            List.of("Which products have declining sales?", "What is the best-performing category?", "Which products have not sold in 30 days?")
        );
    }

    @Transactional(readOnly = true)
    public ToolExecutionResult getCategoryPerformance(User user) {
        if ("STAFF".equals(roleOf(user))) {
            return denied("CATEGORY_PERFORMANCE", "Category revenue and profitability analytics are available to `OWNER` and `MANAGER` roles.",
                List.of("How do I add a product?", "Which products need restocking?"));
        }
        AnalyticsDashboardResponse dash = analyticsService.getDashboard("LAST_30_DAYS", null, null, null, null, null);
        AnalyticsSummary sum = dash.summary();
        CategoryAnalyticsDto best = sum.bestPerformingCategory();
        CategoryAnalyticsDto worst = sum.lowestPerformingCategory();

        StringBuilder md = new StringBuilder();
        md.append(String.format("### Category Performance Analytics (%s to %s)\n", dash.from(), dash.to()));
        if (best != null) {
            md.append(String.format("- **Best-Performing Category**: **%s** — **%s** revenue (**%s%%** of store revenue), **%s** profit (**%s%%** margin), **%s** units sold.\n",
                best.categoryName(), inr(best.revenue()), best.revenueContributionPct(), inr(best.profit()), best.marginPct(), num(best.quantitySold())));
        }
        if (worst != null) {
            md.append(String.format("- **Lowest-Performing Category**: **%s** — **%s** revenue (**%s%%** share), **%s** profit, **%s** units sold.\n",
                worst.categoryName(), inr(worst.revenue()), worst.revenueContributionPct(), inr(worst.profit()), num(worst.quantitySold())));
        }

        List<List<String>> rows = new ArrayList<>();
        for (CategoryAnalyticsDto c : dash.categories()) {
            rows.add(List.of(
                c.categoryName(),
                String.valueOf(c.productCount()),
                num(c.quantitySold()),
                inr(c.revenue()),
                inr(c.profit()),
                c.marginPct() + "%",
                c.revenueContributionPct() + "%"
            ));
        }

        return new ToolExecutionResult(
            "CATEGORY_PERFORMANCE", false, md.toString(),
            List.of(new TablePayload("Category Revenue & Profit Breakdown (Last 30 Days)",
                List.of("Category", "SKUs", "Qty Sold", "Revenue", "Profit", "Margin %", "Revenue Share"), rows)),
            List.of(new NavAction("Open Category Analytics", "/analytics", "View category charts in Sales Analytics")),
            List.of(),
            List.of("Which product sells the most?", "What is the total profit this month?", "Compare this month with last month")
        );
    }

    @Transactional(readOnly = true)
    public ToolExecutionResult getMonthComparisonAndProfit(User user) {
        if ("STAFF".equals(roleOf(user))) {
            return denied("MONTH_COMPARISON", "Store-wide monthly profit and month-over-month financial comparisons are restricted to `OWNER` and `MANAGER` roles.",
                List.of("What is today's total sales?", "How do I record an expense?"));
        }
        LocalDate today = clock.today();
        LocalDate thisMonthStart = today.withDayOfMonth(1);
        YearMonth lastYm = YearMonth.from(today).minusMonths(1);
        LocalDate lastMonthStart = lastYm.atDay(1);
        LocalDate lastMonthEnd = lastYm.atEndOfMonth();

        FinanceService.Summary thisM = financeService.summary(thisMonthStart, today);
        FinanceService.Summary lastM = financeService.summary(lastMonthStart, lastMonthEnd);

        BigDecimal salesGrowth = pctChange(thisM.totalSales(), lastM.totalSales());
        BigDecimal grossGrowth = pctChange(thisM.grossProfit(), lastM.grossProfit());
        BigDecimal opGrowth = pctChange(thisM.operatingProfit(), lastM.operatingProfit());

        String md = String.format(
            "### Monthly Financial Performance: This Month (%s to %s) vs Last Month (%s to %s)\n" +
            "- **This Month Revenue**: **%s** (%d bills) vs Last Month **%s** (%d bills) → **%s%%**\n" +
            "- **This Month Gross Profit**: **%s** (Net Revenue %s - COGS %s) vs Last Month **%s** → **%s%%**\n" +
            "- **This Month Operating Expenses**: **%s** vs Last Month **%s**\n" +
            "- **This Month Net / Operating Profit**: **%s** vs Last Month **%s** → **%s%%**",
            thisMonthStart, today, lastMonthStart, lastMonthEnd,
            inr(thisM.totalSales()), thisM.billCount(), inr(lastM.totalSales()), lastM.billCount(), fmtSigned(salesGrowth),
            inr(thisM.grossProfit()), inr(thisM.netRevenue()), inr(thisM.cogs()), inr(lastM.grossProfit()), fmtSigned(grossGrowth),
            inr(thisM.expenses()), inr(lastM.expenses()),
            inr(thisM.operatingProfit()), inr(lastM.operatingProfit()), fmtSigned(opGrowth)
        );

        TablePayload table = new TablePayload(
            "This Month vs Last Month Comparison",
            List.of("Financial Metric", "This Month (" + YearMonth.from(today) + ")", "Last Month (" + lastYm + ")", "Change %"),
            List.of(
                List.of("Net Revenue / Total Sales", inr(thisM.totalSales()), inr(lastM.totalSales()), fmtSigned(salesGrowth) + "%"),
                List.of("Completed Bills", String.valueOf(thisM.billCount()), String.valueOf(lastM.billCount()), "—"),
                List.of("Cost of Goods Sold (COGS)", inr(thisM.cogs()), inr(lastM.cogs()), "—"),
                List.of("Gross Profit", inr(thisM.grossProfit()), inr(lastM.grossProfit()), fmtSigned(grossGrowth) + "%"),
                List.of("Operating Expenses", inr(thisM.expenses()), inr(lastM.expenses()), "—"),
                List.of("Net / Operating Profit", inr(thisM.operatingProfit()), inr(lastM.operatingProfit()), fmtSigned(opGrowth) + "%")
            )
        );

        return new ToolExecutionResult(
            "MONTH_COMPARISON_AND_PROFIT", false, md, List.of(table),
            List.of(
                new NavAction("Open Sales Analytics", "/analytics", "Compare monthly sales trends")
            ),
            List.of(
                new DownloadAction("Download This Month P&L CSV", "/reports/pnl.csv?from=" + thisMonthStart + "&to=" + today, "pnl-" + YearMonth.from(today) + ".csv")
            ),
            List.of("What are the highest expenses?", "Which products have declining sales?", "What is my current cash balance?")
        );
    }

    @Transactional(readOnly = true)
    public ToolExecutionResult getDecliningProducts(User user) {
        if ("STAFF".equals(roleOf(user))) {
            return denied("DECLINING_PRODUCTS", "Product sales trend and decline analysis is restricted to `OWNER` and `MANAGER` roles.",
                List.of("Which products need restocking?", "Which products are expiring?"));
        }
        AnalyticsDashboardResponse dash = analyticsService.getDashboard("LAST_30_DAYS", null, null, null, null, null);
        List<ProductPerformanceDto> declining = dash.decliningProducts();

        if (declining.isEmpty()) {
            return new ToolExecutionResult(
                "DECLINING_PRODUCTS", false,
                String.format("### Declining Sales Check (%s to %s vs Previous 30 Days)\n- **0 products** show declining sales in the last 30 days compared to the prior 30-day window (`%s` to `%s`). All active moving SKUs have stable or growing demand!",
                    dash.from(), dash.to(), dash.prevFrom(), dash.prevTo()),
                List.of(),
                List.of(new NavAction("Open Sales Analytics", "/analytics", "Inspect product velocity & growth")),
                List.of(),
                List.of("Which product sells the most?", "Which products have not sold in 30 days?", "Which products need restocking?")
            );
        }

        StringBuilder md = new StringBuilder();
        md.append(String.format("### Products with Declining Sales (%s to %s vs %s to %s)\n", dash.from(), dash.to(), dash.prevFrom(), dash.prevTo()));
        md.append(String.format("- Found **%d** products with lower unit sales compared to the previous period:\n", declining.size()));

        List<List<String>> rows = new ArrayList<>();
        for (ProductPerformanceDto p : declining) {
            rows.add(List.of(
                p.productName(),
                p.categoryName(),
                num(p.prevQuantitySold()) + " " + p.unit(),
                num(p.quantitySold()) + " " + p.unit(),
                fmtSigned(p.salesGrowthPct()) + "%",
                inr(p.revenue())
            ));
        }

        return new ToolExecutionResult(
            "DECLINING_PRODUCTS", false, md.toString(),
            List.of(new TablePayload("Products with Declining Sales",
                List.of("Product", "Category", "Prev Period Qty", "Current Period Qty", "Growth %", "Current Revenue"), rows)),
            List.of(new NavAction("Open Sales Analytics", "/analytics", "View declining products tab")),
            List.of(),
            List.of("Which products have not sold in 30 days?", "Which product sells the least?", "Which products need restocking?")
        );
    }

    // =========================================================================
    // 2. INVENTORY & SUPPLIER TOOLS
    // =========================================================================

    @Transactional(readOnly = true)
    public ToolExecutionResult getRestockingNeeds(User user) {
        List<Map<String, Object>> lowStock = jdbc.queryForList(
            "SELECT p.name, p.sku, COALESCE(c.name, 'Uncategorized') AS category, p.unit, p.current_stock, p.min_stock_level, p.purchase_price " +
            "FROM products p LEFT JOIN categories c ON c.id = p.category_id " +
            "WHERE p.is_active = true AND p.current_stock <= p.min_stock_level " +
            "ORDER BY (p.current_stock - p.min_stock_level) ASC, p.name ASC LIMIT 25",
            new MapSqlParameterSource()
        );

        MapSqlParameterSource cutoffParam = new MapSqlParameterSource(
            "cutoff", clock.odt(Instant.now().minus(30, ChronoUnit.DAYS))
        );
        List<Map<String, Object>> velocityRows = jdbc.queryForList(
            "SELECT p.name, p.unit, p.current_stock, p.min_stock_level, p.purchase_price, " +
            "ROUND(COALESCE((SELECT SUM(si.quantity) FROM sale_items si JOIN sales s ON s.id = si.sale_id WHERE si.product_id = p.id AND s.created_at >= :cutoff), 0) / 30.0, 2) AS avg_daily " +
            "FROM products p WHERE p.is_active = true AND p.current_stock <= GREATEST(p.min_stock_level, 5) ORDER BY p.current_stock ASC LIMIT 10",
            cutoffParam
        );

        StringBuilder md = new StringBuilder();
        md.append("### Inventory Restocking & Reorder Analysis\n");
        md.append(String.format("- **Products at or Below Min Stock Level**: **%d** items\n", lowStock.size()));

        List<TablePayload> tables = new ArrayList<>();
        if (!lowStock.isEmpty()) {
            List<List<String>> rows = new ArrayList<>();
            for (Map<String, Object> r : lowStock) {
                BigDecimal cur = (BigDecimal) r.get("current_stock");
                BigDecimal min = (BigDecimal) r.get("min_stock_level");
                BigDecimal suggested = min.multiply(new BigDecimal("2")).subtract(cur).max(BigDecimal.ONE);
                rows.add(List.of(
                    String.valueOf(r.get("name")),
                    String.valueOf(r.get("category")),
                    num(cur) + " " + r.get("unit"),
                    num(min) + " " + r.get("unit"),
                    num(suggested) + " " + r.get("unit"),
                    inr((BigDecimal) r.get("purchase_price"))
                ));
            }
            tables.add(new TablePayload("Low-Stock Products Needing Immediate Restock",
                List.of("Product", "Category", "Current Stock", "Min Threshold", "Suggested Order Qty", "Unit Cost"), rows));
        } else {
            md.append("- Great news! Every active product is currently above its minimum stock threshold.\n");
            if (!velocityRows.isEmpty()) {
                List<List<String>> vRows = new ArrayList<>();
                for (Map<String, Object> r : velocityRows) {
                    vRows.add(List.of(
                        String.valueOf(r.get("name")),
                        num((BigDecimal) r.get("current_stock")) + " " + r.get("unit"),
                        num((BigDecimal) r.get("min_stock_level")) + " " + r.get("unit"),
                        r.get("avg_daily") + "/day"
                    ));
                }
                tables.add(new TablePayload("Closest-to-Threshold Products",
                    List.of("Product", "Current Stock", "Min Threshold", "30d Daily Velocity"), vRows));
            }
        }

        return new ToolExecutionResult(
            "RESTOCKING_NEEDS", false, md.toString(), tables,
            List.of(
                new NavAction("Open Smart Reorder", "/reorder", "One-click draft Purchase Orders"),
                new NavAction("Open Purchase Orders", "/purchase-orders", "Create supplier PO")
            ),
            List.of(),
            List.of("Which products are expiring?", "Which supplier has the lowest product price?", "How do I create a purchase order?")
        );
    }

    @Transactional(readOnly = true)
    public ToolExecutionResult getExpiringProducts(User user) {
        LocalDate today = clock.today();
        LocalDate horizon = today.plusDays(30);
        MapSqlParameterSource p = new MapSqlParameterSource("horizon", Date.valueOf(horizon));
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT p.name, p.sku, COALESCE(c.name, 'Uncategorized') AS category, p.unit, p.current_stock, p.expiry_date, " +
            "ROUND(p.current_stock * p.purchase_price, 2) AS stock_value " +
            "FROM products p LEFT JOIN categories c ON c.id = p.category_id " +
            "WHERE p.is_active = true AND p.current_stock > 0 AND p.expiry_date IS NOT NULL AND p.expiry_date <= :horizon " +
            "ORDER BY p.expiry_date ASC", p
        );

        if (rows.isEmpty()) {
            return new ToolExecutionResult(
                "EXPIRING_PRODUCTS", false,
                String.format("### Expiry Check (Through %s)\n- **0 active products** in stock are expired or expiring within the next 30 days.", horizon),
                List.of(),
                List.of(new NavAction("Open Inventory", "/inventory", "Inspect inventory batches")),
                List.of(),
                List.of("Which products need restocking?", "What is the total inventory value?", "Which products have not sold in 30 days?")
            );
        }

        long alreadyExpired = rows.stream()
            .filter(r -> ((Date) r.get("expiry_date")).toLocalDate().isBefore(today))
            .count();
        long expiring7d = rows.stream()
            .filter(r -> {
                LocalDate d = ((Date) r.get("expiry_date")).toLocalDate();
                return !d.isBefore(today) && !d.isAfter(today.plusDays(7));
            }).count();

        String md = String.format(
            "### Expiring & Expired Products Alert (As of %s)\n" +
            "- **Already Expired in Stock**: **%d** products\n" +
            "- **Expiring Within 7 Days**: **%d** products\n" +
            "- **Total Expiring Within 30 Days**: **%d** products",
            today, alreadyExpired, expiring7d, rows.size()
        );

        List<List<String>> tRows = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            LocalDate exp = ((Date) r.get("expiry_date")).toLocalDate();
            String status = exp.isBefore(today) ? "EXPIRED" : (!exp.isAfter(today.plusDays(7)) ? "CRITICAL (<=7d)" : "SOON (<=30d)");
            tRows.add(List.of(
                String.valueOf(r.get("name")),
                String.valueOf(r.get("category")),
                num((BigDecimal) r.get("current_stock")) + " " + r.get("unit"),
                exp.toString(),
                status,
                inr((BigDecimal) r.get("stock_value"))
            ));
        }

        return new ToolExecutionResult(
            "EXPIRING_PRODUCTS", false, md,
            List.of(new TablePayload("Products Expiring Within 30 Days",
                List.of("Product", "Category", "Stock on Hand", "Expiry Date", "Status", "Stock Value at Risk"), tRows)),
            List.of(new NavAction("Open Inventory", "/inventory", "Record expiry write-off or discount")),
            List.of(),
            List.of("Which products need restocking?", "What is the total inventory value?", "Which products have not sold in 30 days?")
        );
    }

    @Transactional(readOnly = true)
    public ToolExecutionResult getInventoryValuationAndDeadStock(User user, boolean focusSlowMoving) {
        if ("STAFF".equals(roleOf(user))) {
            return denied("INVENTORY_VALUATION", "Total store inventory valuation and financial stock reports are restricted to `OWNER` and `MANAGER` roles.",
                List.of("Which products need restocking?", "Which products are expiring?"));
        }

        Map<String, Object> inv = jdbc.queryForMap(
            "SELECT COUNT(*) FILTER (WHERE is_active) AS active_skus, " +
            "COALESCE(SUM(current_stock * purchase_price) FILTER (WHERE is_active), 0) AS cost_val, " +
            "COALESCE(SUM(current_stock * selling_price) FILTER (WHERE is_active), 0) AS retail_val " +
            "FROM products",
            new MapSqlParameterSource()
        );
        BigDecimal costVal = (BigDecimal) inv.get("cost_val");
        BigDecimal retailVal = (BigDecimal) inv.get("retail_val");
        long activeSkus = ((Number) inv.get("active_skus")).longValue();

        List<Map<String, Object>> slow30 = financeService.slowMoving(30);

        StringBuilder md = new StringBuilder();
        md.append("### Store Inventory Valuation & 30-Day Unsold Stock\n");
        md.append(String.format("- **Active Products (SKUs)**: **%d**\n", activeSkus));
        md.append(String.format("- **Total Inventory Value (at Purchase Cost)**: **%s**\n", inr(costVal)));
        md.append(String.format("- **Total Retail Value (at Selling Price)**: **%s** (Potential Gross Margin: **%s**)\n",
            inr(retailVal), inr(retailVal.subtract(costVal))));
        md.append(String.format("- **Products with Stock Not Sold in Last 30 Days**: **%d** items\n", slow30.size()));

        List<TablePayload> tables = new ArrayList<>();

        List<Map<String, Object>> catVal = jdbc.queryForList(
            "SELECT COALESCE(c.name, 'Uncategorized') AS category, COUNT(p.id) AS skus, " +
            "COALESCE(SUM(p.current_stock * p.purchase_price), 0) AS val " +
            "FROM products p LEFT JOIN categories c ON c.id = p.category_id " +
            "WHERE p.is_active = true GROUP BY COALESCE(c.name, 'Uncategorized') ORDER BY val DESC",
            new MapSqlParameterSource()
        );
        List<List<String>> cRows = new ArrayList<>();
        for (Map<String, Object> r : catVal) {
            cRows.add(List.of(
                String.valueOf(r.get("category")),
                String.valueOf(r.get("skus")),
                inr((BigDecimal) r.get("val"))
            ));
        }
        if (!focusSlowMoving) {
            tables.add(new TablePayload("Inventory Valuation by Category", List.of("Category", "Active SKUs", "Stock Value (Cost)"), cRows));
        }

        if (!slow30.isEmpty()) {
            List<List<String>> sRows = new ArrayList<>();
            for (Map<String, Object> r : slow30) {
                sRows.add(List.of(
                    String.valueOf(r.get("name")),
                    String.valueOf(r.get("sku")),
                    num((BigDecimal) r.get("currentStock")),
                    inr((BigDecimal) r.get("value")),
                    r.get("lastSold") != null ? String.valueOf(r.get("lastSold")).substring(0, 10) : "Never Sold"
                ));
            }
            tables.add(new TablePayload("Products in Stock with Zero Sales in Last 30 Days",
                List.of("Product", "SKU", "Stock on Hand", "Tied-Up Capital", "Last Sold Date"), sRows));
        }

        return new ToolExecutionResult(
            focusSlowMoving ? "SLOW_MOVING_30_DAYS" : "INVENTORY_VALUATION",
            false, md.toString(), tables,
            List.of(
                new NavAction("Open Inventory", "/inventory", "Manage stock and adjustments"),
                new NavAction("Open Sales Analytics", "/analytics", "View Velocity & Slow Stock tab")
            ),
            List.of(),
            List.of("Which products need restocking?", "Which products are expiring?", "Which supplier has the lowest product price?")
        );
    }

    @Transactional(readOnly = true)
    public ToolExecutionResult getSupplierLowestPrices(User user) {
        if ("STAFF".equals(roleOf(user))) {
            return denied("SUPPLIER_PRICING", "Supplier cost comparison and vendor contracts are restricted to `OWNER` and `MANAGER` roles.",
                List.of("How do I create a purchase order?", "Which products need restocking?"));
        }

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT p.name AS product_name, p.unit, s.name AS supplier_name, sp.last_cost, sp.lead_time_days, sp.preferred " +
            "FROM supplier_products sp " +
            "JOIN products p ON p.id = sp.product_id " +
            "JOIN suppliers s ON s.id = sp.supplier_id " +
            "WHERE p.is_active = true AND s.is_active = true " +
            "ORDER BY sp.last_cost ASC, p.name ASC LIMIT 25",
            new MapSqlParameterSource()
        );

        if (rows.isEmpty()) {
            List<Map<String, Object>> prodCosts = jdbc.queryForList(
                "SELECT p.name AS product_name, p.unit, COALESCE(c.name, 'General') AS category, p.purchase_price " +
                "FROM products p LEFT JOIN categories c ON c.id = p.category_id " +
                "WHERE p.is_active = true ORDER BY p.purchase_price ASC LIMIT 15",
                new MapSqlParameterSource()
            );
            List<List<String>> fallbackRows = new ArrayList<>();
            for (Map<String, Object> r : prodCosts) {
                fallbackRows.add(List.of(
                    String.valueOf(r.get("product_name")),
                    String.valueOf(r.get("category")),
                    String.valueOf(r.get("unit")),
                    inr((BigDecimal) r.get("purchase_price"))
                ));
            }
            return new ToolExecutionResult(
                "SUPPLIER_LOWEST_PRICE", false,
                "### Product Purchase Cost & Supplier Catalog\n" +
                "- No multi-supplier catalog rows are linked in `supplier_products` yet, so here are the **lowest unit purchase cost products** from your active inventory catalog.\n" +
                "- Tip: Link suppliers to products in **Suppliers** (`/suppliers`) or receive a **Purchase Order** (`/purchase-orders`) to populate supplier-specific unit costs automatically.",
                List.of(new TablePayload("Lowest Unit Cost Products", List.of("Product", "Category", "Unit", "Purchase Cost"), fallbackRows)),
                List.of(new NavAction("Open Suppliers", "/suppliers", "Manage suppliers & product costs")),
                List.of(),
                List.of("How much do I owe suppliers?", "Which products need restocking?", "How do I create a purchase order?")
            );
        }

        Map<String, Object> lowest = rows.get(0);
        String md = String.format(
            "### Supplier Product Price Comparison\n" +
            "- **Lowest Unit Supplier Offer**: **%s** supplies **%s** at **%s / %s** (Lead time: %s days).\n" +
            "- Below is the ranked supplier-product cost catalog sorted from lowest price upward:",
            lowest.get("supplier_name"), lowest.get("product_name"),
            inr((BigDecimal) lowest.get("last_cost")), lowest.get("unit"), lowest.get("lead_time_days")
        );

        List<List<String>> tRows = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            tRows.add(List.of(
                String.valueOf(r.get("product_name")),
                String.valueOf(r.get("supplier_name")),
                inr((BigDecimal) r.get("last_cost")) + " / " + r.get("unit"),
                r.get("lead_time_days") + " days",
                Boolean.TRUE.equals(r.get("preferred")) ? "Yes (Preferred)" : "No"
            ));
        }

        return new ToolExecutionResult(
            "SUPPLIER_LOWEST_PRICE", false, md,
            List.of(new TablePayload("Supplier Catalog Ranked by Lowest Price",
                List.of("Product", "Supplier", "Unit Cost", "Lead Time", "Preferred Vendor"), tRows)),
            List.of(new NavAction("Open Suppliers", "/suppliers", "View supplier catalog and ledgers")),
            List.of(),
            List.of("How much do I owe suppliers?", "Which products need restocking?", "How do I create a purchase order?")
        );
    }

    // =========================================================================
    // 3. EMPLOYEE, ATTENDANCE & PAYROLL TOOLS
    // =========================================================================

    @Transactional(readOnly = true)
    public ToolExecutionResult getAttendanceOverview(User user, String rawMessage) {
        LocalDate today = clock.today();
        String currentMonth = YearMonth.from(today).toString();
        String role = roleOf(user);

        if ("STAFF".equals(role)) {
            String qLower = rawMessage == null ? "" : rawMessage.toLowerCase(Locale.ROOT);
            if (qLower.contains("who is absent") || qLower.contains("highest attendance") || qLower.contains("all employees") || qLower.contains("how many employees")) {
                return denied("EMPLOYEE_ATTENDANCE",
                    "As a `STAFF` member, you can view your **own** attendance, leave balance, and payslips, but store-wide employee attendance registers are restricted to `OWNER` and `MANAGER` roles.",
                    List.of("What is my attendance percentage?", "How do I mark attendance?", "How do I approve leave?"));
            }
            AttendanceCalendarResponse cal = hrService.getMonthlyAttendanceCalendar(currentMonth, user.getId());
            EmployeeAttendanceSummaryDto my = cal.employees().isEmpty() ? null : cal.employees().get(0);
            if (my == null) {
                return new ToolExecutionResult("MY_ATTENDANCE", false, "No attendance profile found for your account.", List.of(),
                    List.of(new NavAction("Open HR & Payroll", "/employees", "Check in or view attendance")), List.of(), List.of());
            }
            String md = String.format(
                "### Your Attendance Summary (%s)\n" +
                "- **Employee**: **%s** (`%s`)\n" +
                "- **Attendance Percentage**: **%s%%** (%s present days out of %d working days)\n" +
                "- **Half Days**: %d | **Absent Days**: %s | **Paid Leave Days**: %s\n" +
                "- **Late Arrivals**: %d | **Overtime Hours Recorded**: **%s hrs**",
                currentMonth, my.fullName(), my.employeeCode(), my.attendancePercentage(),
                num(my.presentDays()), my.workingDaysInPeriod(), my.halfDays(),
                num(my.absentDays()), num(my.paidLeaveDays()), my.lateDays(), num(my.totalOvertimeHours())
            );
            return new ToolExecutionResult(
                "MY_ATTENDANCE", false, md,
                List.of(new TablePayload("Your Attendance (" + currentMonth + ")",
                    List.of("Employee", "Present Days", "Absent Days", "Paid Leaves", "Overtime Hrs", "Attendance %"),
                    List.of(List.of(my.fullName(), num(my.presentDays()), num(my.absentDays()), num(my.paidLeaveDays()), num(my.totalOvertimeHours()) + " h", my.attendancePercentage() + "%")))),
                List.of(new NavAction("Open HR & Payroll", "/employees", "View your monthly attendance calendar")),
                List.of(),
                List.of("How do I mark attendance?", "How do I approve leave?")
            );
        }

        HrDashboardResponse hrDash = hrService.getDashboard(currentMonth);
        AttendanceCalendarResponse cal = hrService.getMonthlyAttendanceCalendar(currentMonth, null);

        List<String> presentNames = hrDash.todayAttendanceList().stream()
            .filter(a -> "PRESENT".equals(a.status()) || "HALF_DAY".equals(a.status()))
            .map(a -> a.fullName() + ("HALF_DAY".equals(a.status()) ? " (Half-Day)" : ""))
            .toList();
        List<String> absentNames = hrDash.todayAttendanceList().stream()
            .filter(a -> "ABSENT".equals(a.status()))
            .map(AttendanceDto::fullName)
            .toList();
        List<String> onLeaveNames = hrDash.todayAttendanceList().stream()
            .filter(a -> "LEAVE".equals(a.status()))
            .map(AttendanceDto::fullName)
            .toList();

        EmployeeAttendanceSummaryDto highest = cal.employees().stream()
            .max(Comparator.comparing(EmployeeAttendanceSummaryDto::attendancePercentage)
                .thenComparing(EmployeeAttendanceSummaryDto::presentDays))
            .orElse(null);

        EmployeeAttendanceSummaryDto matchedEmployee = null;
        if (rawMessage != null) {
            String qLower = rawMessage.toLowerCase(Locale.ROOT);
            for (EmployeeAttendanceSummaryDto e : cal.employees()) {
                if ((e.fullName() != null && qLower.contains(e.fullName().toLowerCase(Locale.ROOT))) ||
                    (e.employeeCode() != null && qLower.contains(e.employeeCode().toLowerCase(Locale.ROOT)))) {
                    matchedEmployee = e;
                    break;
                }
            }
        }

        StringBuilder md = new StringBuilder();
        md.append(String.format("### Employee Attendance & Overtime Summary (Today: %s | Month: %s)\n", today, currentMonth));
        md.append(String.format("- **Active Employees**: **%d** (Total: %d)\n", hrDash.activeEmployees(), hrDash.totalEmployees()));
        md.append(String.format("- **Present Today**: **%d** %s\n", hrDash.presentToday(), presentNames.isEmpty() ? "" : "(" + String.join(", ", presentNames) + ")"));
        md.append(String.format("- **Absent Today**: **%d** %s\n", hrDash.absentToday(), absentNames.isEmpty() ? "(None marked absent)" : "(" + String.join(", ", absentNames) + ")"));
        md.append(String.format("- **On Leave Today**: **%d** %s | **Not Marked Yet Today**: **%d**\n",
            hrDash.onLeaveToday(), onLeaveNames.isEmpty() ? "" : "(" + String.join(", ", onLeaveNames) + ")", hrDash.notMarkedToday()));
        md.append(String.format("- **Late Arrivals Today**: **%d**\n", hrDash.lateArrivalsToday()));
        md.append(String.format("- **Store-Wide Monthly Attendance**: **%s%%** | **Total Overtime Recorded (%s)**: **%s hours**\n",
            hrDash.overallAttendancePct(), currentMonth, num(hrDash.totalOvertimeHoursMonth())));

        if (highest != null) {
            md.append(String.format("- **Highest Attendance This Month**: **%s** (`%s`) with **%s%%** attendance (%s present days, %s overtime hrs).\n",
                highest.fullName(), highest.employeeCode(), highest.attendancePercentage(), num(highest.presentDays()), num(highest.totalOvertimeHours())));
        }
        if (matchedEmployee != null) {
            md.append(String.format("\n**Requested Employee Detail — %s (`%s`)**:\n" +
                "- **Attendance Percentage**: **%s%%** (%s Present / %d Working Days)\n" +
                "- **Absent Days**: %s | **Paid Leaves**: %s | **Unpaid Leaves**: %s | **Late Days**: %d | **Overtime**: %s hrs\n",
                matchedEmployee.fullName(), matchedEmployee.employeeCode(), matchedEmployee.attendancePercentage(),
                num(matchedEmployee.presentDays()), matchedEmployee.workingDaysInPeriod(),
                num(matchedEmployee.absentDays()), num(matchedEmployee.paidLeaveDays()),
                num(matchedEmployee.unpaidLeaveDays()), matchedEmployee.lateDays(), num(matchedEmployee.totalOvertimeHours())));
        }

        List<List<String>> rows = new ArrayList<>();
        for (EmployeeAttendanceSummaryDto e : cal.employees()) {
            String todaySt = e.dailyStatusMap().getOrDefault(today.toString(), "NOT_MARKED");
            rows.add(List.of(
                e.employeeCode() + " - " + e.fullName(),
                e.designation() != null ? e.designation() : "—",
                todaySt,
                num(e.presentDays()) + " / " + e.workingDaysInPeriod(),
                num(e.absentDays()),
                num(e.totalOvertimeHours()) + " hrs",
                e.attendancePercentage() + "%"
            ));
        }

        return new ToolExecutionResult(
            "EMPLOYEE_ATTENDANCE_OVERVIEW", false, md.toString(),
            List.of(new TablePayload("Employee Attendance & Overtime (" + currentMonth + ")",
                List.of("Employee", "Designation", "Today Status", "Present / Working Days", "Absent Days", "Overtime", "Attendance %"), rows)),
            List.of(new NavAction("Open HR & Attendance", "/employees", "Manage daily attendance & monthly calendar")),
            List.of(),
            "OWNER".equals(role)
                ? List.of("What is the total salary expenditure?", "Which employee salaries are pending?", "How do I generate payroll?")
                : List.of("How do I mark attendance?", "How do I approve leave?", "What is today's total sales?")
        );
    }

    @Transactional(readOnly = true)
    public ToolExecutionResult getPayrollAndSalarySummary(User user) {
        if (!"OWNER".equals(roleOf(user))) {
            return denied(
                "PAYROLL_CONFIDENTIAL",
                "Confidential salary expenditure and employee payroll records are strictly restricted to the **Store Owner (`OWNER`)** role.",
                List.of("How many employees are present today?", "How do I mark attendance?", "How do I approve leave?")
            );
        }

        String currentMonth = YearMonth.from(clock.today()).toString();
        HrDashboardResponse hrDash = hrService.getDashboard(currentMonth);
        List<PayrollRecordDto> records = hrService.listPayrollRecords(currentMonth, null);

        List<PayrollRecordDto> pendingList = records.stream()
            .filter(r -> r.pendingAmount() != null && r.pendingAmount().compareTo(BigDecimal.ZERO) > 0)
            .toList();

        StringBuilder md = new StringBuilder();
        md.append(String.format("### Payroll & Salary Expenditure Summary (Month: %s)\n", currentMonth));
        md.append(String.format("- **Total Monthly Salary Liability**: **%s**\n", inr(hrDash.totalMonthlySalary())));
        md.append(String.format("- **Salary Disbursed (Paid) So Far**: **%s**\n", inr(hrDash.paidMonthlySalary())));
        md.append(String.format("- **Total Pending Salaries**: **%s** across **%d** employee(s)\n",
            inr(hrDash.pendingSalaries()), pendingList.size()));
        md.append(String.format("- **Total Overtime Pay (%s)**: **%s** (%s overtime hours)\n",
            currentMonth, inr(hrDash.totalOvertimePayMonth()), num(hrDash.totalOvertimeHoursMonth())));

        if (records.isEmpty()) {
            md.append("\n*(Note: Monthly payroll records for `" + currentMonth + "` have not been generated yet. Numbers above reflect active employee base salaries. Go to **HR & Payroll -> Payroll & Payslips** and click **Generate / Recalculate Payroll** to create payslips.)*");
        }

        List<List<String>> rows = new ArrayList<>();
        for (PayrollRecordDto r : records) {
            rows.add(List.of(
                r.employeeCode() + " - " + r.fullName(),
                r.designation() != null ? r.designation() : "—",
                inr(r.basicSalary()),
                inr(r.overtimePay()),
                inr(r.netSalary()),
                inr(r.paidAmount()),
                inr(r.pendingAmount()),
                r.paymentStatus()
            ));
        }

        List<TablePayload> tables = new ArrayList<>();
        if (!rows.isEmpty()) {
            tables.add(new TablePayload("Monthly Payroll Register (" + currentMonth + ")",
                List.of("Employee", "Designation", "Basic Salary", "Overtime Pay", "Net Salary", "Paid", "Pending", "Status"), rows));
        } else {
            List<EmployeeDto> emps = hrService.listEmployees(null, "ACTIVE", null);
            List<List<String>> eRows = new ArrayList<>();
            for (EmployeeDto e : emps) {
                eRows.add(List.of(
                    e.employeeCode() + " - " + e.fullName(),
                    e.designation() != null ? e.designation() : e.role(),
                    e.salaryType(),
                    inr(e.basicSalary()),
                    "NOT_GENERATED_YET"
                ));
            }
            tables.add(new TablePayload("Active Employee Salary Structure (" + currentMonth + ")",
                List.of("Employee", "Designation", "Salary Type", "Monthly Basic Salary", "Payroll Status"), eRows));
        }

        return new ToolExecutionResult(
            "PAYROLL_AND_SALARY_SUMMARY", false, md.toString(), tables,
            List.of(new NavAction("Open Payroll & Payslips", "/employees", "Generate payroll, pay salaries & print payslips")),
            List.of(),
            List.of("How many employees are present today?", "Who has the highest attendance?", "How do I generate payroll?")
        );
    }

    // =========================================================================
    // 4. ACCOUNTING, EXPENSES, SUPPLIER PAYABLES & CASH/UPI BALANCES
    // =========================================================================

    @Transactional(readOnly = true)
    public ToolExecutionResult getAccountingAndFinancialReport(User user, String rawMessage) {
        if ("STAFF".equals(roleOf(user))) {
            return denied(
                "ACCOUNTING_AND_PNL",
                "Store-wide accounting, net profit (P&L), supplier payables, and cash/UPI ledger balances are restricted to `OWNER` and `MANAGER` roles.",
                List.of("What is today's total sales?", "How do I record an expense?", "How do I open or close a cash shift?")
            );
        }

        LocalDate today = clock.today();
        LocalDate monthStart = today.withDayOfMonth(1);
        FinanceService.Summary todaySum = financeService.summary(today, today);
        FinanceService.Summary monthSum = financeService.summary(monthStart, today);
        FinanceService.Pnl monthPnl = financeService.pnl(monthStart, today);

        StringBuilder md = new StringBuilder();
        md.append(String.format("### Store Accounting & Financial Report (As of %s)\n", today));
        md.append(String.format("#### 1. Revenue & Profitability\n" +
            "- **Today's Net Revenue**: **%s** (%d bills) | **Today's Gross Profit**: **%s** | **Today's Net Operating Profit**: **%s**\n" +
            "- **Month-to-Date (%s to %s) Net Revenue**: **%s** (%d bills)\n" +
            "- **Month-to-Date COGS**: **%s** → **Gross Profit**: **%s** (**%s%%** gross margin)\n" +
            "- **Month-to-Date Operating Expenses**: **%s** → **Net / Operating Profit**: **%s**\n\n",
            inr(todaySum.netRevenue()), todaySum.billCount(), inr(todaySum.grossProfit()), inr(todaySum.operatingProfit()),
            monthStart, today, inr(monthSum.netRevenue()), monthSum.billCount(),
            inr(monthSum.cogs()), inr(monthSum.grossProfit()), monthPnl.grossMarginPct(),
            inr(monthSum.expenses()), inr(monthSum.operatingProfit())
        ));

        md.append(String.format("#### 2. Live Cash, UPI & Balance Sheet Positions\n" +
            "- **Current Cash Ledger Balance**: **%s**\n" +
            "- **Current UPI Ledger Balance**: **%s**\n" +
            "- **Total Supplier Payable (Amount Owed to Suppliers)**: **%s**\n" +
            "- **Current Inventory Valuation (at Cost)**: **%s**\n",
            inr(monthSum.cashBalance()), inr(monthSum.upiBalance()),
            inr(monthSum.supplierOutstanding()), inr(monthSum.inventoryValuation())
        ));

        List<TablePayload> tables = new ArrayList<>();
        tables.add(new TablePayload(
            "Monthly Profit & Loss (P&L) Statement (" + monthStart + " to " + today + ")",
            List.of("P&L Line Item", "Today (" + today + ")", "Month-to-Date (" + YearMonth.from(today) + ")"),
            List.of(
                List.of("Net Revenue (Sales - Discounts)", inr(todaySum.netRevenue()), inr(monthSum.netRevenue())),
                List.of("Less: Cost of Goods Sold (COGS)", inr(todaySum.cogs()), inr(monthSum.cogs())),
                List.of("Gross Profit", inr(todaySum.grossProfit()), inr(monthSum.grossProfit()) + " (" + monthPnl.grossMarginPct() + "%)"),
                List.of("Less: Operating Expenses", inr(todaySum.expenses()), inr(monthSum.expenses())),
                List.of("Net / Operating Profit", inr(todaySum.operatingProfit()), inr(monthSum.operatingProfit())),
                List.of("Current Cash Balance (All-Time Ledger)", inr(todaySum.cashBalance()), inr(monthSum.cashBalance())),
                List.of("Current UPI Balance (All-Time Ledger)", inr(todaySum.upiBalance()), inr(monthSum.upiBalance())),
                List.of("Total Owed to Suppliers (Payables)", inr(todaySum.supplierOutstanding()), inr(monthSum.supplierOutstanding()))
            )
        ));

        List<Map<String, Object>> expBreakdown = monthPnl.expensesByCategory();
        String expPeriodLabel = monthStart + " to " + today;
        if (expBreakdown.isEmpty()) {
            expBreakdown = financeService.expenseBreakdown(today.minusDays(90), today);
            expPeriodLabel = "Last 90 Days";
        }
        if (!expBreakdown.isEmpty()) {
            List<List<String>> eRows = new ArrayList<>();
            for (Map<String, Object> e : expBreakdown) {
                eRows.add(List.of(String.valueOf(e.get("category")), inr((BigDecimal) e.get("amount"))));
            }
            tables.add(new TablePayload("Highest Operating Expenses by Category (" + expPeriodLabel + ")",
                List.of("Expense Category", "Total Amount"), eRows));
        }

        List<Map<String, Object>> supBalances = jdbc.queryForList(
            "SELECT s.name, s.phone, " +
            "(s.opening_balance + COALESCE((SELECT SUM(CASE WHEN sl.entry_type IN ('PURCHASE','DEBIT_ADJUSTMENT') THEN sl.amount ELSE -sl.amount END) FROM supplier_ledger sl WHERE sl.supplier_id = s.id), 0)) AS balance " +
            "FROM suppliers s WHERE s.is_active = true ORDER BY balance DESC LIMIT 10",
            new MapSqlParameterSource()
        );
        if (!supBalances.isEmpty()) {
            List<List<String>> sRows = new ArrayList<>();
            for (Map<String, Object> r : supBalances) {
                sRows.add(List.of(
                    String.valueOf(r.get("name")),
                    r.get("phone") != null ? String.valueOf(r.get("phone")) : "—",
                    inr((BigDecimal) r.get("balance"))
                ));
            }
            tables.add(new TablePayload("Supplier Payable Balances (Amount Owed)",
                List.of("Supplier Name", "Phone", "Outstanding Balance"), sRows));
        }

        return new ToolExecutionResult(
            "ACCOUNTING_AND_FINANCIAL_REPORT", false, md.toString(), tables,
            List.of(
                new NavAction("Open Sales Analytics", "/analytics", "View sales & profit trends"),
                new NavAction("Open Expenses", "/expenses", "Inspect operating expenses"),
                new NavAction("Open Suppliers", "/suppliers", "Pay supplier balances")
            ),
            List.of(
                new DownloadAction("Download Monthly P&L CSV", "/reports/pnl.csv?from=" + monthStart + "&to=" + today, "pnl-" + YearMonth.from(today) + ".csv"),
                new DownloadAction("Download Monthly Sales CSV", "/reports/sales.csv?from=" + monthStart + "&to=" + today, "sales-" + YearMonth.from(today) + ".csv"),
                new DownloadAction("Download Monthly Expenses CSV", "/reports/expenses.csv?from=" + monthStart + "&to=" + today, "expenses-" + YearMonth.from(today) + ".csv")
            ),
            List.of("Compare this month with last month", "What is the total inventory value?", "Which product sells the most?")
        );
    }

    private BigDecimal pctChange(BigDecimal current, BigDecimal previous) {
        if (previous == null || previous.compareTo(BigDecimal.ZERO) == 0) {
            return (current != null && current.compareTo(BigDecimal.ZERO) > 0) ? new BigDecimal("100.00") : BigDecimal.ZERO;
        }
        return current.subtract(previous)
            .multiply(new BigDecimal("100"))
            .divide(previous.abs(), 2, RoundingMode.HALF_UP);
    }

    private String fmtSigned(BigDecimal val) {
        if (val == null) return "0.00";
        return (val.compareTo(BigDecimal.ZERO) > 0 ? "+" : "") + val.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
