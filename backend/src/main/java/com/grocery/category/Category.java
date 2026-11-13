package com.grocery.category;

import jakarta.persistence.*;

@Entity @Table(name = "categories")
public class Category {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, length = 100) private String name;
    private String description;
    @Column(name = "is_active", nullable = false) private boolean active = true;

    protected Category() {}
    public Category(String name, String description) { this.name = name; this.description = description; }
    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String n) { name = n; }
    public String getDescription() { return description; }
    public void setDescription(String d) { description = d; }
    public boolean isActive() { return active; }
    public void setActive(boolean a) { active = a; }
}
