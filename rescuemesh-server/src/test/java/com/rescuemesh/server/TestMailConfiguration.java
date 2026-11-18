package com.rescuemesh.server;

import com.rescuemesh.server.mail.MailService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test mail: a {@link MailService} that records the last code sent to each
 * recipient instead of mailing/logging it, so flow tests can complete the
 * verify-email and role-code steps exactly as a real user would.
 */
@TestConfiguration
public class TestMailConfiguration {

    @Bean
    @Primary
    CapturingMailService capturingMailService() {
        return new CapturingMailService();
    }

    public static class CapturingMailService implements MailService {

        private final Map<String, String> verificationCodes = new ConcurrentHashMap<>();
        private final Map<String, String> roleAccessCodes = new ConcurrentHashMap<>();

        @Override
        public void sendVerificationCode(String email, String code) {
            verificationCodes.put(email, code);
        }

        @Override
        public void sendRoleAccessCode(String email, String role, String code) {
            roleAccessCodes.put(email, code);
        }

        public String lastVerificationCode(String email) {
            return verificationCodes.get(email);
        }

        public String lastRoleAccessCode(String email) {
            return roleAccessCodes.get(email);
        }
    }
}
