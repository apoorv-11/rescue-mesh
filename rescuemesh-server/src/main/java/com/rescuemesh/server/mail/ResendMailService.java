package com.rescuemesh.server.mail;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Real {@link MailService} over the Resend HTTPS API (official
 * {@code resend-java} SDK), active only when a Resend API key is configured
 * (see {@code MailConfig}). HTTPS is used instead of SMTP because several
 * hosts (e.g. Render free services) block outbound SMTP ports.
 *
 * <p>Delivery failures never break the calling flow: a {@link ResendException}
 * is caught and logged at ERROR (recipient + provider error only — never the
 * code or the API key), and the user can simply request a fresh code.
 */
public class ResendMailService implements MailService {

    private static final Logger log = LoggerFactory.getLogger(ResendMailService.class);

    private final Resend resend;
    private final String from;

    public ResendMailService(String apiKey, String from) {
        this.resend = new Resend(apiKey);
        this.from = from;
    }

    @Override
    public void sendVerificationCode(String email, String code) {
        send(email,
                "Your RescueMesh verification code",
                "Your RescueMesh verification code is " + code + ".\n\n"
                        + "It expires in 15 minutes. If you did not create a "
                        + "RescueMesh account, ignore this email.");
    }

    @Override
    public void sendRoleAccessCode(String email, String role, String code) {
        send(email,
                "Your RescueMesh role access code",
                "Your RescueMesh role application was approved.\n\n"
                        + "Role: " + role + "\nAccess code: " + code + "\n\n"
                        + "Enter the code in the app under Profile → Role access "
                        + "code within 7 days. The code works once.");
    }

    private void send(String to, String subject, String text) {
        CreateEmailOptions options = CreateEmailOptions.builder()
                .from(from)
                .to(to)
                .subject(subject)
                .text(text)
                .build();
        try {
            CreateEmailResponse response = resend.emails().send(options);
            log.info("Mail via Resend to {} accepted (id: {})", to, response.getId());
        } catch (ResendException e) {
            // Never log the code or API key — recipient + provider error only.
            log.error("Mail via Resend to {} FAILED ({}): {}", to,
                    e.getErrorName() != null ? e.getErrorName() : "status " + e.getStatusCode(),
                    e.getMessage());
        } catch (RuntimeException e) {
            log.error("Mail via Resend to {} FAILED (unexpected): {}", to, e.getMessage());
        }
    }
}
