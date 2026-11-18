package com.rescuemesh.server.config;

import com.rescuemesh.server.mail.LogMailService;
import com.rescuemesh.server.mail.MailService;
import com.rescuemesh.server.mail.ResendMailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Chooses the {@link MailService} implementation from
 * {@code rescuemesh.mail.resend-api-key} (env {@code RESEND_API_KEY}):
 *
 * <ul>
 *   <li>key set to a real value → {@link ResendMailService} (Resend HTTPS
 *       API; sender from {@code rescuemesh.mail.from}, env
 *       {@code RESEND_FROM});</li>
 *   <li>key absent OR blank → {@link LogMailService}: dev mode, no real
 *       email is sent and codes appear in the server log.</li>
 * </ul>
 *
 * The blank case matters: docker-compose passes {@code RESEND_API_KEY}
 * through with an empty default, which shows up here as an empty string
 * rather than a missing property — an explicit non-blank check (instead of
 * a plain {@code @ConditionalOnProperty}) keeps dev mode working there.
 */
@Configuration
public class MailConfig {

    private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

    @Bean
    MailService mailService(Environment environment,
                            @Value("${rescuemesh.mail.from:}") String from) {
        String apiKey = environment.getProperty("rescuemesh.mail.resend-api-key");
        if (apiKey != null && !apiKey.isBlank()) {
            log.info("Mail: Resend HTTPS API (from: {})", from);
            return new ResendMailService(apiKey, from);
        }
        log.warn("Mail: no RESEND_API_KEY configured — DEV MODE, no real email "
                + "is sent; verification and role codes are written to this log");
        return new LogMailService();
    }
}
