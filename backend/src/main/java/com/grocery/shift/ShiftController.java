package com.grocery.shift;

import com.grocery.shift.ShiftService.*;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/shifts")
public class ShiftController {
    private final ShiftService service;
    public ShiftController(ShiftService s) { service = s; }

    @PostMapping("/open") public ShiftDto open(@Valid @RequestBody OpenRequest r) { return service.open(r); }
    @PostMapping("/close") public ShiftDto close(@Valid @RequestBody CloseRequest r) { return service.close(r); }

    @GetMapping("/current")
    public ResponseEntity<ShiftDto> current() {
        ShiftDto s = service.current();
        return s == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(s);
    }

    @GetMapping
    public Page<ShiftDto> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.list(PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "id")));
    }

    @GetMapping("/{id}") public ShiftDto get(@PathVariable Long id) { return service.get(id); }
}
