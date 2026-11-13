package com.grocery.inventory;

import com.grocery.inventory.InventoryService.*;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/inventory")
@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
public class InventoryController {
    private final InventoryService service;
    public InventoryController(InventoryService s) { service = s; }

    @PostMapping("/adjust")
    public ResponseEntity<?> adjust(@Valid @RequestBody AdjustRequest r) {
        MovementDto m = service.adjust(r);
        return m == null ? ResponseEntity.ok(Map.of("message", "Counted quantity matches system stock - no adjustment needed")) : ResponseEntity.ok(m);
    }

    @GetMapping("/movements")
    public Page<MovementDto> movements(@RequestParam(required = false) Long productId, @RequestParam(required = false) MovementType type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "30") int size) {
        return service.search(productId, type, from, to, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200), Sort.by(Sort.Direction.DESC, "id")));
    }
}
