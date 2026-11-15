package com.grocery.analytics;

import com.grocery.analytics.AnalyticsDtos.AnalyticsDashboardResponse;
import com.grocery.analytics.AnalyticsDtos.ProductPerformanceDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/analytics")
@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
public class AnalyticsController {

    private final AnalyticsService analytics;

    public AnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping({"", "/dashboard"})
    public AnalyticsDashboardResponse dashboard(
            @RequestParam(defaultValue = "LAST_30_DAYS") String range,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String granularity,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long categoryId) {
        return analytics.getDashboard(range, from, to, granularity, productId, categoryId);
    }

    @GetMapping("/products")
    public Page<ProductPerformanceDto> productVelocity(
            @RequestParam(defaultValue = "LAST_30_DAYS") String range,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "ALL") String filter,
            @RequestParam(defaultValue = "QUANTITY") String sortBy,
            @RequestParam(defaultValue = "DESC") String direction,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        return analytics.getProductVelocityPage(range, from, to, productId, categoryId, search, filter, sortBy, direction, pageable);
    }

    @GetMapping("/export/excel")
    public ResponseEntity<byte[]> exportExcel(
            @RequestParam(defaultValue = "LAST_30_DAYS") String range,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String granularity,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long categoryId) {
        LocalDate[] resolved = analytics.resolveRange(range, from, to);
        byte[] bytes = analytics.exportExcel(range, from, to, granularity, productId, categoryId);
        String filename = "sales-analytics-" + resolved[0] + "-to-" + resolved[1] + ".xlsx";
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(bytes);
    }
}
