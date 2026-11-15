package com.grocery;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsIT extends AbstractIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @SuppressWarnings("unchecked")
    void analyticsComputesProductVelocityCategoryProfitAndEnforcesRoles() {
        String owner = ownerToken();
        String manager = createUser(owner, "MANAGER");
        String staff = createUser(owner, "STAFF");

        // 1. Role permissions: STAFF cannot access /api/analytics
        var staffRes = call(HttpMethod.GET, "/api/analytics/dashboard", staff, null);
        assertThat(staffRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 2. Empty state check on future custom date range
        var emptyRes = call(HttpMethod.GET, "/api/analytics/dashboard?range=CUSTOM&from=2099-01-01&to=2099-01-05", owner, null);
        assertThat(emptyRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> emptySummary = (Map<String, Object>) emptyRes.getBody().get("summary");
        assertThat(new BigDecimal(String.valueOf(emptySummary.get("totalRevenue")))).isEqualByComparingTo("0.00");
        assertThat(((Number) emptySummary.get("billCount")).longValue()).isZero();

        // 3. Create two categories and three products
        var cat1Res = call(HttpMethod.POST, "/api/categories", owner, Map.of("name", "Dairy_Ana", "description", "Dairy items"));
        var cat2Res = call(HttpMethod.POST, "/api/categories", owner, Map.of("name", "Snacks_Ana", "description", "Snack items"));
        Number cat1Id = (Number) cat1Res.getBody().get("id");
        Number cat2Id = (Number) cat2Res.getBody().get("id");

        // Product A (Dairy_Ana): purchase=20, selling=30 (profit=10/unit), stock=100
        var prodARes = call(HttpMethod.POST, "/api/products", owner, Map.of(
            "name", "Milk Pack Ana", "sku", "ANA-MILK-1", "categoryId", cat1Id,
            "purchasePrice", "20.00", "sellingPrice", "30.00", "unit", "PCS", "minStockLevel", 5, "openingStock", 100
        ));
        Number prodAId = (Number) prodARes.getBody().get("id");

        // Product B (Snacks_Ana): purchase=40, selling=100 (profit=60/unit), stock=50
        var prodBRes = call(HttpMethod.POST, "/api/products", owner, Map.of(
            "name", "Almond Tin Ana", "sku", "ANA-ALM-1", "categoryId", cat2Id,
            "purchasePrice", "40.00", "sellingPrice", "100.00", "unit", "PCS", "minStockLevel", 5, "openingStock", 50
        ));
        Number prodBId = (Number) prodBRes.getBody().get("id");

        // Product C (Dairy_Ana): Zero sales product, stock=25
        var prodCRes = call(HttpMethod.POST, "/api/products", owner, Map.of(
            "name", "ButterTub Ana", "sku", "ANA-BUT-1", "categoryId", cat1Id,
            "purchasePrice", "50.00", "sellingPrice", "75.00", "unit", "PCS", "minStockLevel", 5, "openingStock", 25
        ));
        Number prodCId = (Number) prodCRes.getBody().get("id");

        // 4. Perform sales:
        // Sale 1: 10 units of Product A (10 * 30 = 300, cost = 200, profit = 100) + 2 units of Product B (2 * 100 = 200, cost = 80, profit = 120)
        var sale1 = call(HttpMethod.POST, "/api/sales/checkout", owner, Map.of(
            "items", List.of(
                Map.of("productId", prodAId, "quantity", 10, "discount", 0),
                Map.of("productId", prodBId, "quantity", 2, "discount", 0)
            ),
            "payments", List.of(Map.of("method", "CASH", "amount", "500.00"))
        ));
        assertThat(sale1.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Sale 2: Cancelled transaction (should be excluded from analytics)
        var sale2 = call(HttpMethod.POST, "/api/sales/checkout", owner, Map.of(
            "items", List.of(Map.of("productId", prodAId, "quantity", 5, "discount", 0)),
            "payments", List.of(Map.of("method", "UPI", "amount", "150.00"))
        ));
        Number sale2Id = (Number) sale2.getBody().get("id");
        jdbc.update("UPDATE sales SET status = 'CANCELLED' WHERE id = ?", sale2Id.longValue());

        // 5. Query TODAY analytics as MANAGER
        var dashRes = call(HttpMethod.GET, "/api/analytics/dashboard?range=TODAY", manager, null);
        assertThat(dashRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = dashRes.getBody();
        Map<String, Object> summary = (Map<String, Object>) body.get("summary");

        // Best-selling by quantity should be Product A (10 units > 2 units)
        Map<String, Object> bestProd = (Map<String, Object>) summary.get("bestSellingProduct");
        assertThat(bestProd.get("sku")).isEqualTo("ANA-MILK-1");
        assertThat(new BigDecimal(String.valueOf(bestProd.get("quantitySold")))).isEqualByComparingTo("10.000");
        assertThat(new BigDecimal(String.valueOf(bestProd.get("revenue")))).isEqualByComparingTo("300.00");
        assertThat(new BigDecimal(String.valueOf(bestProd.get("profit")))).isEqualByComparingTo("100.00");
        // Velocity for TODAY (1 day): avgDailyUnits = 10.000, currentStock = 85 (100 - 10 - 5), stockCoverageDays = 85 / 10 = 8.5
        assertThat(new BigDecimal(String.valueOf(bestProd.get("avgDailyUnits")))).isEqualByComparingTo("10.000");
        assertThat(new BigDecimal(String.valueOf(bestProd.get("stockCoverageDays")))).isEqualByComparingTo("8.5");

        // Filter by category1 (Dairy_Ana)
        var catFilterRes = call(HttpMethod.GET, "/api/analytics/dashboard?range=TODAY&categoryId=" + cat1Id, owner, null);
        assertThat(catFilterRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> catSummary = (Map<String, Object>) catFilterRes.getBody().get("summary");
        assertThat(new BigDecimal(String.valueOf(catSummary.get("totalRevenue")))).isEqualByComparingTo("300.00");
        assertThat(new BigDecimal(String.valueOf(catSummary.get("totalProfit")))).isEqualByComparingTo("100.00");

        // Paginated velocity endpoint with ZERO_SALES filter should include Product C
        var zeroVelRes = call(HttpMethod.GET, "/api/analytics/products?range=TODAY&categoryId=" + cat1Id + "&filter=ZERO_SALES", owner, null);
        assertThat(zeroVelRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> zeroContent = (List<Map<String, Object>>) zeroVelRes.getBody().get("content");
        assertThat(zeroContent).anyMatch(p -> ((Number) p.get("productId")).longValue() == prodCId.longValue());

        // 6. Excel export endpoint verification
        ResponseEntity<byte[]> excelRes = rest.exchange(
            "/api/analytics/export/excel?range=TODAY",
            HttpMethod.GET,
            new HttpEntity<>(auth(owner)),
            byte[].class
        );
        assertThat(excelRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(excelRes.getBody()).isNotNull();
        assertThat(excelRes.getBody().length).isGreaterThan(100);
    }
}
