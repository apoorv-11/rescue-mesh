package com.rescuemesh.server.mail;

/**
 * Outbound mail for auth v2. Two implementations exist (wired in
 * {@code MailConfig}): {@link LogMailService} when no Resend API key is
 * configured (local development — codes land in the server log), and
 * {@link ResendMailService} when {@code RESEND_API_KEY} is set.
 */
public interface MailService {

    /** Sends the 6-digit email verification code issued at register/resend. */
    void sendVerificationCode(String email, String code);

    /** Sends the one-time role access code issued on approval / direct creation. */
    void sendRoleAccessCode(String email, String role, String code);
}
