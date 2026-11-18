package com.rescuemesh.server.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Returns the caller's token claims — the simplest authenticated endpoint. */
@RestController
public class MeController {

    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", jwt.getSubject());
        body.put("role", jwt.getClaimAsString("role"));
        body.put("deviceId", jwt.getClaimAsString("deviceId"));
        return body;
    }
}
