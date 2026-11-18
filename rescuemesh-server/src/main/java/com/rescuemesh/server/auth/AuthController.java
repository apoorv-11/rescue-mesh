package com.rescuemesh.server.auth;

import com.rescuemesh.server.domain.EmailVerification;
import com.rescuemesh.server.domain.Role;
import com.rescuemesh.server.domain.SyncAudit;
import com.rescuemesh.server.domain.User;
import com.rescuemesh.server.mail.MailService;
import com.rescuemesh.server.repository.EmailVerificationRepository;
import com.rescuemesh.server.repository.SyncAuditRepository;
import com.rescuemesh.server.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints (all public; they are how tokens are obtained).
 *
 * <ul>
 *   <li>{@code POST /auth/register} — create a user. Auth v2 hardening: the
 *       account is ALWAYS created as {@link Role#CITIZEN} (any requested
 *       role is ignored — privileged roles come only from a redeemed
 *       {@code RoleAccessCode}), an email address is required, and a 6-digit
 *       verification code is issued for it.</li>
 *   <li>{@code POST /auth/verify-email} — prove the email with the code;
 *       returns a login token (auto-login) on success.</li>
 *   <li>{@code POST /auth/resend-verification} — issue a fresh code,
 *       invalidating the previous ones.</li>
 *   <li>{@code POST /auth/login} — username + password → JWT, but only once
 *       the email is verified (403 before that).</li>
 *   <li>{@code POST /auth/device} — deviceId → JWT for the user that device
 *       is bound to. Skeleton semantics: the deviceId alone is the credential
 *       here, because a device that just came back from the mesh may have no
 *       other way in. This is intentionally simple for the skeleton — the
 *       real device credential is the Ed25519 key proof introduced with
 *       envelope signing (Prompt 4), which will replace this trust model.</li>
 * </ul>
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    /** Verification codes live 15 minutes (auth v2 spec). */
    private static final long VERIFICATION_TTL_MINUTES = 15;

    /** After this many wrong attempts a verification record stops being usable. */
    private static final int MAX_VERIFICATION_ATTEMPTS = 5;

    /** Deliberately simple format check (no validation dependency): local@domain.tld. */
    private static final Pattern EMAIL_FORMAT =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public record RegisterRequest(String username, String email, String password,
                                  Role role, String deviceId) {
    }

    public record RegisterResponse(String username, String role, String deviceId) {
    }

    public record LoginRequest(String username, String password) {
    }

    public record VerifyEmailRequest(String username, String code) {
    }

    public record ResendVerificationRequest(String username) {
    }

    public record DeviceTokenRequest(String deviceId) {
    }

    public record TokenResponse(String token, String tokenType) {
    }

    private final UserRepository userRepository;
    private final EmailVerificationRepository emailVerificationRepository;
    private final SyncAuditRepository syncAuditRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final MailService mailService;

    public AuthController(UserRepository userRepository,
                          EmailVerificationRepository emailVerificationRepository,
                          SyncAuditRepository syncAuditRepository,
                          PasswordEncoder passwordEncoder,
                          AuthenticationManager authenticationManager,
                          JwtService jwtService,
                          MailService mailService) {
        this.userRepository = userRepository;
        this.emailVerificationRepository = emailVerificationRepository;
        this.syncAuditRepository = syncAuditRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.mailService = mailService;
    }

    @PostMapping("/register")
    @Transactional
    public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
        if (request.username() == null || request.username().isBlank()
                || request.password() == null || request.password().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "username and password are required");
        }
        if (request.email() == null || request.email().isBlank()
                || !EMAIL_FORMAT.matcher(request.email().trim()).matches()) {
            return error(HttpStatus.BAD_REQUEST, "a valid email is required");
        }
        String email = request.email().trim();
        if (userRepository.existsByUsername(request.username())) {
            return error(HttpStatus.CONFLICT, "username already exists");
        }
        if (userRepository.existsByEmail(email)) {
            return error(HttpStatus.CONFLICT, "email already registered");
        }
        if (request.deviceId() != null && userRepository.existsByDeviceId(request.deviceId())) {
            return error(HttpStatus.CONFLICT, "deviceId already bound to another user");
        }
        User user = new User();
        user.setUsername(request.username());
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        // Auth v2: the requested role is IGNORED on purpose. Self-registration
        // must never mint a privileged account; VOLUNTEER/COORDINATOR/ADMIN
        // are granted only by redeeming an admin-issued RoleAccessCode.
        user.setRole(Role.CITIZEN);
        user.setEmailVerified(false);
        user.setDeviceId(request.deviceId());
        userRepository.save(user);
        issueVerificationCode(user);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new RegisterResponse(user.getUsername(), user.getRole().name(),
                        user.getDeviceId()));
    }

    @PostMapping("/verify-email")
    @Transactional
    public ResponseEntity<?> verifyEmail(@RequestBody VerifyEmailRequest request) {
        if (request.username() == null || request.code() == null || request.code().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "username and code are required");
        }
        var userOpt = userRepository.findByUsername(request.username());
        if (userOpt.isEmpty()) {
            // Same response as a wrong code: do not leak which usernames exist.
            return error(HttpStatus.BAD_REQUEST, "invalid or expired code");
        }
        User user = userOpt.get();
        if (user.isEmailVerified()) {
            return error(HttpStatus.BAD_REQUEST, "email already verified");
        }
        var recordOpt = emailVerificationRepository
                .findFirstByUserAndConsumedFalseOrderByCreatedAtDesc(user);
        if (recordOpt.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "invalid or expired code");
        }
        EmailVerification record = recordOpt.get();
        if (record.getExpiresAt().isBefore(Instant.now())
                || record.getAttempts() >= MAX_VERIFICATION_ATTEMPTS) {
            return error(HttpStatus.BAD_REQUEST, "invalid or expired code");
        }
        String presentedHash = AccessCodes.sha256Hex(request.code().trim());
        boolean matches = MessageDigest.isEqual(
                presentedHash.getBytes(StandardCharsets.UTF_8),
                record.getCodeHash().getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            record.setAttempts(record.getAttempts() + 1);
            emailVerificationRepository.save(record);
            return error(HttpStatus.BAD_REQUEST, "invalid or expired code");
        }
        record.setConsumed(true);
        emailVerificationRepository.save(record);
        user.setEmailVerified(true);
        userRepository.save(user);
        // Auto-login: proving the email is enough to hand out the first token.
        return ResponseEntity.ok(new TokenResponse(jwtService.issueToken(user), "Bearer"));
    }

    @PostMapping("/resend-verification")
    @Transactional
    public ResponseEntity<?> resendVerification(@RequestBody ResendVerificationRequest request) {
        if (request.username() == null || request.username().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "username is required");
        }
        var userOpt = userRepository.findByUsername(request.username());
        if (userOpt.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "unknown user");
        }
        User user = userOpt.get();
        if (user.isEmailVerified()) {
            return error(HttpStatus.BAD_REQUEST, "email already verified");
        }
        issueVerificationCode(user);
        return ResponseEntity.ok(Map.of("status", "verification code sent"));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(),
                            request.password()));
        } catch (BadCredentialsException e) {
            return error(HttpStatus.UNAUTHORIZED, "invalid credentials");
        }
        User user = userRepository.findByUsername(request.username()).orElseThrow();
        // Auth v2: a correct password is not enough — the email must be
        // verified first, so throwaway/fake addresses cannot mint accounts
        // that can actually sign in.
        if (!user.isEmailVerified()) {
            return error(HttpStatus.FORBIDDEN, "email not verified");
        }
        return ResponseEntity.ok(new TokenResponse(jwtService.issueToken(user), "Bearer"));
    }

    @PostMapping("/device")
    public ResponseEntity<?> deviceToken(@RequestBody DeviceTokenRequest request) {
        if (request.deviceId() == null || request.deviceId().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "deviceId is required");
        }
        var found = userRepository.findByDeviceId(request.deviceId());
        if (found.isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, "unknown device");
        }
        User user = found.get();
        SyncAudit audit = new SyncAudit();
        audit.setDeviceId(request.deviceId());
        audit.setAction("DEVICE_TOKEN_ISSUED");
        audit.setDetail("Device token issued for user " + user.getUsername());
        syncAuditRepository.save(audit);
        return ResponseEntity.ok(new TokenResponse(jwtService.issueToken(user), "Bearer"));
    }

    /**
     * Invalidates every outstanding code for the user, stores a fresh one
     * (hash only) and sends the plaintext through the mail service.
     */
    private void issueVerificationCode(User user) {
        for (EmailVerification outstanding
                : emailVerificationRepository.findByUserAndConsumedFalse(user)) {
            outstanding.setConsumed(true);
            emailVerificationRepository.save(outstanding);
        }
        String code = AccessCodes.generateVerificationCode();
        EmailVerification record = new EmailVerification();
        record.setUser(user);
        record.setCodeHash(AccessCodes.sha256Hex(code));
        record.setExpiresAt(Instant.now().plus(VERIFICATION_TTL_MINUTES, ChronoUnit.MINUTES));
        record.setAttempts(0);
        record.setConsumed(false);
        emailVerificationRepository.save(record);
        mailService.sendVerificationCode(user.getEmail(), code);
    }

    private ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
