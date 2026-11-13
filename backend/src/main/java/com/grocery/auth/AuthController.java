package com.grocery.auth;

import com.grocery.security.JwtService;
import com.grocery.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record LoginResponse(String token, long expiresInSeconds, String username, String fullName, String role) {}
    public record MeResponse(String username, String fullName, String role) {}

    public record ChangePasswordRequest(@NotBlank String currentPassword, @jakarta.validation.constraints.Size(min = 8, max = 72) String newPassword) {}

    private final AuthenticationManager am; private final JwtService jwt; private final UserRepository users;
    private final org.springframework.security.crypto.password.PasswordEncoder enc;
    private final com.grocery.shift.ShiftRepository shifts;
    public AuthController(AuthenticationManager am, JwtService jwt, UserRepository users, org.springframework.security.crypto.password.PasswordEncoder enc,
                          com.grocery.shift.ShiftRepository shifts) {
        this.am = am; this.jwt = jwt; this.users = users; this.enc = enc; this.shifts = shifts;
    }

    /** Called before the client discards its token: staff may not sign out while their shift is still open. */
    @PostMapping("/logout") @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public void logout(Authentication auth) {
        var u = users.findByUsername(auth.getName()).orElseThrow();
        if (u.getRole().getName().equals("STAFF")
                && shifts.findByUserIdAndStatus(u.getId(), com.grocery.shift.ShiftStatus.OPEN).isPresent())
            throw new com.grocery.common.Exceptions.BusinessException("You can sign out only after closing your shift. Enter your closing cash on the Shifts page.");
    }

    @PostMapping("/change-password") @org.springframework.transaction.annotation.Transactional
    public void changePassword(Authentication auth, @Valid @RequestBody ChangePasswordRequest r) {
        var u = users.findByUsername(auth.getName()).orElseThrow();
        if (!enc.matches(r.currentPassword(), u.getPasswordHash()))
            throw new com.grocery.common.Exceptions.BusinessException("Current password is incorrect");
        u.setPasswordHash(enc.encode(r.newPassword()));
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest r) {
        am.authenticate(new UsernamePasswordAuthenticationToken(r.username(), r.password()));
        var u = users.findByUsername(r.username()).orElseThrow();
        String role = u.getRole().getName();
        return new LoginResponse(jwt.generate(u.getUsername(), role), jwt.getExpirySeconds(),
                u.getUsername(), u.getFullName(), role);
    }

    @GetMapping("/me")
    public MeResponse me(Authentication auth) {
        var u = users.findByUsername(auth.getName()).orElseThrow();
        return new MeResponse(u.getUsername(), u.getFullName(), u.getRole().getName());
    }
}
