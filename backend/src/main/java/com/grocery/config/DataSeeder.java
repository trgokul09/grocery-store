package com.grocery.config;

import com.grocery.user.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DataSeeder implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private final RoleRepository roles; private final UserRepository users; private final PasswordEncoder enc;
    @Value("${app.owner.username}") String ownerUser;
    @Value("${app.owner.password}") String ownerPass;

    public DataSeeder(RoleRepository r, UserRepository u, PasswordEncoder e) { roles = r; users = u; enc = e; }

    @Override @Transactional
    public void run(String... args) {
        for (String n : new String[]{"OWNER", "MANAGER", "STAFF"})
            if (roles.findByName(n).isEmpty()) roles.save(new Role(n));
        if (users.findByUsername(ownerUser).isEmpty()) {
            users.save(new User(ownerUser, enc.encode(ownerPass), "Store Owner", roles.findByName("OWNER").orElseThrow()));
            log.warn("Seeded default OWNER '{}'. Change the password immediately.", ownerUser);
        }
    }
}
