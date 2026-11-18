package com.rescuemesh.server.web;

import com.rescuemesh.server.domain.ApplicationStatus;
import com.rescuemesh.server.domain.Role;
import com.rescuemesh.server.domain.RoleApplication;
import com.rescuemesh.server.domain.User;
import com.rescuemesh.server.repository.RoleApplicationRepository;
import com.rescuemesh.server.repository.UserRepository;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Role applications (auth v2). An authenticated, email-verified user asks
 * for VOLUNTEER or COORDINATOR; an admin reviews the application
 * ({@code AdminRolesController}) and approval issues a one-time access code
 * that the user redeems at {@code POST /profile/role-code}. ADMIN can never
 * be applied for — admin codes are created directly by an existing admin.
 */
@RestController
@RequestMapping("/roles")
public class RolesController {

    public record ApplyRequest(String requestedRole, String organization, String reason) {
    }

    private final UserRepository userRepository;
    private final RoleApplicationRepository roleApplicationRepository;

    public RolesController(UserRepository userRepository,
                           RoleApplicationRepository roleApplicationRepository) {
        this.userRepository = userRepository;
        this.roleApplicationRepository = roleApplicationRepository;
    }

    @PostMapping("/apply")
    @Transactional
    public ResponseEntity<?> apply(@AuthenticationPrincipal Jwt jwt,
                                   @RequestBody ApplyRequest request) {
        User user = currentUser(jwt);
        if (!user.isEmailVerified()) {
            return error(HttpStatus.FORBIDDEN, "email not verified");
        }
        Role requested;
        try {
            requested = Role.valueOf(request.requestedRole());
        } catch (IllegalArgumentException | NullPointerException e) {
            return error(HttpStatus.BAD_REQUEST, "unknown role");
        }
        if (requested != Role.VOLUNTEER && requested != Role.COORDINATOR) {
            return error(HttpStatus.BAD_REQUEST,
                    "only VOLUNTEER or COORDINATOR can be applied for");
        }
        if (roleApplicationRepository.existsByUserAndStatus(user, ApplicationStatus.PENDING)) {
            return error(HttpStatus.CONFLICT, "a role application is already pending");
        }
        RoleApplication application = new RoleApplication();
        application.setUser(user);
        application.setRequestedRole(requested);
        application.setOrganization(request.organization());
        application.setReason(request.reason());
        application.setStatus(ApplicationStatus.PENDING);
        roleApplicationRepository.save(application);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(RoleApplicationView.from(application));
    }

    @GetMapping("/my-applications")
    public List<RoleApplicationView> myApplications(@AuthenticationPrincipal Jwt jwt) {
        User user = currentUser(jwt);
        return roleApplicationRepository.findByUserOrderByCreatedAtDesc(user)
                .stream().map(RoleApplicationView::from).toList();
    }

    private User currentUser(Jwt jwt) {
        return userRepository.findByUsername(jwt.getSubject()).orElseThrow();
    }

    private ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
