package com.rescuemesh.server.web;

import com.rescuemesh.server.auth.AccessCodes;
import com.rescuemesh.server.domain.ApplicationStatus;
import com.rescuemesh.server.domain.Role;
import com.rescuemesh.server.domain.RoleAccessCode;
import com.rescuemesh.server.domain.RoleApplication;
import com.rescuemesh.server.domain.User;
import com.rescuemesh.server.mail.MailService;
import com.rescuemesh.server.repository.RoleAccessCodeRepository;
import com.rescuemesh.server.repository.RoleApplicationRepository;
import com.rescuemesh.server.repository.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin side of role access (auth v2), all under {@code /admin/**} so the
 * existing ADMIN-only security rule covers every endpoint here.
 *
 * <p>Approving an application (or creating a code directly) generates a
 * {@link RoleAccessCode}: the plaintext is emailed to the user AND returned
 * once in this admin response. Returning it once is deliberate — in the
 * offline demo the mail service may be log-only, so the admin needs a way
 * to hand the code to the user in person. Only the hash is ever stored.
 */
@RestController
@RequestMapping("/admin")
public class AdminRolesController {

    /** Role access codes live 7 days (auth v2 spec). */
    private static final long ROLE_CODE_TTL_DAYS = 7;

    public record RejectRequest(String note) {
    }

    public record DirectCodeRequest(String username, String role) {
    }

    private final UserRepository userRepository;
    private final RoleApplicationRepository roleApplicationRepository;
    private final RoleAccessCodeRepository roleAccessCodeRepository;
    private final MailService mailService;

    public AdminRolesController(UserRepository userRepository,
                                RoleApplicationRepository roleApplicationRepository,
                                RoleAccessCodeRepository roleAccessCodeRepository,
                                MailService mailService) {
        this.userRepository = userRepository;
        this.roleApplicationRepository = roleApplicationRepository;
        this.roleAccessCodeRepository = roleAccessCodeRepository;
        this.mailService = mailService;
    }

    @GetMapping("/role-applications")
    public ResponseEntity<?> listApplications(
            @RequestParam(name = "status", defaultValue = "PENDING") String status) {
        ApplicationStatus parsed;
        try {
            parsed = ApplicationStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, "unknown status");
        }
        List<RoleApplicationView> views = roleApplicationRepository
                .findByStatusOrderByCreatedAtAsc(parsed)
                .stream().map(RoleApplicationView::from).toList();
        return ResponseEntity.ok(views);
    }

    @PostMapping("/role-applications/{id}/approve")
    @Transactional
    public ResponseEntity<?> approve(@AuthenticationPrincipal Jwt jwt,
                                     @PathVariable Long id) {
        var applicationOpt = roleApplicationRepository.findById(id);
        if (applicationOpt.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "application not found");
        }
        RoleApplication application = applicationOpt.get();
        if (application.getStatus() != ApplicationStatus.PENDING) {
            return error(HttpStatus.CONFLICT, "application already reviewed");
        }
        application.setStatus(ApplicationStatus.APPROVED);
        application.setReviewedBy(jwt.getSubject());
        application.setReviewedAt(Instant.now());
        roleApplicationRepository.save(application);

        String plaintext = issueCode(application.getUser(),
                application.getRequestedRole(), jwt.getSubject(), application);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("application", RoleApplicationView.from(application));
        // Plaintext shown ONCE, to the admin only (see class javadoc).
        body.put("accessCode", plaintext);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/role-applications/{id}/reject")
    @Transactional
    public ResponseEntity<?> reject(@AuthenticationPrincipal Jwt jwt,
                                    @PathVariable Long id,
                                    @RequestBody(required = false) RejectRequest request) {
        var applicationOpt = roleApplicationRepository.findById(id);
        if (applicationOpt.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "application not found");
        }
        RoleApplication application = applicationOpt.get();
        if (application.getStatus() != ApplicationStatus.PENDING) {
            return error(HttpStatus.CONFLICT, "application already reviewed");
        }
        application.setStatus(ApplicationStatus.REJECTED);
        application.setReviewNote(request != null ? request.note() : null);
        application.setReviewedBy(jwt.getSubject());
        application.setReviewedAt(Instant.now());
        roleApplicationRepository.save(application);
        return ResponseEntity.ok(RoleApplicationView.from(application));
    }

    @PostMapping("/role-codes")
    @Transactional
    public ResponseEntity<?> createDirectCode(@AuthenticationPrincipal Jwt jwt,
                                              @RequestBody DirectCodeRequest request) {
        Role role;
        try {
            role = Role.valueOf(request.role());
        } catch (IllegalArgumentException | NullPointerException e) {
            return error(HttpStatus.BAD_REQUEST, "unknown role");
        }
        var userOpt = userRepository.findByUsername(request.username());
        if (userOpt.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "user not found");
        }
        // Direct codes may grant ANY role, including ADMIN — this endpoint is
        // itself ADMIN-only, so this is the only path that can mint an admin.
        String plaintext = issueCode(userOpt.get(), role, jwt.getSubject(), null);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", userOpt.get().getUsername());
        body.put("role", role.name());
        // Plaintext shown ONCE, to the admin only (see class javadoc).
        body.put("accessCode", plaintext);
        return ResponseEntity.ok(body);
    }

    /**
     * Creates the {@link RoleAccessCode} row (hash only), emails the
     * plaintext to the user and returns the plaintext for the one-time
     * admin response.
     */
    private String issueCode(User user, Role role, String createdBy,
                             RoleApplication application) {
        String plaintext = AccessCodes.generateRoleAccessCode();
        RoleAccessCode code = new RoleAccessCode();
        code.setUser(user);
        code.setRole(role);
        code.setCodeHash(AccessCodes.sha256Hex(plaintext));
        code.setExpiresAt(Instant.now().plus(ROLE_CODE_TTL_DAYS, ChronoUnit.DAYS));
        code.setCreatedBy(createdBy);
        code.setApplication(application);
        roleAccessCodeRepository.save(code);
        mailService.sendRoleAccessCode(user.getEmail(), role.name(), plaintext);
        return plaintext;
    }

    private ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
