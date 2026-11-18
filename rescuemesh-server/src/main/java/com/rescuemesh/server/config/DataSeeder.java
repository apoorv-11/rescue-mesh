package com.rescuemesh.server.config;

import com.rescuemesh.server.domain.Role;
import com.rescuemesh.server.domain.User;
import com.rescuemesh.server.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds the first ADMIN user on boot if it does not exist yet, so a fresh
 * deployment always has one privileged login. Credentials come from
 * {@code ADMIN_USERNAME} / {@code ADMIN_PASSWORD} (local-dev defaults in
 * application.yml — override them outside your laptop).
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminUsername;
    private final String adminPassword;

    public DataSeeder(UserRepository userRepository,
                      PasswordEncoder passwordEncoder,
                      @Value("${rescuemesh.seed.admin-username}") String adminUsername,
                      @Value("${rescuemesh.seed.admin-password}") String adminPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(String... args) {
        if (userRepository.findByUsername(adminUsername).isEmpty()) {
            User admin = new User();
            admin.setUsername(adminUsername);
            admin.setPasswordHash(passwordEncoder.encode(adminPassword));
            admin.setRole(Role.ADMIN);
            // Auth v2: login requires a verified email, but the seeded admin
            // has no mailbox to verify with — it is created already verified.
            // This is the ONE exemption from the verify-before-login rule.
            admin.setEmailVerified(true);
            userRepository.save(admin);
            log.warn("Seeded ADMIN user '{}' — change the password outside local dev",
                    adminUsername);
        }
    }
}
