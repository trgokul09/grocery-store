package com.grocery.purchase;

import com.grocery.common.CurrentUser;
import com.grocery.purchase.PurchaseDtos.*;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/purchase-orders")
public class PurchaseController {
    private final PurchaseService service; private final CurrentUser cu;
    public PurchaseController(PurchaseService s, CurrentUser cu) { service = s; this.cu = cu; }

    /** STAFF only ever sees deliverable (APPROVED / PARTIALLY_RECEIVED) orders. */
    @GetMapping
    public Page<PoDto> list(@RequestParam(required = false) PoStatus status, @RequestParam(required = false) Long supplierId,
                            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var pg = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "id"));
        return service.list(status, supplierId, cu.isStaff(), pg);
    }

    @GetMapping("/{id}") public PoDto get(@PathVariable Long id) { return service.get(id, cu.isStaff()); }

    @PostMapping @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public PoDto create(@Valid @RequestBody PoRequest r) { return service.create(r); }
    @PutMapping("/{id}") @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public PoDto update(@PathVariable Long id, @Valid @RequestBody PoRequest r) { return service.update(id, r); }
    @PostMapping("/{id}/submit") @PreAuthorize("hasAnyRole('OWNER','MANAGER')") public PoDto submit(@PathVariable Long id) { return service.submit(id); }
    @PostMapping("/{id}/approve") @PreAuthorize("hasRole('OWNER')") public PoDto approve(@PathVariable Long id) { return service.approve(id); }
    @PostMapping("/{id}/reject") @PreAuthorize("hasRole('OWNER')")
    public PoDto reject(@PathVariable Long id, @RequestBody(required = false) RejectRequest r) { return service.reject(id, r == null ? null : r.reason()); }
    @PostMapping("/{id}/cancel") @PreAuthorize("hasAnyRole('OWNER','MANAGER')") public PoDto cancel(@PathVariable Long id) { return service.cancel(id); }
    @PostMapping("/{id}/receive") public PoDto receive(@PathVariable Long id, @Valid @RequestBody ReceiveRequest r) { return service.receive(id, r); }
}
