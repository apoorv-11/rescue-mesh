package com.rescuemesh.server.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development {@link MailService}: no Resend API key is configured, so NO
 * real email is sent — the code is written to the server log at INFO
 * instead. This is the default for local runs and the offline demo; set
 * RESEND_API_KEY to switch to {@link ResendMailService}.
 */
public class LogMailService implements MailService {

    private static final Logger log = LoggerFactory.getLogger(LogMailService.class);

    @Override
    public void sendVerificationCode(String email, String code) {
        log.info("[DEV MAIL — no real email sent] verification code for {}: {}",
                email, code);
    }

    @Override
    public void sendRoleAccessCode(String email, String role, String code) {
        log.info("[DEV MAIL — no real email sent] role access code for {} (role {}): {}",
                email, role, code);
    }
}
