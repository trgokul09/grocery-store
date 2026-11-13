package com.grocery.product;

import com.grocery.product.ProductDtos.*;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/products")
public class ProductController {
    private final ProductService service; private final ProductImportService importer;
    public ProductController(ProductService s, ProductImportService i) { service = s; importer = i; }

    /** All roles can browse. STAFF is restricted to the active catalog. */
    @GetMapping
    public Page<ProductDto> list(@RequestParam(required = false) String q, @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Boolean active, @RequestParam(defaultValue = "false") boolean lowStockOnly,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "name") String sort, Authentication auth) {
        boolean staff = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_STAFF"));
        Boolean effectiveActive = staff ? Boolean.TRUE : active;
        var pg = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200), Sort.by(sortField(sort)));
        return service.search(q, categoryId, effectiveActive, lowStockOnly, pg);
    }

    private static String sortField(String s) {
        return switch (s) { case "sku", "sellingPrice", "currentStock", "expiryDate", "brand" -> s; default -> "name"; };
    }

    @GetMapping("/{id}") public ProductDto get(@PathVariable Long id) { return service.get(id); }
    @GetMapping("/barcode/{code}") public ProductDto byBarcode(@PathVariable String code) { return service.getByBarcode(code); }

    @PostMapping @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ProductDto> create(@Valid @RequestBody ProductRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(r));
    }

    @PutMapping("/{id}") @PreAuthorize("hasRole('OWNER')")
    public ProductDto update(@PathVariable Long id, @Valid @RequestBody ProductRequest r) { return service.update(id, r); }

    /** Soft delete - never a hard delete. */
    @DeleteMapping("/{id}") @PreAuthorize("hasRole('OWNER')")
    public ProductDto deactivate(@PathVariable Long id) { return service.setActive(id, false); }

    @PatchMapping("/{id}/activate") @PreAuthorize("hasRole('OWNER')")
    public ProductDto activate(@PathVariable Long id) { return service.setActive(id, true); }

    @GetMapping("/import/template") @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<byte[]> template() {
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"product-import-template.xlsx\"")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(importer.template());
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE) @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ImportResult> importProducts(@RequestParam("file") MultipartFile file) {
        ImportResult r = importer.importFile(file);
        return ResponseEntity.status(r.success() ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_ENTITY).body(r);
    }
}
