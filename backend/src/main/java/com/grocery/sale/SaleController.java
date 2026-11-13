package com.grocery.sale;

import com.grocery.product.ProductDtos.ProductDto;
import com.grocery.product.ProductRepository;
import com.grocery.product.ProductService;
import com.grocery.sale.SaleDtos.*;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;

@RestController
public class SaleController {
    private final SaleService service; private final ProductRepository products;
    public SaleController(SaleService s, ProductRepository p) { service = s; products = p; }

    @PostMapping("/api/sales/checkout") @ResponseStatus(HttpStatus.CREATED)
    public SaleDto checkout(@Valid @RequestBody CheckoutRequest r) { return service.checkout(r); }

    @GetMapping("/api/sales/{id}") public SaleDto get(@PathVariable Long id) { return service.get(id); }

    @GetMapping("/api/sales")
    public Page<SaleDto> list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                              @RequestParam(required = false) String invoice,
                              @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.search(from, to, invoice, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "id")));
    }

    @GetMapping("/api/pos/quick-items") @Transactional(readOnly = true)
    public List<ProductDto> quickItems(@RequestParam(defaultValue = "12") int limit) {
        return service.quickGrid(Math.min(Math.max(limit, 1), 40), products).stream().map(ProductService::toDto).toList();
    }
}
