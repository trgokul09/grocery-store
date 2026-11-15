package com.grocery.analytics;

import com.grocery.analytics.AnalyticsDtos.*;
import com.grocery.common.Exceptions.BusinessException;
import com.grocery.common.StoreClock;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.*;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

@Service
public class AnalyticsService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal SEVEN = new BigDecimal("7");
    private static final BigDecimal THIRTY = new BigDecimal("30");
    private static final BigDecimal SLOW_VELOCITY_THRESHOLD = new BigDecimal("0.50");
    private static final BigDecimal FAST_VELOCITY_THRESHOLD = new BigDecimal("3.00");

    private final NamedParameterJdbcTemplate jdbc;
    private final StoreClock clock;

    public AnalyticsService(NamedParameterJdbcTemplate jdbc, StoreClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Resolves preset date range names into inclusive [from, to] LocalDate pairs in the store's timezone.
     * Supported presets: TODAY, YESTERDAY, LAST_7_DAYS (or WEEK), LAST_30_DAYS, THIS_MONTH (or MONTH), LAST_MONTH, CUSTOM.
     */
    public LocalDate[] resolveRange(String range, LocalDate from, LocalDate to) {
        LocalDate today = clock.today();
        String r = range == null ? "LAST_30_DAYS" : range.trim().toUpperCase();
        return switch (r) {
            case "TODAY" -> new LocalDate[]{today, today};
            case "YESTERDAY" -> new LocalDate[]{today.minusDays(1), today.minusDays(1)};
            case "LAST_7_DAYS", "WEEK" -> new LocalDate[]{today.minusDays(6), today};
            case "LAST_30_DAYS" -> new LocalDate[]{today.minusDays(29), today};
            case "THIS_MONTH", "MONTH" -> new LocalDate[]{today.withDayOfMonth(1), today};
            case "LAST_MONTH" -> {
                LocalDate firstOfLastMonth = today.minusMonths(1).withDayOfMonth(1);
                LocalDate lastOfLastMonth = firstOfLastMonth.with(TemporalAdjusters.lastDayOfMonth());
                yield new LocalDate[]{firstOfLastMonth, lastOfLastMonth};
            }
            case "YEAR", "THIS_YEAR" -> new LocalDate[]{today.withDayOfYear(1), today};
            case "CUSTOM" -> {
                if (from == null || to == null || to.isBefore(from)) {
                    throw new BusinessException("Provide a valid from/to date range");
                }
                yield new LocalDate[]{from, to};
            }
            default -> new LocalDate[]{today.minusDays(29), today};
        };
    }

    private MapSqlParameterSource baseParams(LocalDate from, LocalDate to, Long productId, Long categoryId) {
        long days = Math.max(1L, ChronoUnit.DAYS.between(from, to) + 1);
        LocalDate prevTo = from.minusDays(1);
        LocalDate prevFrom = prevTo.minusDays(days - 1);

        return new MapSqlParameterSource()
            .addValue("from", clock.odt(clock.startOf(from)), Types.TIMESTAMP_WITH_TIMEZONE)
            .addValue("to", clock.odt(clock.startOf(to.plusDays(1))), Types.TIMESTAMP_WITH_TIMEZONE)
            .addValue("prevFrom", clock.odt(clock.startOf(prevFrom)), Types.TIMESTAMP_WITH_TIMEZONE)
            .addValue("prevTo", clock.odt(clock.startOf(prevTo.plusDays(1))), Types.TIMESTAMP_WITH_TIMEZONE)
            .addValue("tz", clock.zone().getId(), Types.VARCHAR)
            .addValue("productId", productId, Types.BIGINT)
            .addValue("categoryId", categoryId, Types.BIGINT);
    }

    /**
     * SQL expression for net quantity on a sale_item after accounting for item returns.
     */
    private static final String NET_QTY_EXPR = "GREATEST(si.quantity - COALESCE(si.returned_qty, 0), 0)";

    /**
     * SQL expression for net line revenue after accounting for item returns and invoice-level refunds.
     */
    private static final String NET_REV_EXPR =
        "(CASE WHEN si.quantity > 0 THEN si.line_total * (" + NET_QTY_EXPR + " / si.quantity) ELSE 0 END) * " +
        "(CASE WHEN s.total > 0 THEN GREATEST(s.total - COALESCE(s.refunded_amount, 0), 0) / s.total ELSE 1 END)";

    /**
     * SQL expression for net line COGS on a sale_item after accounting for item returns.
     */
    private static final String NET_COGS_EXPR = "(si.cost_price * " + NET_QTY_EXPR + ")";

    @Transactional(readOnly = true)
    public AnalyticsDashboardResponse getDashboard(String range, LocalDate reqFrom, LocalDate reqTo,
                                                   String granularity, Long productId, Long categoryId) {
        LocalDate[] resolved = resolveRange(range, reqFrom, reqTo);
        LocalDate from = resolved[0];
        LocalDate to = resolved[1];
        long rangeDays = Math.max(1L, ChronoUnit.DAYS.between(from, to) + 1);
        LocalDate prevTo = from.minusDays(1);
        LocalDate prevFrom = prevTo.minusDays(rangeDays - 1);

        String normGranularity = normalizeGranularity(granularity, rangeDays);
        MapSqlParameterSource params = baseParams(from, to, productId, categoryId);

        // 1. Compute all product performance metrics (includes active products & any products sold in period)
        List<ProductPerformanceDto> allProducts = computeAllProducts(from, to, rangeDays, params);

        // 2. Compute category analytics & category performance over time
        List<CategoryAnalyticsDto> categories = computeCategories(params);
        List<Map<String, Object>> categoryTrendSeries = computeCategoryTrendSeries(from, to, normGranularity, params);

        // 3. Compute time series trends (Daily, Weekly, Monthly, Yearly)
        List<TrendPointDto> dailySeries = computeTimeSeries(from, to, "DAY", params);
        List<TrendPointDto> weeklySeries = computeTimeSeries(from, to, "WEEK", params);
        List<TrendPointDto> monthlySeries = computeTimeSeries(from, to, "MONTH", params);
        List<TrendPointDto> yearlySeries = computeTimeSeries(from, to, "YEAR", params);
        List<TrendPointDto> activeSeries = switch (normGranularity) {
            case "WEEK" -> weeklySeries;
            case "MONTH" -> monthlySeries;
            case "YEAR" -> yearlySeries;
            default -> dailySeries;
        };

        // 4. Payment method & cashier analytics
        List<PaymentMethodAnalyticsDto> paymentMethods = computePaymentMethods(params);
        List<CashierAnalyticsDto> cashiers = computeCashiers(params);

        // 5. Peak sales days & peak sales hours
        List<DayOfWeekSalesDto> salesByDayOfWeek = computeSalesByDayOfWeek(params);
        List<PeakDayDto> topPeakDates = computeTopPeakDates(params, 7);
        List<HourlySalesDto> salesByHour = computeSalesByHour(params);

        // 6. Product rankings & segments
        List<ProductPerformanceDto> topByQuantity = allProducts.stream()
            .filter(p -> p.quantitySold().signum() > 0)
            .sorted(Comparator.comparingInt(ProductPerformanceDto::rankByQuantity))
            .limit(10)
            .toList();

        List<ProductPerformanceDto> topByRevenue = allProducts.stream()
            .filter(p -> p.revenue().signum() > 0)
            .sorted(Comparator.comparingInt(ProductPerformanceDto::rankByRevenue))
            .limit(10)
            .toList();

        List<ProductPerformanceDto> topByProfit = allProducts.stream()
            .filter(p -> p.quantitySold().signum() > 0)
            .sorted(Comparator.comparingInt(ProductPerformanceDto::rankByProfit))
            .limit(10)
            .toList();

        List<ProductPerformanceDto> bottomProducts = allProducts.stream()
            .sorted(Comparator.comparing(ProductPerformanceDto::quantitySold)
                .thenComparing(ProductPerformanceDto::revenue)
                .thenComparing(ProductPerformanceDto::productName, String.CASE_INSENSITIVE_ORDER))
            .limit(10)
            .toList();

        List<ProductPerformanceDto> zeroSalesProducts = allProducts.stream()
            .filter(p -> p.quantitySold().signum() == 0)
            .sorted(Comparator.comparing(ProductPerformanceDto::stockValue).reversed()
                .thenComparing(ProductPerformanceDto::productName, String.CASE_INSENSITIVE_ORDER))
            .toList();

        List<ProductPerformanceDto> decliningProducts = allProducts.stream()
            .filter(ProductPerformanceDto::declining)
            .sorted(Comparator.comparing((ProductPerformanceDto p) -> p.prevQuantitySold().subtract(p.quantitySold())).reversed()
                .thenComparing(ProductPerformanceDto::salesGrowthPct))
            .toList();

        List<ProductPerformanceDto> slowMovingProducts = allProducts.stream()
            .filter(p -> p.currentStock().signum() > 0 && ("ZERO_SALES".equals(p.velocityStatus()) || "SLOW".equals(p.velocityStatus())))
            .sorted(Comparator.comparing(ProductPerformanceDto::quantitySold)
                .thenComparing(Comparator.comparing(ProductPerformanceDto::stockValue).reversed()))
            .toList();

        // 7. Overall Summary KPIs
        AnalyticsSummary summary = buildSummary(
            rangeDays, params, allProducts, categories, topByQuantity, bottomProducts,
            zeroSalesProducts, slowMovingProducts, decliningProducts, topPeakDates, salesByHour
        );

        return new AnalyticsDashboardResponse(
            from, to, prevFrom, prevTo,
            range == null ? "LAST_30_DAYS" : range.toUpperCase(),
            normGranularity,
            productId, categoryId,
            summary,
            activeSeries,
            dailySeries,
            weeklySeries,
            monthlySeries,
            yearlySeries,
            categories,
            categoryTrendSeries,
            topByQuantity,
            topByRevenue,
            topByProfit,
            bottomProducts,
            zeroSalesProducts.stream().limit(25).toList(),
            decliningProducts.stream().limit(25).toList(),
            slowMovingProducts.stream().limit(25).toList(),
            paymentMethods,
            cashiers,
            salesByDayOfWeek,
            topPeakDates,
            salesByHour
        );
    }

    @Transactional(readOnly = true)
    public Page<ProductPerformanceDto> getProductVelocityPage(
            String range, LocalDate reqFrom, LocalDate reqTo,
            Long productId, Long categoryId, String search, String filter,
            String sortBy, String direction, Pageable pageable) {

        LocalDate[] resolved = resolveRange(range, reqFrom, reqTo);
        LocalDate from = resolved[0];
        LocalDate to = resolved[1];
        long rangeDays = Math.max(1L, ChronoUnit.DAYS.between(from, to) + 1);
        MapSqlParameterSource params = baseParams(from, to, productId, categoryId);

        List<ProductPerformanceDto> all = computeAllProducts(from, to, rangeDays, params);

        String q = search == null ? "" : search.trim().toLowerCase();
        String f = filter == null ? "ALL" : filter.trim().toUpperCase();

        List<ProductPerformanceDto> filtered = all.stream()
            .filter(p -> q.isEmpty()
                || p.productName().toLowerCase().contains(q)
                || p.sku().toLowerCase().contains(q)
                || (p.categoryName() != null && p.categoryName().toLowerCase().contains(q)))
            .filter(p -> switch (f) {
                case "ZERO_SALES" -> p.quantitySold().signum() == 0;
                case "SLOW_MOVING" -> p.currentStock().signum() > 0 && ("ZERO_SALES".equals(p.velocityStatus()) || "SLOW".equals(p.velocityStatus()));
                case "DECLINING" -> p.declining();
                case "FAST_MOVING" -> "FAST".equals(p.velocityStatus());
                default -> true;
            })
            .toList();

        Comparator<ProductPerformanceDto> cmp = switch (sortBy == null ? "QUANTITY" : sortBy.trim().toUpperCase()) {
            case "REVENUE" -> Comparator.comparing(ProductPerformanceDto::revenue);
            case "PROFIT" -> Comparator.comparing(ProductPerformanceDto::profit);
            case "MARGIN" -> Comparator.comparing(ProductPerformanceDto::marginPct);
            case "VELOCITY" -> Comparator.comparing(ProductPerformanceDto::avgDailyUnits);
            case "STOCK_COVERAGE" -> Comparator.comparing(
                ProductPerformanceDto::stockCoverageDays,
                Comparator.nullsLast(BigDecimal::compareTo)
            );
            case "STOCK" -> Comparator.comparing(ProductPerformanceDto::currentStock);
            case "GROWTH" -> Comparator.comparing(ProductPerformanceDto::salesGrowthPct);
            case "NAME" -> Comparator.comparing(ProductPerformanceDto::productName, String.CASE_INSENSITIVE_ORDER);
            default -> Comparator.comparing(ProductPerformanceDto::quantitySold)
                .thenComparing(ProductPerformanceDto::revenue);
        };

        boolean desc = direction == null || !"ASC".equalsIgnoreCase(direction.trim());
        if (desc) {
            cmp = cmp.reversed();
        }
        List<ProductPerformanceDto> sorted = filtered.stream().sorted(cmp).toList();

        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), sorted.size());
        List<ProductPerformanceDto> content = start >= sorted.size() ? List.of() : sorted.subList(start, end);
        return new PageImpl<>(content, pageable, sorted.size());
    }

    private AnalyticsSummary buildSummary(
            long rangeDays,
            MapSqlParameterSource params,
            List<ProductPerformanceDto> allProducts,
            List<CategoryAnalyticsDto> categories,
            List<ProductPerformanceDto> topByQuantity,
            List<ProductPerformanceDto> bottomProducts,
            List<ProductPerformanceDto> zeroSalesProducts,
            List<ProductPerformanceDto> slowMovingProducts,
            List<ProductPerformanceDto> decliningProducts,
            List<PeakDayDto> topPeakDates,
            List<HourlySalesDto> salesByHour) {

        String filterClause =
            " AND (:productId IS NULL OR si.product_id = :productId) " +
            " AND (:categoryId IS NULL OR p.category_id = :categoryId) ";

        String curSql =
            "SELECT COALESCE(SUM(" + NET_REV_EXPR + "), 0) AS rev, " +
            "       COALESCE(SUM(" + NET_QTY_EXPR + "), 0) AS qty, " +
            "       COALESCE(SUM(" + NET_COGS_EXPR + "), 0) AS cogs, " +
            "       COALESCE(SUM(si.discount), 0) AS disc, " +
            "       COUNT(DISTINCT s.id) AS bills " +
            "FROM sales s " +
            "JOIN sale_items si ON si.sale_id = s.id " +
            "JOIN products p ON p.id = si.product_id " +
            "WHERE COALESCE(s.status, 'COMPLETED') != 'CANCELLED' " +
            "  AND s.created_at >= :from AND s.created_at < :to " + filterClause;

        String prevSql =
            "SELECT COALESCE(SUM(" + NET_REV_EXPR + "), 0) AS rev, " +
            "       COALESCE(SUM(" + NET_QTY_EXPR + "), 0) AS qty, " +
            "       COALESCE(SUM(" + NET_COGS_EXPR + "), 0) AS cogs, " +
            "       COUNT(DISTINCT s.id) AS bills " +
            "FROM sales s " +
            "JOIN sale_items si ON si.sale_id = s.id " +
            "JOIN products p ON p.id = si.product_id " +
            "WHERE COALESCE(s.status, 'COMPLETED') != 'CANCELLED' " +
            "  AND s.created_at >= :prevFrom AND s.created_at < :prevTo " + filterClause;

        Map<String, Object> cur = jdbc.queryForMap(curSql, params);
        Map<String, Object> prev = jdbc.queryForMap(prevSql, params);

        BigDecimal totalRevenue = scale2((BigDecimal) cur.get("rev"));
        BigDecimal totalQty = scale3((BigDecimal) cur.get("qty"));
        BigDecimal totalCogs = scale2((BigDecimal) cur.get("cogs"));
        BigDecimal totalProfit = scale2(totalRevenue.subtract(totalCogs));
        BigDecimal totalDiscount = scale2((BigDecimal) cur.get("disc"));
        long billCount = ((Number) cur.get("bills")).longValue();

        BigDecimal prevRevenue = scale2((BigDecimal) prev.get("rev"));
        BigDecimal prevQty = scale3((BigDecimal) prev.get("qty"));
        BigDecimal prevCogs = scale2((BigDecimal) prev.get("cogs"));
        BigDecimal prevProfit = scale2(prevRevenue.subtract(prevCogs));
        long prevBillCount = ((Number) prev.get("bills")).longValue();

        BigDecimal revenueGrowthPct = growthPct(totalRevenue, prevRevenue);
        BigDecimal quantityGrowthPct = growthPct(totalQty, prevQty);
        BigDecimal profitGrowthPct = growthPct(totalProfit, prevProfit);
        BigDecimal billGrowthPct = growthPct(BigDecimal.valueOf(billCount), BigDecimal.valueOf(prevBillCount));
        BigDecimal grossMarginPct = pct(totalProfit, totalRevenue);

        BigDecimal avgOrderValue = billCount > 0
            ? totalRevenue.divide(BigDecimal.valueOf(billCount), 2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal avgBasketSize = billCount > 0
            ? totalQty.divide(BigDecimal.valueOf(billCount), 2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        ProductPerformanceDto bestSelling = topByQuantity.isEmpty() ? null : topByQuantity.getFirst();
        ProductPerformanceDto leastSelling = bottomProducts.isEmpty() ? null : bottomProducts.getFirst();

        CategoryAnalyticsDto bestCategory = categories.stream()
            .filter(c -> c.revenue().signum() > 0)
            .max(Comparator.comparing(CategoryAnalyticsDto::revenue).thenComparing(CategoryAnalyticsDto::profit))
            .orElse(null);

        CategoryAnalyticsDto lowestCategory = categories.isEmpty() ? null : categories.stream()
            .min(Comparator.comparing(CategoryAnalyticsDto::revenue)
                .thenComparing(CategoryAnalyticsDto::quantitySold)
                .thenComparing(CategoryAnalyticsDto::categoryName, String.CASE_INSENSITIVE_ORDER))
            .orElse(null);

        PeakDayDto peakDay = topPeakDates.isEmpty() ? null : topPeakDates.getFirst();
        HourlySalesDto peakHour = salesByHour.stream()
            .filter(h -> h.revenue().signum() > 0)
            .max(Comparator.comparing(HourlySalesDto::revenue).thenComparingLong(HourlySalesDto::billCount))
            .orElse(null);

        return new AnalyticsSummary(
            totalRevenue, prevRevenue, revenueGrowthPct,
            totalQty, prevQty, quantityGrowthPct,
            totalCogs, totalProfit, prevProfit, profitGrowthPct,
            grossMarginPct, totalDiscount,
            billCount, prevBillCount, billGrowthPct,
            avgOrderValue, avgBasketSize, rangeDays,
            bestSelling, leastSelling,
            bestCategory, lowestCategory,
            peakDay, peakHour,
            allProducts.size(),
            zeroSalesProducts.size(),
            slowMovingProducts.size(),
            decliningProducts.size()
        );
    }

    private List<ProductPerformanceDto> computeAllProducts(LocalDate from, LocalDate to, long rangeDays, MapSqlParameterSource params) {
        String sql =
            "WITH cur_sales AS ( " +
            "  SELECT si.product_id, " +
            "         COALESCE(SUM(" + NET_QTY_EXPR + "), 0) AS qty, " +
            "         COALESCE(SUM(" + NET_REV_EXPR + "), 0) AS rev, " +
            "         COALESCE(SUM(" + NET_COGS_EXPR + "), 0) AS cogs, " +
            "         COALESCE(SUM(si.discount), 0) AS disc " +
            "  FROM sale_items si " +
            "  JOIN sales s ON s.id = si.sale_id " +
            "  WHERE COALESCE(s.status, 'COMPLETED') != 'CANCELLED' " +
            "    AND s.created_at >= :from AND s.created_at < :to " +
            "  GROUP BY si.product_id " +
            "), " +
            "prev_sales AS ( " +
            "  SELECT si.product_id, " +
            "         COALESCE(SUM(" + NET_QTY_EXPR + "), 0) AS prev_qty, " +
            "         COALESCE(SUM(" + NET_REV_EXPR + "), 0) AS prev_rev " +
            "  FROM sale_items si " +
            "  JOIN sales s ON s.id = si.sale_id " +
            "  WHERE COALESCE(s.status, 'COMPLETED') != 'CANCELLED' " +
            "    AND s.created_at >= :prevFrom AND s.created_at < :prevTo " +
            "  GROUP BY si.product_id " +
            "), " +
            "last_sold AS ( " +
            "  SELECT si.product_id, MAX(s.created_at) AS last_sold_at " +
            "  FROM sale_items si " +
            "  JOIN sales s ON s.id = si.sale_id " +
            "  WHERE COALESCE(s.status, 'COMPLETED') != 'CANCELLED' " +
            "    AND " + NET_QTY_EXPR + " > 0 " +
            "  GROUP BY si.product_id " +
            ") " +
            "SELECT p.id AS product_id, p.name AS product_name, p.sku, p.category_id, " +
            "       COALESCE(c.name, 'Uncategorized') AS category_name, p.unit, " +
            "       p.current_stock, p.min_stock_level, p.purchase_price, p.selling_price, p.created_at, " +
            "       COALESCE(cs.qty, 0) AS qty, COALESCE(cs.rev, 0) AS rev, " +
            "       COALESCE(cs.cogs, 0) AS cogs, COALESCE(cs.disc, 0) AS disc, " +
            "       COALESCE(ps.prev_qty, 0) AS prev_qty, COALESCE(ps.prev_rev, 0) AS prev_rev, " +
            "       ls.last_sold_at " +
            "FROM products p " +
            "LEFT JOIN categories c ON c.id = p.category_id " +
            "LEFT JOIN cur_sales cs ON cs.product_id = p.id " +
            "LEFT JOIN prev_sales ps ON ps.product_id = p.id " +
            "LEFT JOIN last_sold ls ON ls.product_id = p.id " +
            "WHERE (p.is_active = TRUE OR COALESCE(cs.qty, 0) > 0) " +
            "  AND (:productId IS NULL OR p.id = :productId) " +
            "  AND (:categoryId IS NULL OR p.category_id = :categoryId) " +
            "ORDER BY p.id";

        List<Map<String, Object>> rows = jdbc.queryForList(sql, params);

        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalProfit = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            BigDecimal rev = scale2((BigDecimal) r.get("rev"));
            BigDecimal cogs = scale2((BigDecimal) r.get("cogs"));
            BigDecimal prof = rev.subtract(cogs);
            totalRevenue = totalRevenue.add(rev);
            if (prof.signum() > 0) {
                totalProfit = totalProfit.add(prof);
            }
        }

        LocalDate today = clock.today();
        long availableSellingDays = Math.max(1L, rangeDays);
        BigDecimal sellingDaysBd = BigDecimal.valueOf(availableSellingDays);

        List<ProductPerformanceDto> preliminary = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            Long id = ((Number) r.get("product_id")).longValue();
            String name = (String) r.get("product_name");
            String sku = (String) r.get("sku");
            Long catId = r.get("category_id") == null ? null : ((Number) r.get("category_id")).longValue();
            String catName = (String) r.get("category_name");
            String unit = (String) r.get("unit");

            BigDecimal currentStock = scale3((BigDecimal) r.get("current_stock"));
            BigDecimal minStock = scale3((BigDecimal) r.get("min_stock_level"));
            BigDecimal purchasePrice = scale2((BigDecimal) r.get("purchase_price"));
            BigDecimal sellingPrice = scale2((BigDecimal) r.get("selling_price"));
            BigDecimal stockValue = scale2(currentStock.multiply(purchasePrice));

            BigDecimal qty = scale3((BigDecimal) r.get("qty"));
            BigDecimal rev = scale2((BigDecimal) r.get("rev"));
            BigDecimal cogs = scale2((BigDecimal) r.get("cogs"));
            BigDecimal profit = scale2(rev.subtract(cogs));
            BigDecimal marginPct = pct(profit, rev);
            BigDecimal disc = scale2((BigDecimal) r.get("disc"));

            BigDecimal revShare = pct(rev, totalRevenue);
            BigDecimal profitShare = profit.signum() > 0 ? pct(profit, totalProfit) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

            BigDecimal avgDailyUnits = qty.divide(sellingDaysBd, 3, RoundingMode.HALF_UP);
            BigDecimal avgWeeklyUnits = avgDailyUnits.multiply(SEVEN).setScale(2, RoundingMode.HALF_UP);
            BigDecimal avgMonthlyUnits = avgDailyUnits.multiply(THIRTY).setScale(2, RoundingMode.HALF_UP);
            BigDecimal dailyRevenueVelocity = rev.divide(sellingDaysBd, 2, RoundingMode.HALF_UP);

            BigDecimal stockCoverageDays;
            if (currentStock.signum() == 0) {
                stockCoverageDays = BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
            } else if (avgDailyUnits.signum() > 0) {
                stockCoverageDays = currentStock.divide(avgDailyUnits, 1, RoundingMode.HALF_UP);
            } else {
                stockCoverageDays = null; // Infinite coverage due to 0 velocity
            }

            String velocityStatus;
            if (qty.signum() == 0) {
                velocityStatus = "ZERO_SALES";
            } else if (avgDailyUnits.compareTo(SLOW_VELOCITY_THRESHOLD) < 0
                    || (stockCoverageDays != null && stockCoverageDays.compareTo(new BigDecimal("60")) > 0)) {
                velocityStatus = "SLOW";
            } else if (avgDailyUnits.compareTo(FAST_VELOCITY_THRESHOLD) >= 0) {
                velocityStatus = "FAST";
            } else {
                velocityStatus = "MODERATE";
            }

            BigDecimal prevQty = scale3((BigDecimal) r.get("prev_qty"));
            BigDecimal prevRev = scale2((BigDecimal) r.get("prev_rev"));
            BigDecimal salesGrowthPct = growthPct(qty, prevQty);
            boolean declining = prevQty.signum() > 0 && qty.compareTo(prevQty) < 0;

            Instant lastSoldAt = toInstant(r.get("last_sold_at"));
            Long daysSinceLastSale = lastSoldAt == null
                ? null
                : Math.max(0L, ChronoUnit.DAYS.between(clock.toLocal(lastSoldAt), today));

            preliminary.add(new ProductPerformanceDto(
                id, name, sku, catId, catName, unit,
                currentStock, minStock, purchasePrice, sellingPrice, stockValue,
                qty, rev, cogs, profit, marginPct, disc,
                revShare, profitShare,
                availableSellingDays, avgDailyUnits, avgWeeklyUnits, avgMonthlyUnits, dailyRevenueVelocity,
                stockCoverageDays, velocityStatus,
                prevQty, prevRev, salesGrowthPct, declining,
                lastSoldAt, daysSinceLastSale,
                0, 0, 0
            ));
        }

        // Assign ranks by Quantity, Revenue, and Profit
        List<Long> byQtyOrder = preliminary.stream()
            .sorted(Comparator.comparing(ProductPerformanceDto::quantitySold).reversed()
                .thenComparing(Comparator.comparing(ProductPerformanceDto::revenue).reversed())
                .thenComparing(ProductPerformanceDto::productName, String.CASE_INSENSITIVE_ORDER))
            .map(ProductPerformanceDto::productId)
            .toList();

        List<Long> byRevOrder = preliminary.stream()
            .sorted(Comparator.comparing(ProductPerformanceDto::revenue).reversed()
                .thenComparing(Comparator.comparing(ProductPerformanceDto::quantitySold).reversed())
                .thenComparing(ProductPerformanceDto::productName, String.CASE_INSENSITIVE_ORDER))
            .map(ProductPerformanceDto::productId)
            .toList();

        List<Long> byProfOrder = preliminary.stream()
            .sorted(Comparator.comparing(ProductPerformanceDto::profit).reversed()
                .thenComparing(Comparator.comparing(ProductPerformanceDto::revenue).reversed())
                .thenComparing(ProductPerformanceDto::productName, String.CASE_INSENSITIVE_ORDER))
            .map(ProductPerformanceDto::productId)
            .toList();

        Map<Long, Integer> qtyRanks = new HashMap<>();
        Map<Long, Integer> revRanks = new HashMap<>();
        Map<Long, Integer> profRanks = new HashMap<>();
        for (int i = 0; i < byQtyOrder.size(); i++) qtyRanks.put(byQtyOrder.get(i), i + 1);
        for (int i = 0; i < byRevOrder.size(); i++) revRanks.put(byRevOrder.get(i), i + 1);
        for (int i = 0; i < byProfOrder.size(); i++) profRanks.put(byProfOrder.get(i), i + 1);

        return preliminary.stream()
            .map(p -> new ProductPerformanceDto(
                p.productId(), p.productName(), p.sku(), p.categoryId(), p.categoryName(), p.unit(),
                p.currentStock(), p.minStockLevel(), p.purchasePrice(), p.sellingPrice(), p.stockValue(),
                p.quantitySold(), p.revenue(), p.cogs(), p.profit(), p.marginPct(), p.discountGiven(),
                p.revenueContributionPct(), p.profitContributionPct(),
                p.availableSellingDays(), p.avgDailyUnits(), p.avgWeeklyUnits(), p.avgMonthlyUnits(), p.dailyRevenueVelocity(),
                p.stockCoverageDays(), p.velocityStatus(),
                p.prevQuantitySold(), p.prevRevenue(), p.salesGrowthPct(), p.declining(),
                p.lastSoldAt(), p.daysSinceLastSale(),
                qtyRanks.getOrDefault(p.productId(), 0),
                revRanks.getOrDefault(p.productId(), 0),
                profRanks.getOrDefault(p.productId(), 0)
            ))
            .sorted(Comparator.comparingInt(ProductPerformanceDto::rankByRevenue))
            .toList();
    }

    private List<CategoryAnalyticsDto> computeCategories(MapSqlParameterSource params) {
        String sql =
            "WITH all_cats AS ( " +
            "  SELECT id AS category_id, name AS category_name FROM categories WHERE is_active = TRUE " +
            "  UNION " +
            "  SELECT CAST(NULL AS BIGINT) AS category_id, 'Uncategorized' AS category_name " +
            "  WHERE EXISTS (SELECT 1 FROM products WHERE category_id IS NULL AND is_active = TRUE) " +
            "), " +
            "prod_counts AS ( " +
            "  SELECT category_id, COUNT(*) AS prod_cnt FROM products WHERE is_active = TRUE GROUP BY category_id " +
            "), " +
            "cur_cat AS ( " +
            "  SELECT p.category_id, " +
            "         COUNT(DISTINCT s.id) AS bills, " +
            "         COALESCE(SUM(" + NET_QTY_EXPR + "), 0) AS qty, " +
            "         COALESCE(SUM(" + NET_REV_EXPR + "), 0) AS rev, " +
            "         COALESCE(SUM(" + NET_COGS_EXPR + "), 0) AS cogs " +
            "  FROM sale_items si " +
            "  JOIN sales s ON s.id = si.sale_id " +
            "  JOIN products p ON p.id = si.product_id " +
            "  WHERE COALESCE(s.status, 'COMPLETED') != 'CANCELLED' " +
            "    AND s.created_at >= :from AND s.created_at < :to " +
            "    AND (:productId IS NULL OR si.product_id = :productId) " +
            "  GROUP BY p.category_id " +
            "), " +
            "prev_cat AS ( " +
            "  SELECT p.category_id, " +
            "         COALESCE(SUM(" + NET_QTY_EXPR + "), 0) AS prev_qty, " +
            "         COALESCE(SUM(" + NET_REV_EXPR + "), 0) AS prev_rev, " +
            "         COALESCE(SUM(" + NET_COGS_EXPR + "), 0) AS prev_cogs " +
            "  FROM sale_items si " +
            "  JOIN sales s ON s.id = si.sale_id " +
            "  JOIN products p ON p.id = si.product_id " +
            "  WHERE COALESCE(s.status, 'COMPLETED') != 'CANCELLED' " +
            "    AND s.created_at >= :prevFrom AND s.created_at < :prevTo " +
            "    AND (:productId IS NULL OR si.product_id = :productId) " +
            "  GROUP BY p.category_id " +
            ") " +
            "SELECT ac.category_id, ac.category_name, " +
            "       COALESCE(pc.prod_cnt, 0) AS product_count, " +
            "       COALESCE(cc.bills, 0) AS bill_count, " +
            "       COALESCE(cc.qty, 0) AS qty, " +
            "       COALESCE(cc.rev, 0) AS rev, " +
            "       COALESCE(cc.cogs, 0) AS cogs, " +
            "       COALESCE(pv.prev_qty, 0) AS prev_qty, " +
            "       COALESCE(pv.prev_rev, 0) AS prev_rev, " +
            "       COALESCE(pv.prev_cogs, 0) AS prev_cogs " +
            "FROM all_cats ac " +
            "LEFT JOIN prod_counts pc ON pc.category_id IS NOT DISTINCT FROM ac.category_id " +
            "LEFT JOIN cur_cat cc ON cc.category_id IS NOT DISTINCT FROM ac.category_id " +
            "LEFT JOIN prev_cat pv ON pv.category_id IS NOT DISTINCT FROM ac.category_id " +
            "WHERE (:categoryId IS NULL OR ac.category_id = :categoryId) " +
            "ORDER BY COALESCE(cc.rev, 0) DESC, ac.category_name ASC";

        List<Map<String, Object>> rows = jdbc.queryForList(sql, params);

        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalProfit = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            BigDecimal rev = scale2((BigDecimal) r.get("rev"));
            BigDecimal cogs = scale2((BigDecimal) r.get("cogs"));
            BigDecimal prof = rev.subtract(cogs);
            totalRevenue = totalRevenue.add(rev);
            if (prof.signum() > 0) {
                totalProfit = totalProfit.add(prof);
            }
        }

        List<CategoryAnalyticsDto> result = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            Long catId = r.get("category_id") == null ? null : ((Number) r.get("category_id")).longValue();
            String catName = (String) r.get("category_name");
            int productCount = ((Number) r.get("product_count")).intValue();
            long billCount = ((Number) r.get("bill_count")).longValue();

            BigDecimal qty = scale3((BigDecimal) r.get("qty"));
            BigDecimal rev = scale2((BigDecimal) r.get("rev"));
            BigDecimal cogs = scale2((BigDecimal) r.get("cogs"));
            BigDecimal profit = scale2(rev.subtract(cogs));
            BigDecimal marginPct = pct(profit, rev);

            BigDecimal revContribution = pct(rev, totalRevenue);
            BigDecimal profContribution = profit.signum() > 0 ? pct(profit, totalProfit) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

            BigDecimal prevQty = scale3((BigDecimal) r.get("prev_qty"));
            BigDecimal prevRev = scale2((BigDecimal) r.get("prev_rev"));
            BigDecimal prevCogs = scale2((BigDecimal) r.get("prev_cogs"));
            BigDecimal prevProfit = scale2(prevRev.subtract(prevCogs));

            BigDecimal revGrowth = growthPct(rev, prevRev);
            BigDecimal profGrowth = growthPct(profit, prevProfit);

            result.add(new CategoryAnalyticsDto(
                catId, catName, productCount, billCount,
                qty, rev, cogs, profit, marginPct,
                revContribution, profContribution,
                prevQty, prevRev, prevProfit, revGrowth, profGrowth
            ));
        }
        return result;
    }

    private List<Map<String, Object>> computeCategoryTrendSeries(LocalDate from, LocalDate to, String granularity, MapSqlParameterSource params) {
        String truncUnit = switch (granularity) {
            case "WEEK" -> "week";
            case "MONTH" -> "month";
            case "YEAR" -> "year";
            default -> "day";
        };
        String fmt = switch (granularity) {
            case "MONTH" -> "YYYY-MM";
            case "YEAR" -> "YYYY";
            default -> "YYYY-MM-DD";
        };

        String sql =
            "SELECT to_char(date_trunc('" + truncUnit + "', timezone(:tz, s.created_at)), '" + fmt + "') AS bucket, " +
            "       COALESCE(c.name, 'Uncategorized') AS category_name, " +
            "       COALESCE(SUM(" + NET_REV_EXPR + "), 0) AS rev " +
            "FROM sale_items si " +
            "JOIN sales s ON s.id = si.sale_id " +
            "JOIN products p ON p.id = si.product_id " +
            "LEFT JOIN categories c ON c.id = p.category_id " +
            "WHERE COALESCE(s.status, 'COMPLETED') != 'CANCELLED' " +
            "  AND s.created_at >= :from AND s.created_at < :to " +
            "  AND (:productId IS NULL OR si.product_id = :productId) " +
            "  AND (:categoryId IS NULL OR p.category_id = :categoryId) " +
            "GROUP BY 1, 2 " +
            "ORDER BY 1 ASC, 2 ASC";

        List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
        Map<String, Map<String, Object>> byBucket = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String bucket = (String) r.get("bucket");
            String catName = (String) r.get("category_name");
            BigDecimal rev = scale2((BigDecimal) r.get("rev"));
            Map<String, Object> point = byBucket.computeIfAbsent(bucket, b -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("bucket", b);
                return m;
            });
            point.put(catName, rev);
        }
        return new ArrayList<>(byBucket.values());
    }

    private List<TrendPointDto> computeTimeSeries(LocalDate from, LocalDate to, String granularity, MapSqlParameterSource baseParams) {
        String truncUnit = switch (granularity) {
            case "WEEK" -> "week";
            case "MONTH" -> "month";
            case "YEAR" -> "year";
            default -> "day";
        };
        String fmt = switch (granularity) {
            case "MONTH" -> "YYYY-MM";
            case "YEAR" -> "YYYY";
            default -> "YYYY-MM-DD";
        };

        // For YEAR or MONTH or WEEK, if the user selected a very narrow date range (like TODAY),
        // we still query the selected [from, to] for DAY, and for WEEK/MONTH/YEAR we query [from, to]
        String sql =
            "SELECT to_char(date_trunc('" + truncUnit + "', timezone(:tz, s.created_at)), '" + fmt + "') AS bucket, " +
            "       COALESCE(SUM(" + NET_REV_EXPR + "), 0) AS rev, " +
            "       COALESCE(SUM(" + NET_COGS_EXPR + "), 0) AS cogs, " +
            "       COALESCE(SUM(" + NET_QTY_EXPR + "), 0) AS qty, " +
            "       COUNT(DISTINCT s.id) AS bills " +
            "FROM sales s " +
            "JOIN sale_items si ON si.sale_id = s.id " +
            "JOIN products p ON p.id = si.product_id " +
            "WHERE COALESCE(s.status, 'COMPLETED') != 'CANCELLED' " +
            "  AND s.created_at >= :from AND s.created_at < :to " +
            "  AND (:productId IS NULL OR si.product_id = :productId) " +
            "  AND (:categoryId IS NULL OR p.category_id = :categoryId) " +
            "GROUP BY 1 " +
            "ORDER BY 1 ASC";

        List<Map<String, Object>> rows = jdbc.queryForList(sql, baseParams);

        Map<String, Map<String, Object>> byBucket = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            byBucket.put((String) r.get("bucket"), r);
        }

        List<Map<String, Object>> normalizedRows = new ArrayList<>();
        if ("DAY".equals(granularity) && ChronoUnit.DAYS.between(from, to) <= 366) {
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                String b = d.toString();
                Map<String, Object> r = byBucket.get(b);
                if (r == null) {
                    r = Map.of(
                        "bucket", b,
                        "rev", BigDecimal.ZERO,
                        "cogs", BigDecimal.ZERO,
                        "qty", BigDecimal.ZERO,
                        "bills", 0L
                    );
                }
                normalizedRows.add(r);
            }
        } else {
            normalizedRows.addAll(rows);
        }

        List<TrendPointDto> series = new ArrayList<>(normalizedRows.size());
        BigDecimal prevRev = null;
        for (Map<String, Object> r : normalizedRows) {
            String bucket = (String) r.get("bucket");
            BigDecimal rev = scale2((BigDecimal) r.get("rev"));
            BigDecimal cogs = scale2((BigDecimal) r.get("cogs"));
            BigDecimal profit = scale2(rev.subtract(cogs));
            BigDecimal marginPct = pct(profit, rev);
            BigDecimal qty = scale3((BigDecimal) r.get("qty"));
            long bills = ((Number) r.get("bills")).longValue();
            BigDecimal aov = bills > 0
                ? rev.divide(BigDecimal.valueOf(bills), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            BigDecimal growth = prevRev == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : growthPct(rev, prevRev);
            prevRev = rev;

            series.add(new TrendPointDto(
                bucket,
                formatBucketLabel(bucket, granularity),
                rev, cogs, profit, marginPct, qty, bills, aov, growth
            ));
        }
        return series;
    }

    private List<PaymentMethodAnalyticsDto> computePaymentMethods(MapSqlParameterSource params) {
        // Allocates payment methods accurately even if product/category filters are active
        String sql =
            "WITH filtered_sale_totals AS ( " +
            "  SELECT s.id AS sale_id, s.total AS sale_total, " +
            "         COALESCE(SUM(" + NET_REV_EXPR + "), 0) AS filtered_rev " +
            "  FROM sales s " +
            "  JOIN sale_items si ON si.sale_id = s.id " +
            "  JOIN products p ON p.id = si.product_id " +
            "  WHERE COALESCE(s.status, 'COMPLETED') != 'CANCELLED' " +
            "    AND s.created_at >= :from AND s.created_at < :to " +
            "    AND (:productId IS NULL OR si.product_id = :productId) " +
            "    AND (:categoryId IS NULL OR p.category_id = :categoryId) " +
            "  GROUP BY s.id, s.total " +
            ") " +
            "SELECT sp.method, " +
            "       COALESCE(SUM(CASE WHEN fst.sale_total > 0 THEN sp.amount * (fst.filtered_rev / fst.sale_total) ELSE 0 END), 0) AS amount, " +
            "       COUNT(DISTINCT sp.sale_id) AS tx_count " +
            "FROM sale_payments sp " +
            "JOIN filtered_sale_totals fst ON fst.sale_id = sp.sale_id " +
            "GROUP BY sp.method " +
            "ORDER BY amount DESC";

        List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
        BigDecimal total = rows.stream()
            .map(r -> scale2((BigDecimal) r.get("amount")))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        return rows.stream().map(r -> {
            String method = (String) r.get("method");
            BigDecimal amount = scale2((BigDecimal) r.get("amount"));
            long count = ((Number) r.get("tx_count")).longValue();
            return new PaymentMethodAnalyticsDto(method, amount, count, pct(amount, total));
        }).toList();
    }

    private List<CashierAnalyticsDto> computeCashiers(MapSqlParameterSource params) {
        String sql =
            "SELECT u.id AS cashier_id, u.full_name AS cashier_name, " +
            "       COUNT(DISTINCT s.id) AS bills, " +
            "       COALESCE(SUM(" + NET_QTY_EXPR + "), 0) AS qty, " +
            "       COALESCE(SUM(" + NET_REV_EXPR + "), 0) AS rev, " +
            "       COALESCE(SUM(si.discount), 0) AS disc, " +
            "       COALESCE(SUM(" + NET_COGS_EXPR + "), 0) AS cogs " +
            "FROM sales s " +
            "JOIN users u ON u.id = s.cashier_id " +
            "JOIN sale_items si ON si.sale_id = s.id " +
            "JOIN products p ON p.id = si.product_id " +
            "WHERE COALESCE(s.status, 'COMPLETED') != 'CANCELLED' " +
            "  AND s.created_at >= :from AND s.created_at < :to " +
            "  AND (:productId IS NULL OR si.product_id = :productId) " +
            "  AND (:categoryId IS NULL OR p.category_id = :categoryId) " +
            "GROUP BY u.id, u.full_name " +
            "ORDER BY rev DESC";

        List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
        BigDecimal totalRev = rows.stream()
            .map(r -> scale2((BigDecimal) r.get("rev")))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        return rows.stream().map(r -> {
            Long id = ((Number) r.get("cashier_id")).longValue();
            String name = (String) r.get("cashier_name");
            long bills = ((Number) r.get("bills")).longValue();
            BigDecimal qty = scale3((BigDecimal) r.get("qty"));
            BigDecimal rev = scale2((BigDecimal) r.get("rev"));
            BigDecimal disc = scale2((BigDecimal) r.get("disc"));
            BigDecimal cogs = scale2((BigDecimal) r.get("cogs"));
            BigDecimal profit = scale2(rev.subtract(cogs));
            BigDecimal avgBill = bills > 0
                ? rev.divide(BigDecimal.valueOf(bills), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            return new CashierAnalyticsDto(id, name, bills, qty, rev, disc, profit, avgBill, pct(rev, totalRev));
        }).toList();
    }

    private List<DayOfWeekSalesDto> computeSalesByDayOfWeek(MapSqlParameterSource params) {
        String sql =
            "SELECT EXTRACT(ISODOW FROM timezone(:tz, s.created_at))::int AS dow, " +
            "       COALESCE(SUM(" + NET_REV_EXPR + "), 0) AS rev, " +
            "       COALESCE(SUM(" + NET_COGS_EXPR + "), 0) AS cogs, " +
            "       COALESCE(SUM(" + NET_QTY_EXPR + "), 0) AS qty, " +
            "       COUNT(DISTINCT s.id) AS bills " +
            "FROM sales s " +
            "JOIN sale_items si ON si.sale_id = s.id " +
            "JOIN products p ON p.id = si.product_id " +
            "WHERE COALESCE(s.status, 'COMPLETED') != 'CANCELLED' " +
            "  AND s.created_at >= :from AND s.created_at < :to " +
            "  AND (:productId IS NULL OR si.product_id = :productId) " +
            "  AND (:categoryId IS NULL OR p.category_id = :categoryId) " +
            "GROUP BY 1 " +
            "ORDER BY 1 ASC";

        List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
        Map<Integer, Map<String, Object>> byDow = new HashMap<>();
        for (Map<String, Object> r : rows) {
            byDow.put(((Number) r.get("dow")).intValue(), r);
        }

        List<DayOfWeekSalesDto> result = new ArrayList<>(7);
        for (int dow = 1; dow <= 7; dow++) {
            String dayName = DayOfWeek.of(dow).getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
            Map<String, Object> r = byDow.get(dow);
            if (r == null) {
                result.add(new DayOfWeekSalesDto(dow, dayName,
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP), 0L));
            } else {
                BigDecimal rev = scale2((BigDecimal) r.get("rev"));
                BigDecimal cogs = scale2((BigDecimal) r.get("cogs"));
                BigDecimal qty = scale3((BigDecimal) r.get("qty"));
                long bills = ((Number) r.get("bills")).longValue();
                result.add(new DayOfWeekSalesDto(dow, dayName, rev, scale2(rev.subtract(cogs)), qty, bills));
            }
        }
        return result;
    }

    private List<PeakDayDto> computeTopPeakDates(MapSqlParameterSource params, int limit) {
        String sql =
            "SELECT to_char(date_trunc('day', timezone(:tz, s.created_at)), 'YYYY-MM-DD') AS sale_date, " +
            "       COALESCE(SUM(" + NET_REV_EXPR + "), 0) AS rev, " +
            "       COALESCE(SUM(" + NET_COGS_EXPR + "), 0) AS cogs, " +
            "       COALESCE(SUM(" + NET_QTY_EXPR + "), 0) AS qty, " +
            "       COUNT(DISTINCT s.id) AS bills " +
            "FROM sales s " +
            "JOIN sale_items si ON si.sale_id = s.id " +
            "JOIN products p ON p.id = si.product_id " +
            "WHERE COALESCE(s.status, 'COMPLETED') != 'CANCELLED' " +
            "  AND s.created_at >= :from AND s.created_at < :to " +
            "  AND (:productId IS NULL OR si.product_id = :productId) " +
            "  AND (:categoryId IS NULL OR p.category_id = :categoryId) " +
            "GROUP BY 1 " +
            "ORDER BY rev DESC, bills DESC " +
            "LIMIT " + limit;

        return jdbc.queryForList(sql, params).stream().map(r -> {
            String d = (String) r.get("sale_date");
            LocalDate ld = LocalDate.parse(d);
            String dayName = ld.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
            BigDecimal rev = scale2((BigDecimal) r.get("rev"));
            BigDecimal cogs = scale2((BigDecimal) r.get("cogs"));
            BigDecimal qty = scale3((BigDecimal) r.get("qty"));
            long bills = ((Number) r.get("bills")).longValue();
            return new PeakDayDto(d, dayName, rev, scale2(rev.subtract(cogs)), qty, bills);
        }).toList();
    }

    private List<HourlySalesDto> computeSalesByHour(MapSqlParameterSource params) {
        String sql =
            "SELECT EXTRACT(HOUR FROM timezone(:tz, s.created_at))::int AS hr, " +
            "       COALESCE(SUM(" + NET_REV_EXPR + "), 0) AS rev, " +
            "       COALESCE(SUM(" + NET_COGS_EXPR + "), 0) AS cogs, " +
            "       COALESCE(SUM(" + NET_QTY_EXPR + "), 0) AS qty, " +
            "       COUNT(DISTINCT s.id) AS bills " +
            "FROM sales s " +
            "JOIN sale_items si ON si.sale_id = s.id " +
            "JOIN products p ON p.id = si.product_id " +
            "WHERE COALESCE(s.status, 'COMPLETED') != 'CANCELLED' " +
            "  AND s.created_at >= :from AND s.created_at < :to " +
            "  AND (:productId IS NULL OR si.product_id = :productId) " +
            "  AND (:categoryId IS NULL OR p.category_id = :categoryId) " +
            "GROUP BY 1 " +
            "ORDER BY 1 ASC";

        List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
        Map<Integer, Map<String, Object>> byHour = new HashMap<>();
        for (Map<String, Object> r : rows) {
            byHour.put(((Number) r.get("hr")).intValue(), r);
        }

        List<HourlySalesDto> result = new ArrayList<>(24);
        for (int h = 0; h < 24; h++) {
            String label = String.format("%02d:00", h);
            Map<String, Object> r = byHour.get(h);
            if (r == null) {
                result.add(new HourlySalesDto(h, label,
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP), 0L));
            } else {
                BigDecimal rev = scale2((BigDecimal) r.get("rev"));
                BigDecimal cogs = scale2((BigDecimal) r.get("cogs"));
                BigDecimal qty = scale3((BigDecimal) r.get("qty"));
                long bills = ((Number) r.get("bills")).longValue();
                result.add(new HourlySalesDto(h, label, rev, scale2(rev.subtract(cogs)), qty, bills));
            }
        }
        return result;
    }

    /**
     * Generates a multi-sheet Excel (.xlsx) workbook containing all analytics views for the selected filters.
     */
    @Transactional(readOnly = true)
    public byte[] exportExcel(String range, LocalDate reqFrom, LocalDate reqTo,
                              String granularity, Long productId, Long categoryId) {
        AnalyticsDashboardResponse data = getDashboard(range, reqFrom, reqTo, granularity, productId, categoryId);
        MapSqlParameterSource params = baseParams(data.from(), data.to(), productId, categoryId);
        List<ProductPerformanceDto> allProducts = computeAllProducts(
            data.from(), data.to(), data.summary().rangeDays(), params
        );

        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            // Sheet 1: Summary KPIs
            Sheet summarySheet = wb.createSheet("Executive Summary");
            int rowIdx = 0;
            addRow(summarySheet, rowIdx++, headerStyle, "Metric", "Value", "Previous Period / Details");
            AnalyticsSummary s = data.summary();
            addRow(summarySheet, rowIdx++, null, "Date Range", data.from() + " to " + data.to(), s.rangeDays() + " days");
            addRow(summarySheet, rowIdx++, null, "Total Revenue", s.totalRevenue().doubleValue(), "Prev: " + s.prevRevenue() + " (" + s.revenueGrowthPct() + "%)");
            addRow(summarySheet, rowIdx++, null, "Total Quantity Sold", s.totalQuantitySold().doubleValue(), "Prev: " + s.prevQuantitySold() + " (" + s.quantityGrowthPct() + "%)");
            addRow(summarySheet, rowIdx++, null, "Total Profit", s.totalProfit().doubleValue(), "Prev: " + s.prevProfit() + " (" + s.profitGrowthPct() + "%)");
            addRow(summarySheet, rowIdx++, null, "Gross Margin %", s.grossMarginPct().doubleValue(), "COGS: " + s.totalCogs());
            addRow(summarySheet, rowIdx++, null, "Completed Bills", s.billCount(), "AOV: " + s.avgOrderValue());
            addRow(summarySheet, rowIdx++, null, "Best-Selling Product",
                s.bestSellingProduct() != null ? s.bestSellingProduct().productName() : "N/A",
                s.bestSellingProduct() != null ? "Qty: " + s.bestSellingProduct().quantitySold() + ", Rev: " + s.bestSellingProduct().revenue() : "");
            addRow(summarySheet, rowIdx++, null, "Least-Selling Product",
                s.leastSellingProduct() != null ? s.leastSellingProduct().productName() : "N/A",
                s.leastSellingProduct() != null ? "Qty: " + s.leastSellingProduct().quantitySold() + ", Rev: " + s.leastSellingProduct().revenue() : "");
            addRow(summarySheet, rowIdx++, null, "Best-Performing Category",
                s.bestPerformingCategory() != null ? s.bestPerformingCategory().categoryName() : "N/A",
                s.bestPerformingCategory() != null ? "Rev: " + s.bestPerformingCategory().revenue() + " (" + s.bestPerformingCategory().revenueContributionPct() + "%)" : "");

            // Sheet 2: Product Performance & Velocity
            Sheet prodSheet = wb.createSheet("Product Performance & Velocity");
            int pIdx = 0;
            addRow(prodSheet, pIdx++, headerStyle,
                "Rank (Rev)", "SKU", "Product", "Category", "Unit", "Qty Sold", "Revenue", "COGS", "Profit",
                "Margin %", "Contribution %", "Avg/Day", "Avg/Week", "Avg/Month", "Current Stock", "Coverage (Days)", "Velocity Status", "Growth %");
            for (ProductPerformanceDto p : allProducts) {
                addRow(prodSheet, pIdx++, null,
                    p.rankByRevenue(), p.sku(), p.productName(), p.categoryName(), p.unit(),
                    p.quantitySold().doubleValue(), p.revenue().doubleValue(), p.cogs().doubleValue(), p.profit().doubleValue(),
                    p.marginPct().doubleValue(), p.revenueContributionPct().doubleValue(),
                    p.avgDailyUnits().doubleValue(), p.avgWeeklyUnits().doubleValue(), p.avgMonthlyUnits().doubleValue(),
                    p.currentStock().doubleValue(),
                    p.stockCoverageDays() != null ? p.stockCoverageDays().doubleValue() : "Infinite",
                    p.velocityStatus(), p.salesGrowthPct().doubleValue());
            }

            // Sheet 3: Category Analytics
            Sheet catSheet = wb.createSheet("Category Analytics");
            int cIdx = 0;
            addRow(catSheet, cIdx++, headerStyle,
                "Category", "Products", "Bills", "Qty Sold", "Revenue", "COGS", "Profit", "Margin %", "Contribution %", "Prev Revenue", "Growth %");
            for (CategoryAnalyticsDto c : data.categories()) {
                addRow(catSheet, cIdx++, null,
                    c.categoryName(), c.productCount(), c.billCount(),
                    c.quantitySold().doubleValue(), c.revenue().doubleValue(), c.cogs().doubleValue(), c.profit().doubleValue(),
                    c.marginPct().doubleValue(), c.revenueContributionPct().doubleValue(),
                    c.prevRevenue().doubleValue(), c.revenueGrowthPct().doubleValue());
            }

            // Sheet 4: Sales Trends
            Sheet trendSheet = wb.createSheet("Sales Trends");
            int tIdx = 0;
            addRow(trendSheet, tIdx++, headerStyle,
                "Period", "Revenue", "COGS", "Profit", "Margin %", "Qty Sold", "Bills", "Avg Order Value", "Growth %");
            for (TrendPointDto t : data.trendSeries()) {
                addRow(trendSheet, tIdx++, null,
                    t.bucket(), t.revenue().doubleValue(), t.cogs().doubleValue(), t.profit().doubleValue(),
                    t.marginPct().doubleValue(), t.quantitySold().doubleValue(), t.billCount(),
                    t.avgOrderValue().doubleValue(), t.growthPct().doubleValue());
            }

            // Sheet 5: Slow-Moving & Zero-Sales Stock
            Sheet slowSheet = wb.createSheet("Slow & Zero-Sales Stock");
            int slIdx = 0;
            addRow(slowSheet, slIdx++, headerStyle,
                "SKU", "Product", "Category", "Current Stock", "Stock Value", "Qty Sold in Period", "Avg Daily Units", "Stock Coverage (Days)", "Days Since Last Sale", "Status");
            for (ProductPerformanceDto p : data.slowMovingProducts()) {
                addRow(slowSheet, slIdx++, null,
                    p.sku(), p.productName(), p.categoryName(),
                    p.currentStock().doubleValue(), p.stockValue().doubleValue(), p.quantitySold().doubleValue(),
                    p.avgDailyUnits().doubleValue(),
                    p.stockCoverageDays() != null ? p.stockCoverageDays().doubleValue() : "Infinite",
                    p.daysSinceLastSale() != null ? p.daysSinceLastSale() : "Never sold",
                    p.velocityStatus());
            }

            // Sheet 6: Cashier & Payment Breakdown
            Sheet cpSheet = wb.createSheet("Cashiers & Payments");
            int cpIdx = 0;
            addRow(cpSheet, cpIdx++, headerStyle, "Payment Method", "Amount", "Transactions", "Share %");
            for (PaymentMethodAnalyticsDto pm : data.paymentMethods()) {
                addRow(cpSheet, cpIdx++, null, pm.method(), pm.amount().doubleValue(), pm.transactionCount(), pm.sharePct().doubleValue());
            }
            cpIdx++;
            addRow(cpSheet, cpIdx++, headerStyle, "Cashier", "Bills", "Qty Sold", "Revenue", "Discounts", "Profit", "Avg Bill", "Share %");
            for (CashierAnalyticsDto ca : data.cashiers()) {
                addRow(cpSheet, cpIdx++, null,
                    ca.cashierName(), ca.billCount(), ca.quantitySold().doubleValue(),
                    ca.revenue().doubleValue(), ca.discountGiven().doubleValue(), ca.profit().doubleValue(),
                    ca.avgBillValue().doubleValue(), ca.sharePct().doubleValue());
            }

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException("Failed to generate Excel export: " + e.getMessage());
        }
    }

    private void addRow(Sheet sheet, int rowIdx, CellStyle style, Object... values) {
        Row row = sheet.createRow(rowIdx);
        for (int i = 0; i < values.length; i++) {
            Cell cell = row.createCell(i);
            Object v = values[i];
            if (v instanceof Number n) {
                cell.setCellValue(n.doubleValue());
            } else {
                cell.setCellValue(v == null ? "" : String.valueOf(v));
            }
            if (style != null) {
                cell.setCellStyle(style);
            }
        }
    }

    private String normalizeGranularity(String granularity, long rangeDays) {
        if (granularity != null && !granularity.isBlank()) {
            return switch (granularity.trim().toUpperCase()) {
                case "WEEK", "WEEKLY" -> "WEEK";
                case "MONTH", "MONTHLY" -> "MONTH";
                case "YEAR", "YEARLY" -> "YEAR";
                default -> "DAY";
            };
        }
        if (rangeDays <= 45) return "DAY";
        if (rangeDays <= 180) return "WEEK";
        if (rangeDays <= 730) return "MONTH";
        return "YEAR";
    }

    private String formatBucketLabel(String bucket, String granularity) {
        if (bucket == null) return "";
        return switch (granularity) {
            case "DAY" -> bucket.length() >= 10 ? bucket.substring(5) : bucket;
            case "WEEK" -> "Wk " + (bucket.length() >= 10 ? bucket.substring(5) : bucket);
            default -> bucket;
        };
    }

    private static BigDecimal scale2(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale3(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(3, RoundingMode.HALF_UP);
    }

    private static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        if (part == null || whole == null || whole.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return part.multiply(HUNDRED).divide(whole, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal growthPct(BigDecimal current, BigDecimal previous) {
        BigDecimal cur = current == null ? BigDecimal.ZERO : current;
        BigDecimal prev = previous == null ? BigDecimal.ZERO : previous;
        if (prev.signum() == 0) {
            return cur.signum() > 0
                ? new BigDecimal("100.00")
                : (cur.signum() < 0 ? new BigDecimal("-100.00") : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        }
        return cur.subtract(prev).multiply(HUNDRED).divide(prev.abs(), 2, RoundingMode.HALF_UP);
    }

    private static Instant toInstant(Object ts) {
        if (ts == null) return null;
        if (ts instanceof Instant i) return i;
        if (ts instanceof Timestamp t) return t.toInstant();
        if (ts instanceof OffsetDateTime odt) return odt.toInstant();
        return null;
    }
}
