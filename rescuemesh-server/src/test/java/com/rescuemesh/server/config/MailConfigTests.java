package com.rescuemesh.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.rescuemesh.server.mail.LogMailService;
import com.rescuemesh.server.mail.MailService;
import com.rescuemesh.server.mail.ResendMailService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Mail provider selection: {@link LogMailService} in dev mode (no Resend
 * API key, or a blank one — docker-compose passes an empty default), and
 * {@link ResendMailService} once a key is configured. Direct bean
 * construction, so no network or application context is involved
 * (constructing the Resend client does not call the API).
 */
class MailConfigTests {

    private static final String FROM = "RescueMesh <onboarding@resend.dev>";

    private final MailConfig config = new MailConfig();

    @Test
    void logMailServiceWhenResendKeyAbsent() {
        MailService service = config.mailService(new MockEnvironment(), FROM);
        assertThat(service).isInstanceOf(LogMailService.class);
    }

    @Test
    void logMailServiceWhenResendKeyBlank() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("rescuemesh.mail.resend-api-key", "  ");
        MailService service = config.mailService(environment, FROM);
        assertThat(service).isInstanceOf(LogMailService.class);
    }

    @Test
    void resendMailServiceWhenResendKeySet() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("rescuemesh.mail.resend-api-key", "re_test_key_not_real");
        MailService service = config.mailService(environment, FROM);
        assertThat(service).isInstanceOf(ResendMailService.class);
    }
}
