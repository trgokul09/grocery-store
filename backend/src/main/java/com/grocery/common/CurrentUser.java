package com.grocery.common;

import com.grocery.common.Exceptions.BusinessException;
import com.grocery.user.User;
import com.grocery.user.UserRepository;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import java.util.Optional;

@Component
public class CurrentUser {
    private final UserRepository users;
    public CurrentUser(UserRepository users) { this.users = users; }

    public Optional<User> find() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated() || a instanceof AnonymousAuthenticationToken) return Optional.empty();
        return users.findByUsername(a.getName());
    }
    public User require() { return find().orElseThrow(() -> new BusinessException("Not authenticated")); }
    public String role() { return find().map(u -> u.getRole().getName()).orElse(""); }
    public boolean is(String role) { return role().equals(role); }
    public boolean isStaff() { return is("STAFF"); }
    public boolean isOwner() { return is("OWNER"); }
}
