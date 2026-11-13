package com.grocery.user;

import com.grocery.audit.AuditService;
import com.grocery.common.CurrentUser;
import com.grocery.common.Exceptions.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('OWNER')")
public class UserController {
    public record UserDto(Long id, String username, String fullName, String role, boolean active, Instant createdAt) {}
    public record CreateUser(@NotBlank @Size(min = 3, max = 50) @Pattern(regexp = "[A-Za-z0-9._-]+") String username,
                             @NotBlank @Size(min = 8, max = 72) String password, @NotBlank @Size(max = 120) String fullName,
                             @NotBlank @Pattern(regexp = "OWNER|MANAGER|STAFF") String role) {}
    public record UpdateUser(@NotBlank @Size(max = 120) String fullName, @NotBlank @Pattern(regexp = "OWNER|MANAGER|STAFF") String role) {}
    public record ResetPassword(@NotBlank @Size(min = 8, max = 72) String newPassword) {}

    private final UserRepository users; private final RoleRepository roles; private final PasswordEncoder enc;
    private final AuditService audit; private final CurrentUser cu;
    public UserController(UserRepository u, RoleRepository r, PasswordEncoder e, AuditService a, CurrentUser c) { users = u; roles = r; enc = e; audit = a; cu = c; }

    private static UserDto dto(User u) { return new UserDto(u.getId(), u.getUsername(), u.getFullName(), u.getRole().getName(), u.isActive(), u.getCreatedAt()); }

    @GetMapping @Transactional(readOnly = true)
    public List<UserDto> list() { return users.findAll().stream().map(UserController::dto).toList(); }

    @PostMapping @ResponseStatus(HttpStatus.CREATED) @Transactional
    public UserDto create(@Valid @RequestBody CreateUser r) {
        if (users.findByUsername(r.username()).isPresent()) throw new BusinessException("Username already taken");
        User u = users.save(new User(r.username(), enc.encode(r.password()), r.fullName().trim(), roles.findByName(r.role()).orElseThrow()));
        audit.log("CREATE", "USER", u.getId(), null, Map.of("username", u.getUsername(), "role", r.role()));
        return dto(u);
    }

    @PutMapping("/{id}") @Transactional
    public UserDto update(@PathVariable Long id, @Valid @RequestBody UpdateUser r) {
        User u = users.findById(id).orElseThrow(() -> new NotFoundException("User not found"));
        if (u.getId().equals(cu.require().getId()) && !r.role().equals(u.getRole().getName()))
            throw new BusinessException("You cannot change your own role");
        var before = Map.of("fullName", u.getFullName(), "role", u.getRole().getName());
        u.setFullName(r.fullName().trim()); u.setRole(roles.findByName(r.role()).orElseThrow());
        audit.log("UPDATE", "USER", id, before, Map.of("fullName", u.getFullName(), "role", r.role()));
        return dto(u);
    }

    @PatchMapping("/{id}/active") @Transactional
    public UserDto setActive(@PathVariable Long id, @RequestParam boolean value) {
        User u = users.findById(id).orElseThrow(() -> new NotFoundException("User not found"));
        if (u.getId().equals(cu.require().getId()) && !value) throw new BusinessException("You cannot deactivate yourself");
        u.setActive(value);
        audit.log(value ? "ACTIVATE" : "DEACTIVATE", "USER", id, null, Map.of("active", value));
        return dto(u);
    }

    @PostMapping("/{id}/reset-password") @Transactional
    public void reset(@PathVariable Long id, @Valid @RequestBody ResetPassword r) {
        User u = users.findById(id).orElseThrow(() -> new NotFoundException("User not found"));
        u.setPasswordHash(enc.encode(r.newPassword()));
        audit.log("RESET_PASSWORD", "USER", id, null, null);
    }
}
