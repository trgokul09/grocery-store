package com.grocery.reorder;

import com.grocery.purchase.PurchaseDtos.PoDto;
import com.grocery.reorder.ReorderService.*;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/reorder")
public class ReorderController {
    private final ReorderService service;
    public ReorderController(ReorderService s) { service = s; }

    @GetMapping("/recommendations") @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<Group> recommendations() { return service.recommendations(); }

    @PostMapping("/convert") @PreAuthorize("hasRole('OWNER')")
    public PoDto convert(@Valid @RequestBody ConvertRequest r) { return service.convert(r); }
}
