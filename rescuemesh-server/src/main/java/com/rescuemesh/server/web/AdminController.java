package com.rescuemesh.server.web;

import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only probe endpoint. It exists to prove role-based security works:
 * requests without an ADMIN JWT get 401/403, an ADMIN token gets 200.
 * (The real command map for coordinators/admins arrives with the dashboard.)
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    @GetMapping("/ping")
    public Map<String, String> ping(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("status", "admin-ok", "user", jwt.getSubject());
    }
}
