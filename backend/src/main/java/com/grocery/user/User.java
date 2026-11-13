package com.grocery.user;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "users")
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, length = 50) private String username;
    @Column(name = "password_hash", nullable = false) private String passwordHash;
    @Column(name = "full_name", nullable = false) private String fullName;
    @ManyToOne(fetch = FetchType.EAGER, optional = false) @JoinColumn(name = "role_id") private Role role;
    @Column(name = "is_active", nullable = false) private boolean active = true;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();

    protected User() {}
    public User(String username, String passwordHash, String fullName, Role role) {
        this.username = username; this.passwordHash = passwordHash; this.fullName = fullName; this.role = role;
    }
    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getFullName() { return fullName; }
    public Role getRole() { return role; }
    public boolean isActive() { return active; }
    public void setActive(boolean a) { this.active = a; }
    public void setFullName(String n) { this.fullName = n; }
    public void setRole(Role r) { this.role = r; }
    public void setPasswordHash(String h) { this.passwordHash = h; }
    public java.time.Instant getCreatedAt() { return createdAt; }
}
