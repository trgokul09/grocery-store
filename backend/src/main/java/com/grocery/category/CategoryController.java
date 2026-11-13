package com.grocery.category;

import com.grocery.common.Exceptions.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {
    public record CategoryRequest(@NotBlank @Size(max = 100) String name, @Size(max = 255) String description) {}
    public record CategoryDto(Long id, String name, String description, boolean active) {}

    private final CategoryRepository repo;
    public CategoryController(CategoryRepository repo) { this.repo = repo; }

    private static CategoryDto dto(Category c) { return new CategoryDto(c.getId(), c.getName(), c.getDescription(), c.isActive()); }

    @GetMapping
    public List<CategoryDto> list(@RequestParam(defaultValue = "true") boolean activeOnly) {
        return (activeOnly ? repo.findByActiveTrueOrderByNameAsc() : repo.findAll()).stream().map(CategoryController::dto).toList();
    }

    @PostMapping @PreAuthorize("hasAnyRole('OWNER','MANAGER')") @Transactional
    public CategoryDto create(@Valid @RequestBody CategoryRequest r) {
        if (repo.findByNameIgnoreCase(r.name().trim()).isPresent()) throw new BusinessException("Category already exists");
        return dto(repo.save(new Category(r.name().trim(), r.description())));
    }

    @PutMapping("/{id}") @PreAuthorize("hasAnyRole('OWNER','MANAGER')") @Transactional
    public CategoryDto update(@PathVariable Long id, @Valid @RequestBody CategoryRequest r) {
        Category c = repo.findById(id).orElseThrow(() -> new NotFoundException("Category not found"));
        repo.findByNameIgnoreCase(r.name().trim()).filter(x -> !x.getId().equals(id))
            .ifPresent(x -> { throw new BusinessException("Category name already in use"); });
        c.setName(r.name().trim()); c.setDescription(r.description());
        return dto(c);
    }

    @DeleteMapping("/{id}") @PreAuthorize("hasAnyRole('OWNER','MANAGER')") @Transactional
    public CategoryDto deactivate(@PathVariable Long id) {
        Category c = repo.findById(id).orElseThrow(() -> new NotFoundException("Category not found"));
        c.setActive(false);
        return dto(c);
    }
}
