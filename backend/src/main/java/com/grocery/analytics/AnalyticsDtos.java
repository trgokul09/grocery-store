package com.grocery.analytics;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public class AnalyticsDtos {

    public record AnalyticsSummary(
        BigDecimal totalRevenue,
        BigDecimal prevRevenue,
        BigDecimal revenueGrowthPct,
        BigDecimal totalQuantitySold,
        BigDecimal prevQuantitySold,
        BigDecimal quantityGrowthPct,
        BigDecimal totalCogs,
        BigDecimal totalProfit,
        BigDecimal prevProfit,
        BigDecimal profitGrowthPct,
        BigDecimal grossMarginPct,
        BigDecimal totalDiscount,
        long billCount,
        long prevBillCount,
        BigDecimal billGrowthPct,
        BigDecimal avgOrderValue,
        BigDecimal avgBasketSize,
        long rangeDays,
        ProductPerformanceDto bestSellingProduct,
        ProductPerformanceDto leastSellingProduct,
        CategoryAnalyticsDto bestPerformingCategory,
        CategoryAnalyticsDto lowestPerformingCategory,
        PeakDayDto peakSalesDay,
        HourlySalesDto peakSalesHour,
        int totalActiveProducts,
        int zeroSalesProductCount,
        int slowMovingProductCount,
        int decliningProductCount
    ) {}

    public record ProductPerformanceDto(
        Long productId,
        String productName,
        String sku,
        Long categoryId,
        String categoryName,
        String unit,
        BigDecimal currentStock,
        BigDecimal minStockLevel,
        BigDecimal purchasePrice,
        BigDecimal sellingPrice,
        BigDecimal stockValue,
        BigDecimal quantitySold,
        BigDecimal revenue,
        BigDecimal cogs,
        BigDecimal profit,
        BigDecimal marginPct,
        BigDecimal discountGiven,
        BigDecimal revenueContributionPct,
        BigDecimal profitContributionPct,
        long availableSellingDays,
        BigDecimal avgDailyUnits,
        BigDecimal avgWeeklyUnits,
        BigDecimal avgMonthlyUnits,
        BigDecimal dailyRevenueVelocity,
        BigDecimal stockCoverageDays,
        String velocityStatus,
        BigDecimal prevQuantitySold,
        BigDecimal prevRevenue,
        BigDecimal salesGrowthPct,
        boolean declining,
        Instant lastSoldAt,
        Long daysSinceLastSale,
        int rankByQuantity,
        int rankByRevenue,
        int rankByProfit
    ) {}

    public record CategoryAnalyticsDto(
        Long categoryId,
        String categoryName,
        int productCount,
        long billCount,
        BigDecimal quantitySold,
        BigDecimal revenue,
        BigDecimal cogs,
        BigDecimal profit,
        BigDecimal marginPct,
        BigDecimal revenueContributionPct,
        BigDecimal profitContributionPct,
        BigDecimal prevQuantitySold,
        BigDecimal prevRevenue,
        BigDecimal prevProfit,
        BigDecimal revenueGrowthPct,
        BigDecimal profitGrowthPct
    ) {}

    public record TrendPointDto(
        String bucket,
        String label,
        BigDecimal revenue,
        BigDecimal cogs,
        BigDecimal profit,
        BigDecimal marginPct,
        BigDecimal quantitySold,
        long billCount,
        BigDecimal avgOrderValue,
        BigDecimal growthPct
    ) {}

    public record PaymentMethodAnalyticsDto(
        String method,
        BigDecimal amount,
        long transactionCount,
        BigDecimal sharePct
    ) {}

    public record CashierAnalyticsDto(
        Long cashierId,
        String cashierName,
        long billCount,
        BigDecimal quantitySold,
        BigDecimal revenue,
        BigDecimal discountGiven,
        BigDecimal profit,
        BigDecimal avgBillValue,
        BigDecimal sharePct
    ) {}

    public record DayOfWeekSalesDto(
        int dayOfWeek,
        String dayName,
        BigDecimal revenue,
        BigDecimal profit,
        BigDecimal quantitySold,
        long billCount
    ) {}

    public record PeakDayDto(
        String date,
        String dayName,
        BigDecimal revenue,
        BigDecimal profit,
        BigDecimal quantitySold,
        long billCount
    ) {}

    public record HourlySalesDto(
        int hour,
        String label,
        BigDecimal revenue,
        BigDecimal profit,
        BigDecimal quantitySold,
        long billCount
    ) {}

    public record AnalyticsDashboardResponse(
        LocalDate from,
        LocalDate to,
        LocalDate prevFrom,
        LocalDate prevTo,
        String range,
        String granularity,
        Long selectedProductId,
        Long selectedCategoryId,
        AnalyticsSummary summary,
        List<TrendPointDto> trendSeries,
        List<TrendPointDto> dailySeries,
        List<TrendPointDto> weeklySeries,
        List<TrendPointDto> monthlySeries,
        List<TrendPointDto> yearlySeries,
        List<CategoryAnalyticsDto> categories,
        List<Map<String, Object>> categoryTrendSeries,
        List<ProductPerformanceDto> topByQuantity,
        List<ProductPerformanceDto> topByRevenue,
        List<ProductPerformanceDto> topByProfit,
        List<ProductPerformanceDto> bottomProducts,
        List<ProductPerformanceDto> zeroSalesProducts,
        List<ProductPerformanceDto> decliningProducts,
        List<ProductPerformanceDto> slowMovingProducts,
        List<PaymentMethodAnalyticsDto> paymentMethods,
        List<CashierAnalyticsDto> cashiers,
        List<DayOfWeekSalesDto> salesByDayOfWeek,
        List<PeakDayDto> topPeakDates,
        List<HourlySalesDto> salesByHour
    ) {}
}
