package com.grocery.supplier;

import com.grocery.supplier.SupplierDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/suppliers")
@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
public class SupplierController {
    private final SupplierService service;
    public SupplierController(SupplierService s) { service = s; }

    @GetMapping public List<SupplierDto> list() { return service.list(); }
    @GetMapping("/{id}") public SupplierDto get(@PathVariable Long id) { return service.get(id); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public SupplierDto create(@Valid @RequestBody SupplierRequest r) { return service.create(r); }
    @PutMapping("/{id}") public SupplierDto update(@PathVariable Long id, @Valid @RequestBody SupplierRequest r) { return service.update(id, r); }
    @PostMapping("/{id}/approve") @PreAuthorize("hasRole('OWNER')") public SupplierDto approve(@PathVariable Long id) { return service.approve(id); }
    @PatchMapping("/{id}/active") public SupplierDto active(@PathVariable Long id, @RequestParam boolean value) { return service.setActive(id, value); }

    @GetMapping("/{id}/ledger") public LedgerStatement ledger(@PathVariable Long id) { return service.statement(id); }
    @PostMapping("/{id}/payments") public SupplierDto pay(@PathVariable Long id, @Valid @RequestBody PaymentRequest r) { return service.pay(id, r); }
    @PostMapping("/{id}/adjustments") @PreAuthorize("hasRole('OWNER')")
    public SupplierDto adjust(@PathVariable Long id, @Valid @RequestBody AdjustmentRequest r) { return service.adjust(id, r); }

    @GetMapping("/{id}/products") public List<SupplierProductDto> products(@PathVariable Long id) { return service.products(id); }
    @PostMapping("/{id}/products") public List<SupplierProductDto> map(@PathVariable Long id, @Valid @RequestBody SupplierProductRequest r) { return service.mapProduct(id, r); }
    @DeleteMapping("/{id}/products/{mappingId}") public List<SupplierProductDto> unmap(@PathVariable Long id, @PathVariable Long mappingId) { return service.unmapProduct(id, mappingId); }
}
