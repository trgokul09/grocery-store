package com.grocery.finance;

import com.grocery.finance.FinanceService.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController
@RequestMapping("/api")
public class DashboardController {
    private final FinanceService finance;
    public DashboardController(FinanceService f) { finance = f; }

    /** One call feeds the whole mobile dashboard: metrics + charts. range = TODAY | WEEK | MONTH | CUSTOM */
    @GetMapping("/dashboard") @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Dashboard dashboard(@RequestParam(defaultValue = "TODAY") String range,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate[] r = finance.resolve(range, from, to);
        return finance.dashboard(r[0], r[1]);
    }

    @GetMapping("/finance/pnl") @PreAuthorize("hasRole('OWNER')")
    public Pnl pnl(@RequestParam(defaultValue = "MONTH") String range,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate[] r = finance.resolve(range, from, to);
        return finance.pnl(r[0], r[1]);
    }
}
