package com.rescuemesh.server.web;

import com.rescuemesh.server.auth.AccessCodes;
import com.rescuemesh.server.auth.AuthController;
import com.rescuemesh.server.auth.JwtService;
import com.rescuemesh.server.domain.RoleAccessCode;
import com.rescuemesh.server.domain.User;
import com.rescuemesh.server.repository.RoleAccessCodeRepository;
import com.rescuemesh.server.repository.UserRepository;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated self-service profile actions (auth v2): redeeming a role
 * access code and (re)binding the account's device.
 */
@RestController
@RequestMapping("/profile")
public class ProfileController {

    public record RedeemCodeRequest(String code) {
    }

    public record BindDeviceRequest(String deviceId) {
    }

    private final UserRepository userRepository;
    private final RoleAccessCodeRepository roleAccessCodeRepository;
    private final JwtService jwtService;

    public ProfileController(UserRepository userRepository,
                             RoleAccessCodeRepository roleAccessCodeRepository,
                             JwtService jwtService) {
        this.userRepository = userRepository;
        this.roleAccessCodeRepository = roleAccessCodeRepository;
        this.jwtService = jwtService;
    }

    /**
     * Redeems a one-time role access code: the caller's role becomes the
     * code's role and a FRESH token is returned, because the old token still
     * carries the old role claim.
     */
    @PostMapping("/role-code")
    @Transactional
    public ResponseEntity<?> redeemRoleCode(@AuthenticationPrincipal Jwt jwt,
                                            @RequestBody RedeemCodeRequest request) {
        if (request.code() == null || request.code().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "code is required");
        }
        User user = currentUser(jwt);
        String hash = AccessCodes.sha256Hex(normalise(request.code()));
        var codeOpt = roleAccessCodeRepository.findByCodeHash(hash);
        if (codeOpt.isEmpty() || !codeOpt.get().getUser().getId().equals(user.getId())) {
            // A code issued to someone else is indistinguishable from a typo.
            return error(HttpStatus.BAD_REQUEST, "invalid code");
        }
        RoleAccessCode code = codeOpt.get();
        if (code.getUsedAt() != null) {
            return error(HttpStatus.CONFLICT, "code already used");
        }
        if (code.getExpiresAt().isBefore(Instant.now())) {
            return error(HttpStatus.BAD_REQUEST, "code expired");
        }
        code.setUsedAt(Instant.now());
        roleAccessCodeRepository.save(code);
        user.setRole(code.getRole());
        userRepository.save(user);
        return ResponseEntity.ok(
                new AuthController.TokenResponse(jwtService.issueToken(user), "Bearer"));
    }

    /**
     * Binds a device to the caller's account (reinstall / new phone — the
     * register-time binding cannot cover those). A deviceId already bound
     * to a DIFFERENT user is a conflict; rebinding your own is a no-op.
     */
    @PostMapping("/bind-device")
    @Transactional
    public ResponseEntity<?> bindDevice(@AuthenticationPrincipal Jwt jwt,
                                        @RequestBody BindDeviceRequest request) {
        if (request.deviceId() == null || request.deviceId().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "deviceId is required");
        }
        User user = currentUser(jwt);
        var holderOpt = userRepository.findByDeviceId(request.deviceId());
        if (holderOpt.isPresent() && !holderOpt.get().getId().equals(user.getId())) {
            return error(HttpStatus.CONFLICT, "deviceId already bound to another user");
        }
        user.setDeviceId(request.deviceId());
        userRepository.save(user);
        return ResponseEntity.ok(Map.of(
                "username", user.getUsername(),
                "deviceId", user.getDeviceId()));
    }

    private User currentUser(Jwt jwt) {
        return userRepository.findByUsername(jwt.getSubject()).orElseThrow();
    }

    /** Codes are typed by humans: tolerate stray spaces and lower case. */
    private String normalise(String code) {
        return code.trim().toUpperCase().replace(" ", "");
    }

    private ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
